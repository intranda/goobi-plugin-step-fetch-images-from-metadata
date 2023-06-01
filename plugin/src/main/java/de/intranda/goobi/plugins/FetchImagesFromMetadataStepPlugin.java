package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
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

    private boolean useUrl;

    private String imageExtension = ".jpg";

    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        this.process = step.getProzess();
        this.prefs = process.getRegelsatz().getPreferences();

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        this.useUrl = myconfig.getBoolean("useUrl", false);
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

            String processImageFolder = process.getConfiguredImageFolder("media");
            // get a set of filenames in target directory
            Set<String> existingImages = new HashSet<>(storageProvider.list(processImageFolder));

            // process images by their names or by their urls
            successful = processImages(processImageFolder, dd, existingImages);

            // save the metadata
            process.writeMetadataFile(fileformat);

        } catch (IOException | SwapException | DAOException | UGHException e) {
            log.error(e);
            successful = false;
        }

        if (imagesImported) {
            String message = "Images imported for process " + process.getTitel();
            logBoth(process.getId(), LogType.INFO, message);
        }

        if (startExport && process != null) {
            // export the process only if everything has been fine so far
            successful = successful && exportProcess(process, exportImages);
        }

        log.info("FetchImagesFromMetadata step plugin executed");

        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
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

    private String getImageNameFromUrl(URL url) {
        String urlFileName = url.getFile();
        log.debug("urlFileName = " + urlFileName);

        //        String imageName = urlFileName.replaceAll("\\W", "_") + (ignoreFileExtension ? "" : imageExtension);
        String imageName = urlFileName.replaceAll("\\W", "_") + imageExtension;
        log.debug("imageName = " + imageName);

        return imageName;
    }

    private String getImageNameFromUrl(String strUrl) {
        try {
            URL url = new URL(strUrl);
            return getImageNameFromUrl(url);

        } catch (MalformedURLException e) {
            String message = "Malformed URL";
            logBoth(process.getId(), LogType.DEBUG, message);
            return strUrl;
        }
    }

    private boolean processImages(String processImageFolder, DigitalDocument dd, Set<String> existingImages) throws UGHException, IOException {
        boolean successful = true;

        List<String> lstImages = getImageNamesList(dd);
        int iPageNumber = 1;

        for (String strImage : lstImages) {
            // strImage all have file extensions
            log.debug("strImage = " + strImage);
            // process the image page named strImage
            boolean processResult = processImagePageByName(strImage, processImageFolder, dd, iPageNumber, existingImages);
            if (processResult) {
                iPageNumber++;
            }
            // processResult only counts when ignoreCopyErrors is set false
            successful = successful && (ignoreCopyErrors || processResult);
        }

        return successful;
    }

    /**
     * get a sorted list of image names from the Mets file
     * 
     * @param dd DigitalDocument
     * @return the sorted list of image names from the Mets file
     */
    private List<String> getImageNamesList(DigitalDocument dd) {
        List<String> lstImages = MetadataManager.getAllMetadataValues(process.getId(), imageMetadata);
        log.debug("lstImages has size = " + lstImages.size());

        if (useUrl) {
            // no need to order the items
            return lstImages;
        }

        DocStruct logical = dd.getLogicalDocStruct();

        String inventoryNumber = findExistingMetadata(logical, "InventoryNumber");
        log.debug("inventoryNumber = " + inventoryNumber);

        String serialNumber = findExistingMetadata(logical, "SerialNumber");
        log.debug("serialNumber = " + serialNumber);

        String firstImageName = inventoryNumber + " Nr_" + serialNumber;
        log.debug("firstPageName = " + firstImageName);

        // locate the candidate name for the first image
        int index = getIndexOfImage(lstImages, firstImageName);
        if (index < 0) {
            index = getIndexOfImage(lstImages, firstImageName.replace(" ", "_"));
        }
        log.debug("index = " + index);

        String firstImage = null;
        if (index >= 0) {
            // prepare to change position of the found image
            firstImage = lstImages.remove(index);
        } else {
            // no match found, proceed as usual
            log.debug("The proposed pattern did not show up this time. Proceed using default settings.");
        }

        // sort the images according to their names
        Collections.sort(lstImages);

        // put the found image to No.1
        if (StringUtils.isNotBlank(firstImage)) {
            lstImages.add(0, firstImage);
        }

        return lstImages;
    }

    /**
     * get the index of the input imageName among the input list of names
     * 
     * @param lstImages list of image names
     * @param imageName the image name that should be located in the list
     * @return the index of imageName in lstImages
     */
    private int getIndexOfImage(List<String> lstImages, String imageName) {
        for (int i = 0; i < lstImages.size(); ++i) {
            String image = lstImages.get(i);
            if (image.startsWith(imageName)) {
                return i;
            }
        }
        
        return -1;
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
        //        boolean imageExisting = existingImages.contains(strImage.replace(" ", "_"));
        boolean imageExisting = checkExistenceOfImage(strImage, existingImages);
        if (imageExisting) {
            String message = "A file with the Name: " + getImageNameFromString(strImage) + " already exists for this process.";
            logBoth(process.getId(), LogType.DEBUG, message);
        }

        //        // remove file extension if configured so
        //        if (!useUrl && ignoreFileExtension) {
        //            int index = strImage.lastIndexOf(".");
        //            if (index > 0) {
        //                strImage = strImage.substring(0, index);
        //            }
        //        }
        String imageName = parseStrImage(strImage);
        //        strImage = parseStrImage(strImage);

        // retrieve the existing page OR if it has not been imported yet, get and save it
        return imageExisting ? getExistingPage(imageName, dd, iPageNumber) : getAndSavePage(strImage, strProcessImageFolder, dd, iPageNumber);
    }

    private DocStruct getExistingPage(String strImage, DigitalDocument dd, int iPageNumber) {
        //        String imageName = useUrl ? getImageNameFromUrl(strImage) : strImage;

        return getExistingPageByName(strImage, dd, iPageNumber);
        //        return useUrl ? getExistingPageFromUrl(strImage, dd, iPageNumber) : getExistingPageFromFolder(strImage, dd, iPageNumber);
    }

    private boolean checkExistenceOfImage(String strImage, final Set<String> existingImages) {
        String imageName = getImageNameFromString(strImage);

        return existingImages.contains(imageName.replace(" ", "_"));
    }

    private String getImageNameFromString(String strImage) {
        return useUrl ? getImageNameFromUrl(strImage) : strImage;
    }

    private String parseStrImage(String strImage) {
        String imageName = useUrl ? getImageNameFromUrl(strImage) : strImage;
        //        if (useUrl) {
        //            return getImageNameFromUrl(strImage);
        //        }

        if (!ignoreFileExtension) {
            return imageName;
        }

        // file extension should be ignored
        int index = imageName.lastIndexOf(".");
        return index > 0 ? imageName.substring(0, index) : imageName;
    }

    /**
     * get the existing image page by its name
     * 
     * @param strImage name of the image
     * @param dd DigitalDocument
     * @param iPageNumber physical order of this page
     * @return the existing page as a DocStruct object
     */
    private DocStruct getExistingPageByName(String strImage, DigitalDocument dd, int iPageNumber) {
        log.debug("getting existing image page: " + strImage);
        //        String targetImageName = useUrl ? getImageNameFromUrl(strImage) : strImage.replace(" ", "_");
        strImage = strImage.replace(" ", "_");
        String regex = getRegularExpression(strImage);
        log.debug("regex = " + regex);
        //        String regex = getRegularExpression(targetImageName);
        List<DocStruct> pages = dd.getAllDocStructsByType("page");
        for (DocStruct page : pages) {
            String imageName = page.getImageName();
            log.debug("imageName = " + imageName);
            if (imageName.matches(regex)) {
                // physical page number : update the number
                MetadataType typePhysPage = prefs.getMetadataTypeByName("physPageNumber");
                Metadata mdPhysPage = page.getAllMetadataByType(typePhysPage).get(0);
                mdPhysPage.setValue(String.valueOf(iPageNumber));

                // logical page number : update the file name
                MetadataType typeLogPage = prefs.getMetadataTypeByName("logicalPageNumber");
                Metadata mdLogPage = page.getAllMetadataByType(typeLogPage).get(0);
                mdLogPage.setValue(strImage);

                return page;
            }
        }

        String message = "Unable to retrieve the existing page named: " + strImage;
        logBoth(process.getId(), LogType.ERROR, message);
        return null;
    }

    private DocStruct getAndSavePage(String strImage, String processImageFolder, DigitalDocument dd, int iPageNumber)
            throws UGHException, IOException {
        return useUrl ? getAndSavePageFromUrl(strImage, processImageFolder, dd, iPageNumber)
                : getAndSavePageFromFolder(strImage, processImageFolder, dd, iPageNumber);
    }

    private DocStruct getAndSavePageFromUrl(String strImage, String processImageFolder, DigitalDocument dd, int iPageNumber)
            throws IOException, UGHException {
        // download the file from url
        File fileCopy = downloadImageFile(strImage, processImageFolder);

        String imageName = getImageNameFromUrl(strImage);

        // create the page's DocStruct
        DocStruct dsPage = createDocStructPage(fileCopy, imageName, dd, iPageNumber);

        // set the flag
        imagesImported = true;

        return dsPage;
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
    private DocStruct getAndSavePageFromFolder(String strImage, String strProcessImageFolder, DigitalDocument dd, int iPageNumber)
            throws UGHException, IOException {
        log.debug("getting and saving new page: " + strImage);
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
        List<Path> imagePaths = storageProvider.listFiles(folder, path -> path.getFileName().toString().matches(regex));

        // take the first match if there is any
        return imagePaths.isEmpty() ? null : imagePaths.get(0).toFile();
    }

    private File downloadImageFile(String strUrl, String processImageFolder) throws IOException {
        log.debug("downloading image from url: " + strUrl);
        URL url = new URL(strUrl);
        String imageName = getImageNameFromUrl(url);

        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());

        //            Path targetPath = Path.of(processImageFolder, String.valueOf(iPageNumber) + imageExtension);
        Path targetPath = Path.of(processImageFolder, imageName);

        FileOutputStream outputStream = new FileOutputStream(targetPath.toString());
        FileChannel fileChannel = outputStream.getChannel();

        fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);

        return new File(targetPath.toString());
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
        mdLogPage.setValue(strImage.replace(" ", "_"));
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
        String imageName = parseStrImage(strImage);
        String base = "\\Q" + imageName + "\\E";

        return base + (ignoreFileExtension ? "\\..*" : "");
    }

    /**
     * Do the export of the process
     * 
     * @param p Goobi process
     * @param exportImage true if the images should be exported, false otherwise
     * @return true if the process is successfully exported, false if any exceptions should happen
     */
    private boolean exportProcess(Process p, boolean exportImage) {
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
            return true;

        } catch (NoSuchMethodError | Exception e) {
            log.error("Exception during the export of process " + p.getId(), e);
            return false;
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
