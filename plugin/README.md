---
description: >-
  This is technical documentation for the plugin for reading file names from metadata, and adding the corresponding files to the process.
---

# Plugin for fetching images using metadata

## Introduction

This documentation describes the installation, configuration and use of the plugin.

| Details |  |
| :--- | :--- |
| Identifier | plugin_intranda_step_fetch_images_from_metadata |
| Source code | [https://github.com/intranda/plugin_intranda_step_fetch_images_from_metadata](https://github.com/intranda/plugin_intranda_step_fetch_images_from_metadata) |
| Licence | GPL 2.0 or newer |
| Compatibility | Goobi workflow 2021.03 |
| Documentation date | 10.04.2021 |

### Installation

The program consists of these files:

```
plugin_intranda_step_fetch_images_from_metadata.jar
plugin_intranda_step_fetch_images_from_metadata.xml
```

The file `plugin_intranda_step_fetch_images_from_metadata.jar` contains the program logic, and should be copied to this path: `/opt/digiverso/goobi/plugins/step`.

The file `plugin_intranda_step_fetch_images_from_metadata.xml` is the config file, and should be copied to the folder `/opt/digiverso/goobi/config/`.


## Configuration

The configuration is done via the configuration file `plugin_intranda_step_fetch_images_from_metadata.xml` and can be adapted during operation. It is structured as follows:

```xml
<config_plugin>

    <config>
        <!-- which projects to use for (can be more then one, otherwise use *) -->
        <project>*</project>
        <step>*</step>

        <!-- metadata containing the file name -->
        <filenameMetadata>SeparatedMaterial</filenameMetadata>

        <!-- path to images -->     
        <imagesFolder>/opt/digiverso/import/images/</imagesFolder>

          <!-- image file type -->     
        <imageType>jpg</imageType>

    </config>

</config_plugin>
```

| Value  |  Description |
|---|---|
|   `filenameMetadata` |This gives the name of the metadata element which contains the filenames.   |   
|  `imagesFolder` |This gives path to the folder containing the images. |   
|  `imageType` | This gives the image type.  |



### Operation of the plugin

For the current goobi process, all metadata of the type specified under `filenameMetadata` are read, and given the ending specified by `imageType`. These are interpreted as being the file names of images which are to be found in `imagesFolder`. If the images are found, then they are imported into the goobi process, with increasing pysical page numbers (logical page numbers are given by the file names themselves).
