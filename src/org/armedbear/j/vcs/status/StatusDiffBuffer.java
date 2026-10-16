/*
 * StatusDiffBuffer.java
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

package org.armedbear.j.vcs.status;

import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/** The status's diff beside it: the file at its caret, at the hunk there. */
final class StatusDiffBuffer extends DiffPane<VcsStatusBuffer> {
    /** Why it follows the status's caret again. */
    enum Follow {
        // The status drew its lines again: shown again from them, in place.
        REDRAWN,
        // Its repositories were read again: the diff read again too, in place.
        REFRESHED,
        // The caret is on another file or hunk: shown at it, read if need be.
        MOVED
    }

    StatusDiffBuffer(VcsStatusBuffer owner) {
        super(VcsStatusMode.getMode(), owner, "diff", "No file at the caret");
    }

    /**
     * What following line shows: its file and hunk, as keys; null for a
     * line of no file, which leaves the diff as it is.
     */
    static String target(StatusLine line) {
        if (line.file() == null || line.repository() == null)
            return null;
        return key(line) + "|" + line.file().path() + "#" + hunkOf(line);
    }

    // Its file's key without the path: the status's section's.
    private static String key(StatusLine line) {
        return line.repository().root() + "|" + line.section();
    }

    /**
     * Shows the diff of line's file, at line's hunk when the caret has
     * moved there, else where its windows are. A diff the status hasn't
     * read, as of a file closed there, is read for it, but only when the
     * caret moves to it or the status is refreshed.
     */
    void follow(StatusLine line, Follow why) {
        final FileItem f = line.file();
        if (f == null || line.repository() == null)
            return;
        final VcsRepository r = line.repository();
        final String at = why == Follow.MOVED ? hunkOf(line) : null;
        if (!f.hasDiff() || f.diff() != null) {
            ++generation;
            show(r, key(line), f, at);
            return;
        }
        if (why == Follow.REDRAWN)
            return;
        final int read = ++generation;
        Background.start("vcs status diff", () -> {
            List<FileDiff> diffs;
            try {
                diffs = r.backend().readDiff(r, f.staged(), List.of(f.path()));
            }
            catch (RuntimeException e) {
                Log.error(e);
                diffs = List.of();
            }
            final FileDiff d = diffs.isEmpty() ? FileDiff.EMPTY : diffs.get(0);
            SwingUtilities.invokeLater(() -> {
                if (read == generation && Editor.getBufferList().contains(this))
                    show(r, key(line), new FileItem(f.change(), f.letter(), f.staged(), d), at);
            });
        });
    }
}
