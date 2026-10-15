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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.armedbear.j.vcs.status.StatusRenderer;

/**
 * The diff of one file of the log's selected commit, against its first
 * parent, its hunks folding as the status buffer's do.
 */
final class LogDiffBuffer extends LogPane {
    private LogCommit commit;
    private CommitChange change;
    private FileDiff diff;
    // Hunks closed or opened by hand, by key; open by default.
    private final Map<String, Boolean> expanded = new HashMap<>();

    LogDiffBuffer(LogBuffer owner) {
        super(owner, "diff");
    }

    LogCommit commit() {
        return commit;
    }

    CommitChange change() {
        return change;
    }

    /** Reads the file's diff in the commit in the background, then shows it; null shows nothing. */
    void show(LogCommit c, CommitChange ch) {
        final int at = ++generation;
        if (ch == null) {
            commit = c;
            change = null;
            diff = null;
            replace(List.of(), null);
            return;
        }
        final VcsRepository r = owner.getRepository();
        Background.start("vcs log diff", () -> {
            FileDiff read;
            try {
                read = r.backend().readChangeDiff(r, c.id(), c.firstParent(), ch);
            }
            catch (RuntimeException e) {
                Log.error(e);
                read = null;
            }
            final FileDiff d = read;
            SwingUtilities.invokeLater(() -> {
                if (at != generation || owner.isDisposed())
                    return;
                commit = c;
                change = ch;
                diff = d;
                expanded.clear();
                render(null, false);
            });
        });
    }

    /**
     * Lays the lines out again, the caret on the line keyed at, or the first
     * change. Kept, each window's caret and top stay on their lines, or on
     * their hunks when those close.
     */
    private void render(String at, boolean kept) {
        if (change == null)
            return;
        final Map<Editor, StatusLine[]> was = new HashMap<>();
        if (kept)
            for (Editor ed : windows())
                if (ed.getDot() != null
                        && ed.getDotLine() instanceof StatusLine d
                        && ed.getTopLine() instanceof StatusLine t)
                    was.put(ed, new StatusLine[] { d, t });
        final FileItem f = LogFilesBuffer.item(change);
        final FileItem item = new FileItem(f.change(), f.letter(), true, diff);
        final List<StatusLine> lines = StatusRenderer.renderFiles(
            owner.getRepository(),
            "diff",
            List.of(item),
            (key, kind) -> kind == Kind.FILE || expanded.getOrDefault(key, true));
        Line first = null;
        for (StatusLine l : lines)
            if (at != null ? l.key().equals(at) : l.kind() == Kind.DIFF && !l.diffLine().isContext()) {
                first = l;
                break;
            }
        fill(lines);
        for (Editor ed : windows()) {
            final StatusLine[] w = was.get(ed);
            final Line dot = at == null && w != null ? same(lines, w[0]) : first;
            final Line top = w != null ? same(lines, w[1]) : null;
            place(ed, dot != null ? dot : getFirstLine(), top != null ? top : getFirstLine());
        }
    }

    // The line keyed as l was, or its hunk's line; null if neither is there.
    private static Line same(List<StatusLine> lines, StatusLine l) {
        for (StatusLine s : lines)
            if (s.key().equals(l.key()))
                return s;
        for (StatusLine s : lines)
            if (s.key().equals(l.node()))
                return s;
        return null;
    }

    /** Opens or closes the hunk line is in or heads. False if there is none. */
    boolean toggle(StatusLine line) {
        final String hunk = line.kind() == Kind.HUNK ? line.key() : line.kind() == Kind.DIFF ? line.node() : null;
        if (hunk == null)
            return false;
        setExpanded(hunk, !expanded.getOrDefault(hunk, true));
        return true;
    }

    /** Opens or closes every hunk. */
    void setAll(boolean open) {
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.HUNK)
                expanded.put(s.key(), open);
        render(null, true);
    }

    void setExpanded(String hunk, boolean open) {
        expanded.put(hunk, open);
        render(hunk, true);
    }
}
