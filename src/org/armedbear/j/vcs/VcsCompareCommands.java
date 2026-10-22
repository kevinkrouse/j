/*
 * VcsCompareCommands.java
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
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.signs.EditorHunks.Side;
import org.armedbear.j.vcs.signs.SignsUpdater;

/** Comparing a file with what is staged of it. */
public final class VcsCompareCommands {
    private VcsCompareCommands() {}

    /**
     * The file being edited side by side with what is staged of it, as the
     * buffer has it, saved or not: in its encoding and its mode. A file not
     * in the index, new or in a conflict, is all added.
     */
    public static void vcsCompare(Editor editor) {
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
        final Charset charset = SignsUpdater.charset(buffer);
        final List<String> lines = lines(buffer);
        Background.start("vcs diff side by side", () -> {
            Bases bases;
            try {
                bases = r.backend().readBases(r, path);
            }
            catch (RuntimeException e) {
                Log.error(e);
                bases = null;
            }
            final Bases read = bases;
            SwingUtilities.invokeLater(() -> {
                // Not if the window has moved on to something else meanwhile.
                if (!Editor.getEditorList().contains(editor) || editor.getBuffer() != buffer)
                    return;
                if (read == null) {
                    editor.status("Couldn't read what is staged: see vcsCommandLog");
                    return;
                }
                final Side staged = read.index() == null ? null : Side.of(read.index(), charset);
                final Side now = Side.ofBuffer(lines, charset, staged != null && staged.crlf());
                CompareBuffer.open(
                    editor.getFrame(),
                    buffer.getMode(),
                    r.root() + "|" + path,
                    path + (staged == null ? " (not in the index)" : " (staged)"),
                    staged == null ? new CompareBuffer.Text(List.of(), List.of()) : text(staged),
                    path + " (buffer)",
                    text(now));
            });
        });
    }

    private static CompareBuffer.Text text(Side side) {
        return new CompareBuffer.Text(side.lines(), side.keys());
    }

    // The buffer's lines, as it has them now.
    private static List<String> lines(Buffer buffer) {
        final List<String> out = new ArrayList<>();
        for (Line l = buffer.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }
}
