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

/** A status's picks in the gutter, as IntelliJ's checkboxes: files, hunks and changed lines. */
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
        return line instanceof StatusLine s && StatusPicks.pickable(s) ? status.picks().state(s) : null;
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
