/*
 * ImageLoader.java
 *
 * Copyright (C) 2000-2002 Peter Graves
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class ImageLoader {
    private File file;
    private MediaTracker mt;
    private Image image;

    public ImageLoader(File file) {
        this.file = file;
    }

    public Image loadImage() {
        final Editor editor = Editor.currentEditor();
        editor.setWaitCursor();
        try {
            image = Toolkit.getDefaultToolkit().createImage(file.canonicalPath());
            mt = new MediaTracker(editor);
            try {
                mt.addImage(image, 0);
                mt.waitForID(0);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (mt.isErrorAny()) {
                // Toolkit reads GIF, JPEG and PNG; ImageIO adds BMP and TIFF.
                dispose();
                try {
                    image = ImageIO.read(new java.io.File(file.canonicalPath()));
                }
                catch (IOException | RuntimeException e) {
                    // Malformed files make the readers throw more than IOException.
                    Log.error(e);
                }
            }
            return image;
        }
        finally {
            editor.setDefaultCursor();
        }
    }

    public void dispose() {
        if (image != null) {
            if (mt != null)
                mt.removeImage(image);
            image.flush();
            image = null;
            mt = null;
        }
    }
}
