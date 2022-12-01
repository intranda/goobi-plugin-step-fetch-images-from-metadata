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
import org.apache.commons.lang.SystemUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.MetadataManager;
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
    private String imageFiletype;
    private boolean ignoreFileExtension;
    private String mode;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        this.process = step.getProzess();
        this.prefs = process.getRegelsatz().getPreferences();

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        this.imageMetadata = myconfig.getString("filenameMetadata");
        this.folder = myconfig.getString("fileHandling/@folder");
        this.ignoreFileExtension = myconfig.getBoolean("fileHandling/@ignoreFileExtension", false);
        this.mode = myconfig.getString("fileHandling/@mode", "copy");

        if (!folder.endsWith("/")) {
            folder = folder + "/";
        }

        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("FetchImagesFromMetadata step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        //        return PluginGuiType.FULL;
        // return PluginGuiType.PART;
        // return PluginGuiType.PART_AND_FULL;
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

            List<String> lstImages = MetadataManager.getAllMetadataValues(proc.getId(), imageMetadata);
            Collections.sort(lstImages);

            Boolean boImagesImported = false;

            int iPageNumber = 1;

            for (String strImage : lstImages) {

                String strProcessImageFolder = proc.getConfiguredImageFolder("media");
                if (ignoreFileExtension) {
                    int index = strImage.lastIndexOf(".");
                    if (index > 0) {
                        strImage = strImage.substring(0, index);
                    }
                }
                DocStruct page = getAndSavePage(strImage, strProcessImageFolder, dd, iPageNumber);

                if (page != null) {
                    physical.addChild(page);
                    logical.addReferenceTo(page, "logical_physical");
                    boImagesImported = true;

                    iPageNumber++;
                } else {
                    log.error("could not find image " + strImage + " for process " + proc.getTitel());
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "could not find image " + strImage, " - ");
                    successfull = false;
                }
            }

            //and save the metadata again.
            process.writeMetadataFile(fileformat);

            if (boImagesImported) {
                Helper.addMessageToProcessLog(process.getId(), LogType.INFO, "added images to " + proc.getTitel(), " - ");
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

        return PluginReturnValue.FINISH;
    }

    /**
     * Find the specified image file in the hashmap. If it is there, copy the file to a (new, if necessary) subfolder of the main folder, named after
     * the ID of the MetsMods file. Return a new DocStruct with the filename and the location of the file.
     */
    private DocStruct getAndSavePage(String strImage, String strProcessImageFolder, DigitalDocument dd, int iPageNumber)
            throws UGHException, IOException {

        List<Path> imagePaths = StorageProvider.getInstance().listFiles(folder, path -> {
            return path.getFileName().toString().matches(strImage + "\\..*");
        });

        if (imagePaths.isEmpty()) {
            return null;
        }
        //for now take first matching image
        File file = imagePaths.get(0).toFile();
        if (!file.exists()) {
            return null;
        }

        //create subfolder for images, as necessary:
        Path path = Paths.get(strProcessImageFolder);
        StorageProvider.getInstance().createDirectories(path);

        //copy original file:
        Path pathSource = Paths.get(file.getAbsolutePath());
        Path pathDest = Paths.get(strProcessImageFolder + file.getName().replace(" ", "_"));

        switch (this.mode) {
            case "move":
                StorageProvider.getInstance().move(pathSource, pathDest);
                break;
            case "copy":
            default:
                StorageProvider.getInstance().copyFile(pathSource, pathDest);
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

        return dsPage;
    }

}
