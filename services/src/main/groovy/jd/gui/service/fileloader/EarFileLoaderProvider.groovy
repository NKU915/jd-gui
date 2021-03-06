/*
 * Copyright (c) 2008-2015 Emmanuel Dupuy
 * This program is made available under the terms of the GPLv3 License.
 */

package jd.gui.service.fileloader

import jd.gui.api.API

class EarFileLoaderProvider extends ZipFileLoaderProvider {

    String[] getExtensions() { ['ear'] }
    String getDescription() { 'Ear files (*.ear)' }

    boolean accept(API api, File file) {
        return file.exists() && file.canRead() && file.name.toLowerCase().endsWith('.ear')
    }
}
