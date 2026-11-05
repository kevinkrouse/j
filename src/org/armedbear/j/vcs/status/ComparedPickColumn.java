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
 * A status's picks in the new side of its comparison: a box at each hunk's
 * first changed row there, or the row after a removal, or, while it is
 * picked in part, at each of its added rows. A removed line is picked with
 * the line added for it, or by a selection on the old side.
 */
final class ComparedPickColumn extends PickColumn {
    private final VcsStatusBuffer status;

    ComparedPickColumn(VcsStatusBuffer status) {
        super(status);
        this.status = status;
    }

    @Override
    protected boolean anyPicked() {
        return !status.picks().isEmpty();
    }

    @Override
    protected PickState state(Line line) {
        final ComparedPicks.Box box = box(line);
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

    // A box is its hunk's, or its line's, as it is picked or not.
    @Override
    protected Object key(Line line) {
        final ComparedPicks.Box box = box(line);
        return List.of(box.id(), box.i());
    }

    // Hunks, or changed lines.
    @Override
    protected boolean sameKind(Line anchor, Line line) {
        return box(anchor).i() < 0 == box(line).i() < 0;
    }

    @Override
    public String getToolTipText(Line line, int x, int y) {
        final ComparedPicks.Box box = isClickable(line, x, y) ? box(line) : null;
        return box == null ? null : box.i() < 0 ? "Pick or unpick this change" : "Pick or unpick this line";
    }

    private ComparedPicks.Box box(Line line) {
        return status.comparedPicks().boxAt(false, line.lineNumber());
    }
}
