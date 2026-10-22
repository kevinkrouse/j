/*
 * ScrollMap.java
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

import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.mode.compare.DiffAlignment.Change;

/**
 * Where a place on one side of a comparison is on the other, by its
 * changes, in lines and fractions of them, for scrolling the two together
 * when they are not aligned, as IntelliJ's diff viewer does: a line both
 * have is the same line, and a change's lines spread evenly over the other
 * side's, so that the side with more lines there scrolls faster. A change
 * with no lines on one side takes in the line before it on both, or else
 * the line after it, or an empty side's blank line, so that the other side
 * does not jump.
 */
final class ScrollMap {
    // Where the pieces meet, ascending on both sides: between two, a place
    // on one side is as far along on the other.
    private final double[] olds;
    private final double[] news;

    private ScrollMap(List<double[]> knots) {
        olds = new double[knots.size()];
        news = new double[knots.size()];
        for (int i = 0; i < knots.size(); i++) {
            olds[i] = knots.get(i)[0];
            news[i] = knots.get(i)[1];
        }
    }

    /** The map of changes, in order, between sides of which the old has oldSize lines. */
    static ScrollMap of(List<Change> changes, int oldSize) {
        final List<double[]> knots = new ArrayList<>();
        knots.add(new double[] { 0, 0 });
        // Where the last piece ends on the old side.
        int oldEnd = 0;
        for (int i = 0; i < changes.size(); i++) {
            final Change c = changes.get(i);
            final boolean oneSided = c.oldCount() == 0 || c.newCount() == 0;
            // The lines both have before it, after the last piece.
            final int before = c.oldStart() - oldEnd;
            final int took = oneSided && before > 0 ? 1 : 0;
            if (before - took > 0)
                knots.add(new double[] { c.oldStart() - took, c.newStart() - took });
            int o = c.oldStart() + c.oldCount();
            int n = c.newStart() + c.newCount();
            final int next = i + 1 < changes.size() ? changes.get(i + 1).oldStart() : oldSize;
            if (oneSided && took == 0 && next > o) {
                ++o;
                ++n;
            }
            // An empty side's blank line spans all of the other side's.
            final double[] last = knots.get(knots.size() - 1);
            if (o == last[0])
                ++o;
            if (n == last[1])
                ++n;
            knots.add(new double[] { o, n });
            oldEnd = o;
        }
        return new ScrollMap(knots);
    }

    /** The place on the new side that old, a place on the old side, is at. */
    double toNew(double old) {
        return map(olds, news, old);
    }

    /** The place on the old side that a place on the new side is at. */
    double toOld(double place) {
        return map(news, olds, place);
    }

    // Through the pieces from one side to the other; past the last, line for line.
    private static double map(double[] from, double[] to, double x) {
        int i = 0;
        while (i + 1 < from.length && from[i + 1] <= x)
            ++i;
        if (i + 1 == from.length)
            return to[i] + (x - from[i]);
        return to[i] + (x - from[i]) * (to[i + 1] - to[i]) / (from[i + 1] - from[i]);
    }
}
