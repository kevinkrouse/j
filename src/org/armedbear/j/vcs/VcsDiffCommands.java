/*
 * VcsDiffCommands.java
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

package org.armedbear.j.vcs;

import static org.armedbear.j.Constants.FILETYPE_BINARY;

import java.nio.charset.Charset;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.compare.Following;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.vcs.signs.SignsUpdater;

/** Comparing a file with what is staged of it. */
public final class VcsDiffCommands {
    private VcsDiffCommands() {}

    /**
     * The file being edited side by side with what is staged of it, as the
     * buffer has it, saved or not: in its encoding and its mode. A file not
     * in the index, new or in a conflict, is all added. It follows the
     * buffer: an edit, a reload or a change to what is staged shows soon
     * after.
     */
    public static void vcsDiff(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        final File file = buffer.getFile();
        if (buffer.getType() != Buffer.TYPE_NORMAL || file == null || buffer.getFileType() == FILETYPE_BINARY) {
            editor.status("Not a text file");
            return;
        }
        final VcsRepository r = file.isRemote() ? null : Repositories.forFile(file);
        if (r == null) {
            editor.status("Not a file in a repository");
            return;
        }
        final String path = r.relativePath(file);
        Following.open(editor, r.root() + "|" + path, path + " (buffer)", staged(buffer, r, path));
    }

    /**
     * What is staged of path in r, as buffer, a buffer of it, is compared
     * with: read through its change marks' bases if it has them, so once.
     */
    public static Following.Base staged(Buffer buffer, VcsRepository r, String path) {
        final SignsUpdater u = buffer.getSignsUpdater();
        if (u != null && u.getRepository() == r && u.getPath().equals(path))
            return new Staged(u.getBases(), buffer);
        return new Staged(new BasesCache(r, path, null), buffer);
    }

    // What is staged of a file, read again once the repository changes:
    // by the buffer's change marks while they read these bases, else itself.
    private record Staged(BasesCache bases, Buffer buffer) implements Following.Base {
        @Override
        public Following.Read read(Charset charset) {
            final Bases read = bases.get();
            if (read == null)
                return null;
            final String path = bases.getPath();
            return read.index() == null
                    ? new Following.Read(Side.NONE, path + " (not in the index)")
                    : new Following.Read(Side.of(read.index(), charset), path + " (staged)");
        }

        // Still of the file compared, not saved as another.
        @Override
        public boolean of(Buffer buffer) {
            final File file = buffer.getFile();
            final VcsRepository r = bases.getRepository();
            return file != null && Repositories.forFile(file) == r && bases.getPath().equals(r.relativePath(file));
        }

        @Override
        public String unreadable() {
            return "Couldn't read what is staged: see vcsCommandLog";
        }

        @Override
        public void vcsChecked() {
            final SignsUpdater u = buffer.getSignsUpdater();
            if (u == null || u.getBases() != bases)
                bases.invalidate();
        }
    }
}
