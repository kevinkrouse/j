/*
 * EditorPickColumn.java
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

import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.util.PickColumn;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.signs.SignsUpdater;

/**
 * A status's picks in a file's own buffer, the new side of its unstaged
 * diff, by its signs: a change's box at its first sign, or, while it is
 * picked in part, a box at each sign for the lines it marks; and on a
 * collapsed fold's line, a box for the changes it hides. There while
 * something of the file is picked, its boxes only while the signs are by
 * the status's diff: none while the buffer has edits unsaved, or is saved
 * and not yet read again.
 */
final class EditorPickColumn extends PickColumn {
    private final Buffer buffer;
    private final RowPicks picks;

    EditorPickColumn(Buffer buffer, RowPicks picks) {
        super(buffer);
        this.buffer = buffer;
        this.picks = picks;
    }

    @Override
    protected boolean anyPicked() {
        return true;
    }

    @Override
    protected PickState state(Line line) {
        if (!placed())
            return null;
        if (folded(line))
            return picks.signsState(line.lineNumber(), last(line).lineNumber());
        final RowPicks.Box box = picks.signBoxAt(line.lineNumber());
        return box == null ? null : box.state();
    }

    @Override
    protected void toggle(Editor editor, Line line) {
        if (folded(line))
            picks.setSigns(line.lineNumber(), last(line).lineNumber(), state(line) != PickState.ALL);
        else
            picks.toggle(picks.signBoxAt(line.lineNumber()));
    }

    @Override
    protected void set(Editor editor, List<Line> lines, boolean on) {
        final List<RowPicks.Box> boxes = new ArrayList<>();
        final List<int[]> folds = new ArrayList<>();
        for (Line l : lines)
            if (folded(l))
                folds.add(new int[] { l.lineNumber(), last(l).lineNumber() });
            else
                boxes.add(picks.signBoxAt(l.lineNumber()));
        if (!boxes.isEmpty())
            picks.set(boxes, on);
        if (!folds.isEmpty())
            picks.setSigns(folds, on);
    }

    @Override
    protected Trail trail(Line line) {
        return folded(line) || !placed() ? null : picks.trailAt(false, true, line.lineNumber());
    }

    // Whether the signs are by the status's diff, whose rows the boxes are by.
    private boolean placed() {
        final SignsUpdater u = buffer.getSignsUpdater();
        return u != null && u.isGits();
    }

    // A box is its change's, its sign's, or a fold's, as it is picked or not.
    @Override
    protected Object key(Line line) {
        if (folded(line))
            return List.of("fold", line.lineNumber());
        final RowPicks.Box box = picks.signBoxAt(line.lineNumber());
        return List.of(box.lines(), box.line());
    }

    // Whether line heads a fold that is collapsed.
    private boolean folded(Line line) {
        return buffer.getFolds().isFolded(line);
    }

    // The last line line's fold hides.
    private static Line last(Line line) {
        Line l = line;
        while (l.next() != null && l.next().isHidden())
            l = l.next();
        return l;
    }
}
