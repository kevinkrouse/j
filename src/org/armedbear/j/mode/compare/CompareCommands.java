/*
 * CompareCommands.java
 *
 * Copyright (C) 2026 Kevin Krouse
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

package org.armedbear.j.mode.compare;

import static org.armedbear.j.Constants.FILETYPE_BINARY;
import static org.armedbear.j.Constants.FILETYPE_GZIP;
import static org.armedbear.j.Constants.FILETYPE_JPEG;
import static org.armedbear.j.Constants.FILETYPE_ZIP;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Objects;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.FileCommands;
import org.armedbear.j.OpenFileDialog;
import org.armedbear.j.util.Utilities;

/** The commands of a comparison. */
public final class CompareCommands {
    private CompareCommands() {}

    /**
     * {@code diff [FILE]}: the buffer side by side with FILE, at the left,
     * following the buffer; with none, with its file's patchMode backup
     * if it has one, else with a file asked for.
     */
    public static void diff(Editor editor, String path) {
        final Buffer buffer = editor.getBuffer();
        if (buffer.getType() != Buffer.TYPE_NORMAL || buffer.getFileType() == FILETYPE_BINARY) {
            editor.status("Not a text file");
            return;
        }
        File other;
        if (path != null && !path.isBlank())
            other = FileCommands.fileNamed(editor, path.trim());
        else {
            final File file = buffer.getFile();
            other = file != null && file.isLocal() ? buffer.getPatchFile() : null;
            if (other == null || !other.isFile())
                other = OpenFileDialog.getLocalFile(editor, "Diff");
            if (other == null)
                return;
        }
        if (other == null || !other.isLocal() || !other.isFile()) {
            editor.status("No such file: " + (other != null ? other.netPath() : path.trim()));
            return;
        }
        final int type = Utilities.getFileType(other);
        if (type == FILETYPE_BINARY || type == FILETYPE_ZIP || type == FILETYPE_GZIP || type == FILETYPE_JPEG) {
            editor.status("Not a text file: " + other.canonicalPath());
            return;
        }
        final String title = buffer.getFile() != null ? buffer.getFile().canonicalPath() : buffer.getTitle();
        Following.open(
            editor,
            "diff|" + other.canonicalPath() + "|" + title,
            title + " (buffer)",
            new OtherFile(other, buffer.getFile()));
    }

    // Another file, read again once it changes on disk.
    private static final class OtherFile implements Following.Base {
        private final File file;
        // The buffer's file when compared.
        private final File bufferFile;
        // Its time and size when last read, and what was read in charset.
        private volatile long lastModified = -1;
        private volatile long length = -1;
        private Charset charset;
        private Following.Read read;

        OtherFile(File file, File bufferFile) {
            this.file = file;
            this.bufferFile = bufferFile;
        }

        @Override
        public Following.Read read(Charset charset) {
            final long lastModified = file.lastModified();
            final long length = file.length();
            if (read != null
                    && charset.equals(this.charset)
                    && lastModified == this.lastModified
                    && length == this.length)
                return read;
            this.lastModified = lastModified;
            this.length = length;
            this.charset = charset;
            if (!file.isFile())
                return read = new Following.Read(Side.NONE, file.canonicalPath() + " (not there)");
            try {
                return read = new Following.Read(Side.read(file, charset), file.canonicalPath());
            }
            catch (IOException e) {
                return read = null;
            }
        }

        @Override
        public boolean changed() {
            return file.lastModified() != lastModified || file.length() != length;
        }

        // Not once the buffer is saved as another file.
        @Override
        public boolean of(Buffer buffer) {
            return Objects.equals(buffer.getFile(), bufferFile);
        }

        @Override
        public String unreadable() {
            return "Couldn't read " + file.canonicalPath();
        }
    }

    /** q: closes the comparison, its windows from before back. */
    public static void compareQuit(Editor editor) {
        final CompareBuffer b = CompareBuffer.of(editor.getBuffer());
        // A part of another view, as the status's, is closed as that view closes it.
        if (b != null && b.getOwner() != null)
            b.kill();
        else if (b != null)
            b.close(editor.getFrame());
    }
}
