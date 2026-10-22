/*
 * TrackGeometry.java
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

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.mode.compare.DiffAlignment.Change;
import org.armedbear.j.mode.compare.DiffAlignment.Kind;

/**
 * The track's bands, as IntelliJ's diff viewer has them between its panes:
 * for each change, a shape from its lines on the left to its lines on the
 * right, its top and bottom edges cubic curves. A change with no lines on
 * one side narrows to a point there, under the line it comes after.
 */
final class TrackGeometry {
    private TrackGeometry() {}

    /** A change's band: where its edges meet each side, in pixels down the track. */
    record Band(Kind kind, int leftTop, int leftBottom, int rightTop, int rightBottom) {
        /** Its shape in a track width wide, each edge a cubic Bézier level at both ends. */
        Path2D shape(int width) {
            final double mid = width / 2.0;
            final Path2D p = new Path2D.Double();
            p.moveTo(0, leftTop);
            p.curveTo(mid, leftTop, mid, rightTop, width, rightTop);
            p.lineTo(width, rightBottom);
            p.curveTo(mid, rightBottom, mid, leftBottom, 0, leftBottom);
            p.closePath();
            return p;
        }
    }

    /**
     * The bands of changes in a track height high, each side's lines where
     * its geometry has them less how far its window is scrolled.
     */
    static List<Band> bands(
            List<Change> changes,
            SideGeometry left,
            int leftScroll,
            SideGeometry right,
            int rightScroll,
            int height) {
        final List<Band> out = new ArrayList<>();
        for (Change c : changes) {
            final int[] l = span(left, c.oldStart(), c.oldCount(), leftScroll);
            final int[] r = span(right, c.newStart(), c.newCount(), rightScroll);
            if (Math.max(l[1], r[1]) >= 0 && Math.min(l[0], r[0]) <= height)
                out.add(new Band(kind(c), l[0], l[1], r[0], r[1]));
        }
        return out;
    }

    static Kind kind(Change c) {
        return c.oldCount() == 0 ? Kind.ADDED : c.newCount() == 0 ? Kind.REMOVED : Kind.CHANGED;
    }

    // From the top of a change's first line on a side to the bottom of its
    // last; with none there, the point under the line before it.
    private static int[] span(SideGeometry g, int start, int count, int scroll) {
        if (count > 0)
            return new int[] { g.top(start) - scroll, g.bottom(start + count - 1) - scroll };
        final int at = (start > 0 ? g.bottom(start - 1) : 0) - scroll;
        return new int[] { at, at };
    }
}
