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
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EncodingDetector;
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
            try (InputStream in = file.getInputStream()) {
                return read = new Following.Read(side(in.readAllBytes(), charset), file.canonicalPath());
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

    /**
     * A file's text, as j reads it: past a byte order mark; UTF-16 if that
     * says so; else UTF-8 if its bytes are, else in charset, the buffer's,
     * unless that is UTF-8 and they are not.
     */
    static Side side(byte[] bytes, Charset charset) {
        final int n = bytes.length;
        if (n >= 2
                && (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff
                        || bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)) {
            final String text = new String(bytes, StandardCharsets.UTF_16);
            return Side.of(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        final boolean bom = n >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
        final byte[] text = bom ? Arrays.copyOfRange(bytes, 3, n) : bytes;
        final String found = EncodingDetector.classify(text, 0, text.length, false);
        if (EncodingDetector.UTF_8.equals(found) || bom)
            return Side.of(text, StandardCharsets.UTF_8);
        if (found != null && charset.equals(StandardCharsets.UTF_8))
            return Side.of(text, Charset.forName(found));
        return Side.of(text, charset);
    }

    /** q: closes the comparison, its windows from before back. */
    public static void compareQuit(Editor editor) {
        final CompareBuffer b = CompareBuffer.of(editor.getBuffer());
        if (b != null)
            b.close(editor.getFrame());
    }
}
