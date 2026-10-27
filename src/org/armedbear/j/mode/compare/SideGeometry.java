/*
 * SideGeometry.java
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

package org.armedbear.j.mode.compare;

import java.util.List;
import org.armedbear.j.Display;
import org.armedbear.j.Line;
import org.armedbear.j.ViewBuffer;
import org.armedbear.j.VirtualTextLayer;

/**
 * Where a comparison side's lines are, in pixels down the side, as
 * Buffer.getY counts them, rows of virtual text and all; a line folded away
 * is where its fold is. Worked out in one walk, and again only when the
 * side's lines, folds or rows change. What the scroll lock and the track
 * read.
 */
final class SideGeometry {
    // What it was worked out from.
    private record Key(int ch, Line first, int folds, int rows) {
        static Key of(ViewBuffer side) {
            return new Key(Display.getCharHeight(),
                    side.getFirstLine(),
                    side.getFoldVersion(),
                    side.getVirtualText().rowsVersion());
        }
    }

    private final Key key;
    private final int ch;
    // Each line's rows' top, as getY gives it, and its text's top.
    private final int[] start;
    private final int[] top;
    private final boolean[] shown;
    // The height of all of it.
    private final int height;

    SideGeometry(int ch, int[] start, int[] top, boolean[] shown, int height) {
        this(null, ch, start, top, shown, height);
    }

    private SideGeometry(Key key, int ch, int[] start, int[] top, boolean[] shown, int height) {
        this.key = key;
        this.ch = ch;
        this.start = start;
        this.top = top;
        this.shown = shown;
        this.height = height;
    }

    /** side's, or was if side has not changed since it was worked out. */
    static SideGeometry of(ViewBuffer side, SideGeometry was) {
        if (side.needsRenumbering())
            side.renumber();
        final Key key = Key.of(side);
        if (was != null && key.equals(was.key))
            return was;
        final List<Line> lines = CompareBuffer.lines(side);
        final int n = lines.size();
        final int[] start = new int[n];
        final int[] top = new int[n];
        final boolean[] shown = new boolean[n];
        final VirtualTextLayer layer = side.getVirtualText();
        boolean first = true;
        int y = 0;
        for (int i = 0; i < n; i++) {
            final Line l = lines.get(i);
            start[i] = y;
            top[i] = y;
            if (l.isHidden())
                continue;
            shown[i] = true;
            y += layer.rowsAbove(l, first) * key.ch();
            first = false;
            top[i] = y;
            y += key.ch() + layer.rowsBelow(l) * key.ch();
        }
        return new SideGeometry(key, key.ch(), start, top, shown, y);
    }

    /** The height of all of it. */
    int height() {
        return height;
    }

    private int clamp(int line) {
        return Math.max(0, Math.min(line, top.length - 1));
    }

    /** Where line's rows begin: getY's. */
    int start(int line) {
        return top.length == 0 ? 0 : start[clamp(line)];
    }

    /** The top of line's text. */
    int top(int line) {
        return top.length == 0 ? 0 : top[clamp(line)];
    }

    /** The bottom of line's text, over any rows under it. */
    int bottom(int line) {
        if (top.length == 0)
            return 0;
        final int i = clamp(line);
        return shown[i] ? top[i] + ch : top[i];
    }

    /** The place at y, in lines and a fraction of one, in the line shown there or last over it. */
    double placeAt(int y) {
        if (top.length == 0)
            return 0;
        int lo = 0;
        int hi = top.length - 1;
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (top[mid] <= y)
                lo = mid;
            else
                hi = mid - 1;
        }
        while (lo > 0 && !shown[lo])
            --lo;
        return shown[lo] ? lo + Math.max(0.0, Math.min(1.0, (y - top[lo]) / (double) ch)) : lo;
    }

    /** Where place is, in pixels: in a line folded away, at its fold. */
    int yOf(double place) {
        if (top.length == 0)
            return 0;
        final int i = clamp((int) Math.floor(place));
        return top[i] + (shown[i] ? (int) Math.round(Math.max(0.0, Math.min(1.0, place - i)) * ch) : 0);
    }
}
