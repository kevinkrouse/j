/*
 * OldSide.java
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

package org.armedbear.j.vcs.sidebyside;

import java.util.List;
import org.armedbear.j.Editor;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.vcs.ViewPane;

/** The old side of a side-by-side diff, at the left: a part of its new side, which has the alignment. */
public final class OldSide extends ViewPane<SideBySideBuffer> {
    private List<Line> changeStarts = List.of();

    OldSide(Mode mode, SideBySideBuffer owner, String title) {
        super(mode, owner, title, "");
    }

    void lay(List<String> lines) {
        changeStarts = SideBySideBuffer.lay(this, lines, owner.getAlignment(), true);
    }

    @Override
    protected KeyMap keyMapOver(KeyMap map, boolean modal) {
        return owner.keys().over(map, modal);
    }

    @Override
    public void changeMode(Mode mode) {
        owner.modeTo(mode);
    }

    @Override
    public List<Line> getChangeStarts() {
        return changeStarts;
    }

    @Override
    public void scrolled(Editor ed) {
        owner.scrollWith(ed, owner);
    }

    @Override
    public void afterCommand(Editor editor) {
        owner.caretWith(editor, owner, true);
    }
}
