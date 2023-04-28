package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.UGHException;

@PluginImplementation
@Log4j2
public class FetchImagesFromMetadataStepPlugin implements IStepPluginVersion2 {

    @Getter
    private String title = "intranda_step_fetch_images_from_metadata";
    @Getter
    private Step step;
    @Getter
    private String value;
    @Getter
    private boolean allowTaskFinishButtons;
    private String returnPath;
    private Process process;
    private Prefs prefs;
    private String imageMetadata;
    private String folder;
    private boolean ignoreFileExtension;
    private String mode;
    private boolean ignoreCopyErrors;
    private StorageProviderInterface storageProvider;
    private boolean startExport;
    private boolean exportImages;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        this.process = step.getProzess();
        this.prefs = process.getRegelsatz().getPreferences();
        this.storageProvider = StorageProvider.getInstance();

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        this.imageMetadata = myconfig.getString("filenameMetadata");
        this.folder = myconfig.getString("fileHandling/@folder");
        this.ignoreFileExtension = myconfig.getBoolean("fileHandling/@ignoreFileExtension", false);
        this.mode = myconfig.getString("fileHandling/@mode", "copy");
        this.ignoreCopyErrors = myconfig.getBoolean("fileHandling/@ignoreCopyErrors", false);
        this.startExport = myconfig.getBoolean("export/@enabled", false);
        this.exportImages = myconfig.getBoolean("export/@exportImages", true);
        if (!folder.endsWith("/")) {
            folder = folder + "/";
        }

        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("FetchImagesFromMetadata step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_fetch_images_from_metadata.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    private String findExistingMetadata(DocStruct physical, String elementType) {
        if (physical.getAllMetadata() != null) {
            for (Metadata md : physical.getAllMetadata()) {
                if (md.getType().getName().equals(elementType)) {
                    return md.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public PluginReturnValue run() {
        boolean successfull = true;

        Process proc = step.getProzess();
        Fileformat fileformat;
        try {
            fileformat = proc.readMetadataFile();

            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct physical = dd.getPhysicalDocStruct();
            DocStruct logical = dd.getLogicalDocStruct();

            // cleaning up the mets file
            List<DocStruct> physicalChildren = physical.getAllChildren();
            for (DocStruct child : physicalChildren) {
                logical.removeReferenceFrom(child);
                physical.removeChild(child);
            }

            if (findExistingMetadata(physical, "pathimagefiles") == null) {
                Metadata imagePath = new Metadata(this.prefs.getMetadataTypeByName("pathimagefiles"));
                imagePath.setValue(proc.getConfiguredImageFolder("media"));
                physical.addMetadata(imagePath);
            }

            List<String> lstImages = MetadataManager.getAllMetadataValues(proc.getId(), imageMetadata);
            Collections.sort(lstImages);

            boolean boImagesImported = false;

            int iPageNumber = 1;
            String strProcessImageFolder = proc.getConfiguredImageFolder("media");

            // get list with filenames in target directory
            List<String> existingImages = storageProvider.list(strProcessImageFolder);

            for (String strImage : lstImages) {

                if (existingImages.contains(strImage.replace(" ", "_"))) {
                    log.debug("A file with the Name: " + strImage + " already exists for this process.");
                    Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG,
                            "A file with the Name: " + strImage + " already exists for this process.");
                    //                    continue;
                }

                if (ignoreFileExtension) {
                    int index = strImage.lastIndexOf(".");
                    if (index > 0) {
                        strImage = strImage.substring(0, index);
                    }
                }
                Result result = getAndSavePage(strImage, strProcessImageFolder, dd, iPageNumber);
                DocStruct page = result.getPage();
                iPageNumber++;
                if (page != null) {
                    physical.addChild(page);
                    logical.addReferenceTo(page, "logical_physical");
                    boImagesImported = true;

                    //                    iPageNumber++;
                } else if (ignoreCopyErrors) {
                    Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, result.getMessage());
                } else {
                    log.error("Could not find image " + strImage + " for process " + proc.getTitel());
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, result.getMessage());
                    successfull = false;
                }
            }

            //and save the metadata again.
            process.writeMetadataFile(fileformat);

            if (boImagesImported) {
                Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, "added images to " + proc.getTitel());
                log.info("Images imported for process " + proc.getTitel());
            }

            log.info("FetchImagesFromMetadata step plugin executed");

        } catch (IOException | SwapException | DAOException | UGHException e) {
            log.error(e);
            successfull = false;
        }

        if (!successfull) {
            return PluginReturnValue.ERROR;
        }

        if (this.startExport && this.process != null) {

            exportProcess(this.process, this.exportImages);

        }
        return PluginReturnValue.FINISH;
    }

    /**
     * Find the specified image file in the hashmap. If it is there, copy the file to a (new, if necessary) subfolder of the main folder, named after
     * the ID of the MetsMods file. Return a new DocStruct with the filename and the location of the file.
     */
    private Result getAndSavePage(String strImage, String strProcessImageFolder, DigitalDocument dd, int iPageNumber)
            throws UGHException, IOException {

        List<Path> imagePaths = this.storageProvider.listFiles(folder, path -> {
            return path.getFileName().toString().matches("\\Q" + strImage + "\\E" + "\\..*");
        });

        if (imagePaths.isEmpty()) {
            return new Result("There was no file with the name:" + strImage + " in the images folder.", null);
        }
        //for now take first matching image
        File file = imagePaths.get(0).toFile();
        if (!file.exists()) {
            return new Result("There was an error processing the file: " + strImage + " in the images folder.", null);
        }

        //create subfolder for images, as necessary:
        Path path = Paths.get(strProcessImageFolder);
        this.storageProvider.createDirectories(path);

        //copy or move original file:
        Path pathSource = Paths.get(file.getAbsolutePath());
        //replace spaces with "_"
        String fileName = file.getName().replace(" ", "_");
        Path pathDest = Paths.get(strProcessImageFolder + fileName);

        switch (this.mode) {
            case "move":
                this.storageProvider.move(pathSource, pathDest);
                Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG, "Image moved into process folder: " + strImage);
                break;
            case "copy":
            default:
                this.storageProvider.copyFile(pathSource, pathDest);
                Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG, "Image copied into process folder: " + strImage);

        }
        File fileCopy = new File(pathDest.toString());

        DocStructType pageType = prefs.getDocStrctTypeByName("page");
        DocStruct dsPage = dd.createDocStruct(pageType);

        //physical page number : just increment for this folio
        MetadataType typePhysPage = prefs.getMetadataTypeByName("physPageNumber");
        Metadata mdPhysPage = new Metadata(typePhysPage);
        mdPhysPage.setValue(String.valueOf(iPageNumber));
        dsPage.addMetadata(mdPhysPage);

        //logical page number : take the file name
        MetadataType typeLogPage = prefs.getMetadataTypeByName("logicalPageNumber");
        Metadata mdLogPage = new Metadata(typeLogPage);

        mdLogPage.setValue(strImage);
        dsPage.addMetadata(mdLogPage);

        ContentFile cf = new ContentFile();
        if (SystemUtils.IS_OS_WINDOWS) {
            cf.setLocation("file:" + fileCopy.getCanonicalPath());
        } else {
            cf.setLocation("file:/" + fileCopy.getCanonicalPath());
        }
        dsPage.addContentFile(cf);
        dsPage.setImageName(fileCopy.getName());

        return new Result("", dsPage);
    }

    /**
     * Do the export of the process
     */
    private void exportProcess(Process p, boolean exportImg) {
        try {
            IExportPlugin export = null;
            String pluginName = ProcessManager.getExportPluginName(p.getId());
            if (StringUtils.isNotEmpty(pluginName)) {
                try {
                    export = (IExportPlugin) PluginLoader.getPluginByTitle(PluginType.Export, pluginName);
                } catch (Exception e) {
                    log.error("Can't load export plugin, use default plugin", e);
                    export = new ExportDms();
                }
            }
            if (export == null) {
                export = new ExportDms();
            }
            export.setExportFulltext(false);
            export.setExportImages(exportImg);
            export.startExport(p);
            log.info("Export finished inside of catalogue poller for process with ID " + p.getId());
            Helper.addMessageToProcessJournal(p.getId(), LogType.DEBUG, "Process successfully exported by catalogue poller");
        } catch (NoSuchMethodError | Exception e) {
            log.error("Exception during the export of process " + p.getId(), e);
        }
    }

    @Data
    @AllArgsConstructor
    public class Result {
        private String message;
        private DocStruct page;
    }

}
