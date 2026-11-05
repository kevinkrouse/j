/*
 * ComparedPickColumn.java
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

/**
 * A status's picks in a side of its comparison: on the new side, a box at
 * each change's first changed row, or, while it is picked in part, at each
 * of its added rows; on the old, those of changes that add no line, and of
 * removed lines with no line added for them. A removed line with one is
 * picked with it.
 */
final class ComparedPickColumn extends PickColumn {
    private final VcsStatusBuffer status;
    private final boolean old;

    ComparedPickColumn(VcsStatusBuffer status, boolean old) {
        super(status);
        this.status = status;
        this.old = old;
    }

    @Override
    protected boolean anyPicked() {
        return !status.picks().isEmpty();
    }

    @Override
    protected PickState state(Line line) {
        final RowPicks.Box box = box(line);
        return box == null ? null : box.state();
    }

    @Override
    protected void toggle(Editor editor, Line line) {
        editor.moveDotTo(line, 0);
        status.comparedPicks().toggle(box(line));
    }

    @Override
    protected void set(Editor editor, List<Line> lines, boolean on) {
        editor.moveDotTo(lines.get(lines.size() - 1), 0);
        status.comparedPicks().set(lines.stream().map(this::box).toList(), on);
    }

    // A box is its change's, or its line's, as it is picked or not.
    @Override
    protected Object key(Line line) {
        final RowPicks.Box box = box(line);
        return List.of(box.lines(), box.line());
    }

    // Changes, or changed lines.
    @Override
    protected boolean sameKind(Line anchor, Line line) {
        return box(anchor).line() == box(line).line();
    }

    @Override
    protected Trail trail(Line line) {
        return status.comparedPicks().trailAt(old, false, line.lineNumber());
    }

    // The old side's gutter is at the right of its text.
    @Override
    protected boolean textAtLeft() {
        return old;
    }

    @Override
    public String getToolTipText(Line line, int x, int y) {
        final RowPicks.Box box = isClickable(line, x, y) ? box(line) : null;
        return box == null ? null : box.line() ? "Pick or unpick this line" : "Pick or unpick this change";
    }

    private RowPicks.Box box(Line line) {
        return status.comparedPicks().boxAt(old, line.lineNumber());
    }
}
