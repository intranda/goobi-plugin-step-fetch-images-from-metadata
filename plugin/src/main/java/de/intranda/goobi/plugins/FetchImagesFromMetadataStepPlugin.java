package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final long serialVersionUID = 1L;

    @Getter
    private String title = "intranda_step_fetch_images_from_metadata";
    @Getter
    private Step step;

    private String returnPath;
    private Process process;
    private Prefs prefs;

    // name of the Metadata in the METS file that is used to hold the names of the to-be-imported images
    private String imageMetadata;
    // the source folder for the import
    private String folder;
    // copy | move
    private String mode;
    // true if file extension is to be ignored, false otherwise
    private boolean ignoreFileExtension;
    // true if errors happened while copying files should be ignored, false otherwise
    private boolean ignoreCopyErrors;
    // true if the process should be exported by the end of import, false otherwise
    private boolean startExport;
    // true if the images should be exported by the export plugin, false otherwise
    private boolean exportImages;
    // true if any images are imported by this run, false otherwise
    private boolean imagesImported = false;

    private transient StorageProviderInterface storageProvider;

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

        log.info("FetchImagesFromMetadata step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        // won't be used
        return "/uii/plugin_step_fetch_images_from_metadata.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        // won't be used
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        // won't be used
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
        boolean successful = true;

        try {
            Fileformat fileformat = process.readMetadataFile();
            DigitalDocument dd = prepareDigitalDocument(fileformat);

            List<String> lstImages = MetadataManager.getAllMetadataValues(process.getId(), imageMetadata);
            //            Collections.sort(lstImages); // this line will reorder the images according to their names
            log.debug("lstImages has size = " + lstImages.size());

            int iPageNumber = 1;
            String strProcessImageFolder = process.getConfiguredImageFolder("media");
            // get a set of filenames in target directory
            Set<String> existingImages = new HashSet<String>(storageProvider.list(strProcessImageFolder));

            for (String strImage : lstImages) {
                // strImage all have file extensions
                log.debug("strImage = " + strImage);
                // process the image page named strImage
                boolean processResult = processImagePageByName(strImage, strProcessImageFolder, dd, iPageNumber, existingImages);
                if (processResult) {
                    iPageNumber++;
                }
                // processResult only counts when ignoreCopyErrors is set false
                successful = successful && (ignoreCopyErrors || processResult);
            }

            // save the metadata
            process.writeMetadataFile(fileformat);

        } catch (IOException | SwapException | DAOException | UGHException e) {
            log.error(e);
            successful = false;
        }

        if (!successful) {
            return PluginReturnValue.ERROR;
        }

        if (imagesImported) {
            String message = "Images imported for process " + process.getTitel();
            logBoth(process.getId(), LogType.INFO, message);
        }

        if (startExport && process != null) {
            exportProcess(process, exportImages);
        }

        log.info("FetchImagesFromMetadata step plugin executed");

        return PluginReturnValue.FINISH;
    }

    /**
     * prepare the DigitalDocument object that is to be used
     * 
     * @param fileformat Fileformat
     * @return the prepared DigitalDocument object
     * @throws UGHException
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
    private DigitalDocument prepareDigitalDocument(Fileformat fileformat) throws UGHException, IOException, SwapException, DAOException {
        DigitalDocument dd = fileformat.getDigitalDocument();
        DocStruct physical = dd.getPhysicalDocStruct();

        if (findExistingMetadata(physical, "pathimagefiles") == null) {
            Metadata imagePath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
            imagePath.setValue(process.getConfiguredImageFolder("media"));
            physical.addMetadata(imagePath);
        }

        return dd;
    }

    /**
     * get the value of an existing Metadata
     * 
     * @param ds DocStruct whose Metadata should be searched
     * @param elementType name of MetadataType
     * @return value of the Metadata if successfully found, null otherwise
     */
    private String findExistingMetadata(DocStruct ds, String elementType) {
        if (ds.getAllMetadata() != null) {
            for (Metadata md : ds.getAllMetadata()) {
                if (md.getType().getName().equals(elementType)) {
                    return md.getValue();
                }
            }
        }
        return null;
    }

    /**
     * process the image page by its name
     * 
     * @param strImage name of the image
     * @param strProcessImageFolder media folder of the process
     * @param dd DigitalDocument
     * @param iPageNumber physical order of this page
     * @param existingImages list of images that were already imported before this run
     * @return true if the image is successfully retrieved or created, false otherwise
     * @throws UGHException
     * @throws IOException
     */
    private boolean processImagePageByName(String strImage, String strProcessImageFolder, DigitalDocument dd, int iPageNumber,
            final Set<String> existingImages) throws UGHException, IOException {
        // get the page by its name strImage
        DocStruct page = getResultPageByImageName(strImage, strProcessImageFolder, dd, iPageNumber, existingImages);

        if (page == null) {
            String message = "Could not find image " + strImage + " for process " + process.getTitel();
            LogType logType = ignoreCopyErrors ? LogType.INFO : LogType.ERROR;
            logBoth(process.getId(), logType, message);
            return false;
        }

        DocStruct physical = dd.getPhysicalDocStruct();
        DocStruct logical = dd.getLogicalDocStruct();
        // remove old infos
        logical.removeReferenceTo(page); // no need to remove the child
        // add new infos
        physical.addChild(page); // there won't be any duplicates if page was already added as a child  
        logical.addReferenceTo(page, "logical_physical");

        return true;
    }

    /**
     * get the image page by its name
     * 
     * @param strImage name of the image
     * @param strProcessImageFolder media folder of the process
     * @param dd DigitalDocument
     * @param iPageNumber physical order of this page
     * @param existingImages list of images that were already imported before this run
     * @return the page as a DocStruct object
     * @throws UGHException
     * @throws IOException
     */
    private DocStruct getResultPageByImageName(String strImage, String strProcessImageFolder, DigitalDocument dd, int iPageNumber,
            final Set<String> existingImages) throws UGHException, IOException {
        // check if the image was already imported
        boolean imageExisting = existingImages.contains(strImage.replace(" ", "_"));
        if (imageExisting) {
            String message = "A file with the Name: " + strImage + " already exists for this process.";
            logBoth(process.getId(), LogType.DEBUG, message);
        }

        // remove file extension if configured so
        if (ignoreFileExtension) {
            int index = strImage.lastIndexOf(".");
            if (index > 0) {
                strImage = strImage.substring(0, index);
            }
        }

        // retrieve the existing page OR if it has not been imported yet, get and save it
        return imageExisting ? getExistingPage(strImage, dd, iPageNumber) : getAndSavePage(strImage, strProcessImageFolder, dd, iPageNumber);
    }

    /**
     * get the existing image page by its name
     * 
     * @param strImage name of the image
     * @param dd DigitalDocument
     * @param iPageNumber physical order of this page
     * @return the existing page as a DocStruct object
     */
    private DocStruct getExistingPage(String strImage, DigitalDocument dd, int iPageNumber) {
        log.debug("getting existing image page");
        strImage = strImage.replace(" ", "_");
        String regex = getRegularExpression(strImage);
        List<DocStruct> pages = dd.getAllDocStructsByType("page");
        for (DocStruct page : pages) {
            String imageName = page.getImageName();
            log.debug("imageName = " + imageName);

            if (imageName.matches(regex)) {
                log.debug("imageName = " + imageName);
                MetadataType typePhysPage = prefs.getMetadataTypeByName("physPageNumber");
                Metadata mdPhysPage = page.getAllMetadataByType(typePhysPage).get(0);
                mdPhysPage.setValue(String.valueOf(iPageNumber));
                return page;
            }
        }

        String message = "Unable to retrieve the existing page named: " + strImage;
        logBoth(process.getId(), LogType.ERROR, message);
        return null;
    }

    /**
     * get and save the specified image file from the import folder if it is there
     * 
     * @param strImage name of the image
     * @param strProcessImageFolder media folder of the process
     * @param dd DigitalDocument
     * @param iPageNumber physical order of the page
     * @return the new page as a DocStruct object if it is successfully imported, otherwise null
     * @throws UGHException
     * @throws IOException
     */
    private DocStruct getAndSavePage(String strImage, String strProcessImageFolder, DigitalDocument dd, int iPageNumber)
            throws UGHException, IOException {
        log.debug("getting and saving new page");
        // get matched image file
        File file = getMatchedImageFile(strImage, this.folder);
        if (file == null) {
            // no file found in the import folder
            String message = "There was no file with the name: " + strImage + " in the images folder.";
            logBoth(process.getId(), LogType.DEBUG, message);
            return null;
        }
        if (!file.exists()) {
            String message = "There was an error processing the file: " + strImage + " in the images folder.";
            logBoth(process.getId(), LogType.ERROR, message);
            return null;
        }

        // save the image file
        File fileCopy = saveImageFile(strImage, strProcessImageFolder, file);

        // create the page's DocStruct
        DocStruct dsPage = createDocStructPage(fileCopy, strImage, dd, iPageNumber);

        // set the flag
        imagesImported = true;

        return dsPage;
    }

    /**
     * get the image file from the import folder that matches the input name
     * 
     * @param strImage name of the image
     * @param folder path of the import folder
     * @return the image file as a File object
     */
    private File getMatchedImageFile(String strImage, String folder) {
        String regex = getRegularExpression(strImage);
        List<Path> imagePaths = storageProvider.listFiles(folder, path -> {
            return path.getFileName().toString().matches(regex);
        });

        // take the first match if there is any
        return imagePaths.isEmpty() ? null : imagePaths.get(0).toFile();
    }

    /**
     * import the matched image file to the media folder
     * 
     * @param strImage name of the image
     * @param strProcessImageFolder media folder of the process
     * @param file the matched image file
     * @return the copied file as a File object
     * @throws IOException
     */
    private File saveImageFile(String strImage, String strProcessImageFolder, File file) throws IOException {
        // create subfolder for images, as necessary:
        Path path = Paths.get(strProcessImageFolder);
        storageProvider.createDirectories(path);

        // copy or move original file:
        Path pathSource = Paths.get(file.getAbsolutePath());
        // replace spaces with "_"
        String fileName = file.getName().replace(" ", "_");
        Path pathDest = Paths.get(strProcessImageFolder, fileName);

        switch (this.mode) {
            case "move":
                storageProvider.move(pathSource, pathDest);
                Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG, "Image moved into process folder: " + strImage);
                break;
            case "copy":
            default:
                storageProvider.copyFile(pathSource, pathDest);
                Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG, "Image copied into process folder: " + strImage);
        }

        return new File(pathDest.toString());
    }

    /**
     * create a DocStruct object for the image page
     * 
     * @param fileCopy the copy of the imported image file
     * @param strImage name of the image
     * @param dd DigitalDocument
     * @param iPageNumber physical order of the image page
     * @return the page as a DocStruct object
     * @throws UGHException
     * @throws IOException
     */
    private DocStruct createDocStructPage(File fileCopy, String strImage, DigitalDocument dd, int iPageNumber) throws UGHException, IOException {
        DocStructType pageType = prefs.getDocStrctTypeByName("page");
        DocStruct dsPage = dd.createDocStruct(pageType);

        // physical page number : just increment for this folio
        MetadataType typePhysPage = prefs.getMetadataTypeByName("physPageNumber");
        Metadata mdPhysPage = new Metadata(typePhysPage);
        mdPhysPage.setValue(String.valueOf(iPageNumber));
        dsPage.addMetadata(mdPhysPage);

        // logical page number : take the file name
        MetadataType typeLogPage = prefs.getMetadataTypeByName("logicalPageNumber");
        Metadata mdLogPage = new Metadata(typeLogPage);
        mdLogPage.setValue(strImage);
        dsPage.addMetadata(mdLogPage);

        // content file
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

    /**
     * get the proper regular expression
     * 
     * @param strImage name of the image
     * @return the proper regular expression as a string
     */
    private String getRegularExpression(String strImage) {
        String base = "\\Q" + strImage + "\\E";

        return base + (ignoreFileExtension ? "\\..*" : "");
    }

    /**
     * Do the export of the process
     * 
     * @param p Goobi process
     * @param exportImage true if the images should be exported, false otherwise
     */
    private void exportProcess(Process p, boolean exportImage) {
        try {
            IExportPlugin export = getExportPluginOfProcess(p);

            if (export == null) {
                export = new ExportDms();
            }
            export.setExportFulltext(false);
            export.setExportImages(exportImage);
            export.startExport(p);

            String message = "Export finished inside of catalogue poller for process with ID " + p.getId();
            logBoth(p.getId(), LogType.DEBUG, message);

        } catch (NoSuchMethodError | Exception e) {
            log.error("Exception during the export of process " + p.getId(), e);
        }
    }
    
    /**
     * get the export plugin for this process
     * 
     * @param p Goobi process
     * @return the export plugin for this process if there is any found for this process, otherwise null
     */
    private IExportPlugin getExportPluginOfProcess(Process p) {
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

        return export;
    }

    /**
     * print logs to terminal and journal
     * 
     * @param processId id of the Goobi process
     * @param logType type of the log
     * @param message message to be shown to both terminal and journal
     */
    private void logBoth(int processId, LogType logType, String message) {
        String logMessage = "FetchImagesFromMetadata Step Plugin: " + message;
        switch (logType) {
            case ERROR:
                log.error(logMessage);
                break;
            case DEBUG:
                log.debug(logMessage);
                break;
            case WARN:
                log.warn(logMessage);
                break;
            default: // INFO
                log.info(logMessage);
                break;
        }
        if (processId > 0) {
            Helper.addMessageToProcessJournal(processId, logType, logMessage);
        }
    }

}
