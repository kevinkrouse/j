/*
 * LogDiffBuffer.java
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

package org.armedbear.j.vcs.log;

import org.armedbear.j.Buffer;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.DiffPane;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/** The log's diff: the commit's change to the file picked in the files. */
final class LogDiffBuffer extends DiffPane<Buffer> {
    private final CommitDetails details;
    private LogCommit commit;
    private CommitChange change;

    LogDiffBuffer(Buffer owner, CommitDetails details) {
        super(VcsLogMode.getMode(), owner, "diff", "");
        this.details = details;
    }

    CommitDetails details() {
        return details;
    }

    LogCommit commit() {
        return commit;
    }

    CommitChange change() {
        return change;
    }

    /**
     * Reads the file's diff in the commit in the background, then shows it,
     * then runs then; null shows nothing.
     */
    void show(LogCommit c, CommitChange ch, Runnable then) {
        final int at = ++generation;
        if (ch == null) {
            commit = c;
            change = null;
            clear("");
            then.run();
            return;
        }
        final VcsRepository r = details.host().repositoryOf(c);
        final Whitespace ignored = DiffWhitespace.ignored();
        Background
                .then("vcs log diff", () -> r.backend().readChangeDiff(r, c.id(), c.firstParent(), ch, ignored), d -> {
                    if (at != generation || details.host().isDisposed())
                        return;
                    commit = c;
                    change = ch;
                    final FileItem f = LogFilesBuffer.item(ch);
                    // Keyed by the commit, so another commit's diff of the file starts afresh.
                    show(r, "diff|" + c.id(), null, new FileItem(f.change(), f.letter(), true, d), null);
                    then.run();
                });
    }
}
