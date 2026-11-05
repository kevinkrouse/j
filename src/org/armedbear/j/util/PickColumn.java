/*
 * PickColumn.java
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

package org.armedbear.j.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;

/**
 * A gutter column of what is picked in a buffer: a box at each thing that
 * can be picked, checked when it is, a dash when part of it is; a click
 * toggles it, and a Shift click sets those of its kind from the last
 * clicked to it as that one is now. Empty while nothing is picked, its room
 * kept so that a pick moves no text.
 */
public abstract class PickColumn implements GutterColumn {
    private final Buffer buffer;
    // The key of the line last clicked, a Shift click's range's other end.
    private Object anchor;

    protected PickColumn(Buffer buffer) {
        this.buffer = buffer;
    }

    /** Whether anything is picked. */
    protected abstract boolean anyPicked();

    /** How much of what line shows is picked; null if it can't be. */
    protected abstract PickState state(Line line);

    /** Picks what line shows, or unpicks it. */
    protected abstract void toggle(Editor editor, Line line);

    /** Picks what each of lines shows if on, else unpicks it. */
    protected abstract void set(Editor editor, List<Line> lines, boolean on);

    /** What line is known by across redraws; line itself unless overridden. */
    protected Object key(Line line) {
        return line;
    }

    /**
     * Where a row is on the line drawn down from a box picking several rows
     * through them: its box's, between, or the last.
     */
    public enum Trail {
        FROM, THROUGH, TO
    }

    /** Where line is on the line down from a box picking it with others; null, unless overridden. */
    protected Trail trail(Line line) {
        return null;
    }

    /** Whether the text is at the column's left, as in a gutter at the right; not unless overridden. */
    protected boolean textAtLeft() {
        return false;
    }

    /** Whether a Shift click's range from anchor takes line; any that can be picked unless overridden. */
    protected boolean sameKind(Line anchor, Line line) {
        return true;
    }

    @Override
    public int width() {
        return PickIcon.WIDTH;
    }

    @Override
    public void paint(Graphics2D g, Line line, int x, int y, int height, boolean top) {
        if (!anyPicked())
            return;
        final PickState state = state(line);
        final Color picked = buffer.getFormatter().getHoverLinkColor();
        final Trail trail = trail(line);
        if (trail != null)
            PickIcon.paintTrail(g, trail, textAtLeft(), picked, x, y, height);
        if (state != null)
            PickIcon.paint(g, state, picked, x, y, height);
    }

    @Override
    public String getToolTipText(Line line, int x, int y) {
        return isClickable(line, x, y) ? "Pick or unpick it" : null;
    }

    @Override
    public boolean isClickable(Line line, int x, int y) {
        // No box is drawn while nothing is picked: a click there moves the caret.
        return anyPicked() && state(line) != null;
    }

    @Override
    public boolean isShiftClickable(Line line, int x, int y) {
        return isClickable(line, x, y);
    }

    @Override
    public boolean clicked(Editor editor, Line line, int x, int y) {
        anchor = key(line);
        toggle(editor, line);
        return true;
    }

    @Override
    public void shiftClicked(Editor editor, Line line, int x, int y) {
        final List<Line> range = range(line);
        if (range == null)
            clicked(editor, line, x, y);
        else
            set(editor, range, state(range.get(0)) == PickState.ALL);
    }

    // The lines from the last clicked to to, that one first, those of its
    // kind that can be picked; null if it is gone.
    private List<Line> range(Line to) {
        if (anchor == null)
            return null;
        // Looked for both ways at once, so the walk is as long as the range.
        Line up = to;
        Line down = to.next();
        while (up != null || down != null) {
            if (up != null && isAnchor(up))
                return range(up, to, false);
            if (down != null && isAnchor(down))
                return range(down, to, true);
            up = up == null ? null : up.previous();
            down = down == null ? null : down.next();
        }
        return null;
    }

    private boolean isAnchor(Line l) {
        return state(l) != null && key(l).equals(anchor);
    }

    // The lines of from's kind from from to to, going up if up.
    private List<Line> range(Line from, Line to, boolean up) {
        final List<Line> out = new ArrayList<>();
        for (Line l = from;; l = up ? l.previous() : l.next()) {
            if (state(l) != null && sameKind(from, l))
                out.add(l);
            if (l == to)
                return out;
        }
    }
}
