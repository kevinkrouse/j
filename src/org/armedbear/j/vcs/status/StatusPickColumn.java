/*
 * StatusPickColumn.java
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
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.util.PickColumn;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * A status's picks in the gutter, as IntelliJ's checkboxes: files, hunks
 * and changed lines; a hunk picked whole its box alone, with a line from it
 * down through its changes, as side by side and the editor draw one.
 */
final class StatusPickColumn extends PickColumn {
    private final VcsStatusBuffer status;

    StatusPickColumn(VcsStatusBuffer status) {
        super(status);
        this.status = status;
    }

    @Override
    protected boolean anyPicked() {
        return !status.picks().isEmpty();
    }

    @Override
    protected PickState state(Line line) {
        if (!(line instanceof StatusLine s) || !StatusPicks.pickable(s))
            return null;
        // A hunk picked whole: its lines' boxes its own.
        if (s.kind() == Kind.DIFF && wholeHunk(s))
            return null;
        return status.picks().state(s);
    }

    @Override
    protected Trail trail(Line line) {
        if (!(line instanceof StatusLine s) || s.hunk() == null || s.hunk().isCombined())
            return null;
        if (s.kind() == Kind.HUNK)
            return status.picks().state(s) == PickState.ALL
                    && line.next() instanceof StatusLine n
                    && n.kind() == Kind.DIFF
                    && s.key().equals(n.node()) ? Trail.FROM : null;
        if (s.kind() != Kind.DIFF || !wholeHunk(s))
            return null;
        final int last = lastChange(s.hunk());
        return s.diffIndex() < last ? Trail.THROUGH : s.diffIndex() == last ? Trail.TO : null;
    }

    // Whether the hunk a diff's line is of is picked whole.
    private boolean wholeHunk(StatusLine diff) {
        return status.picks()
                .hunkState(StatusRenderer.fileKeyOf(diff.node()), StatusRenderer.hunkId(diff.node())) == PickState.ALL;
    }

    // The index of h's last changed line.
    private static int lastChange(Hunk h) {
        for (int i = h.lines().size() - 1; i >= 0; i--)
            if (!h.lines().get(i).isContext())
                return i;
        return -1;
    }

    @Override
    protected void toggle(Editor editor, Line line) {
        status.pick(List.of((StatusLine) line));
    }

    @Override
    protected void set(Editor editor, List<Line> lines, boolean on) {
        status.pick(lines.stream().map(l -> (StatusLine) l).toList(), on);
    }

    // A line's key survives a render.
    @Override
    protected Object key(Line line) {
        return ((StatusLine) line).key();
    }

    // Files, hunks or changed lines.
    @Override
    protected boolean sameKind(Line anchor, Line line) {
        return ((StatusLine) anchor).kind() == ((StatusLine) line).kind();
    }
}
