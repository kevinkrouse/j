/*
 * TrackGeometryTest.java
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.mode.compare.DiffAlignment.Change;
import org.armedbear.j.mode.compare.DiffAlignment.Kind;
import org.armedbear.j.mode.compare.TrackGeometry.Band;
import org.junit.jupiter.api.Test;

/** The track's bands, from the lines' places on each side. */
public class TrackGeometryTest {
    // Thirty lines ten pixels high, with rows of filler after the lines
    // given, each line followed by how many.
    private static SideGeometry side(int... fillersAfter) {
        final int n = 30;
        final int[] start = new int[n];
        final int[] top = new int[n];
        final boolean[] shown = new boolean[n];
        int y = 0;
        for (int i = 0; i < n; i++) {
            start[i] = y;
            top[i] = y;
            shown[i] = true;
            y += 10;
            for (int k = 0; k < fillersAfter.length; k += 2)
                if (fillersAfter[k] == i)
                    y += 10 * fillersAfter[k + 1];
        }
        return new SideGeometry(10, start, top, shown, y);
    }

    @Test
    public void aChangedLineIsABandLevelWithItOnBothSides() {
        final List<Band> bands = TrackGeometry.bands(List.of(new Change(2, 1, 2, 1)), side(), 0, side(), 0, 100);
        assertEquals(List.of(new Band(Kind.CHANGED, 20, 30, 20, 30)), bands);
    }

    @Test
    public void anAddedRunNarrowsToAPointUnderTheLineBeforeItOnTheLeft() {
        // Two lines added after line 1: the left has two fillers there.
        final List<Band> bands = TrackGeometry.bands(List.of(new Change(2, 0, 2, 2)), side(1, 2), 0, side(), 0, 100);
        assertEquals(List.of(new Band(Kind.ADDED, 20, 20, 20, 40)), bands);
    }

    @Test
    public void aRemovedRunNarrowsToAPointOnTheRight() {
        final List<Band> bands = TrackGeometry.bands(List.of(new Change(3, 3, 3, 0)), side(), 0, side(2, 3), 0, 100);
        assertEquals(List.of(new Band(Kind.REMOVED, 30, 60, 30, 30)), bands);
    }

    @Test
    public void aChangeAtTheTopWithNoLinesOnOneSideIsAtItsStart() {
        final List<Band> bands = TrackGeometry.bands(List.of(new Change(0, 0, 0, 1)), side(), -5, side(), -5, 100);
        assertEquals(List.of(new Band(Kind.ADDED, 5, 5, 5, 15)), bands);
    }

    @Test
    public void onlyTheChangesInTheTrackAreBanded() {
        final List<Change> changes = List.of(new Change(1, 1, 1, 1), new Change(8, 1, 8, 1), new Change(20, 1, 20, 1));
        // Scrolled 50 down: line 1 is above, line 8 in, line 20 below.
        final List<Band> bands = TrackGeometry.bands(changes, side(), 50, side(), 50, 100);
        assertEquals(1, bands.size());
        assertEquals(30, bands.get(0).leftTop());
    }

    @Test
    public void aBandsEdgesAreCubicsLevelWhereTheyMeetEachSide() {
        final List<double[]> segments = new ArrayList<>();
        final PathIterator it = new Band(Kind.ADDED, 20, 20, 20, 40).shape(30).getPathIterator(null);
        final double[] c = new double[6];
        for (; !it.isDone(); it.next()) {
            final int type = it.currentSegment(c);
            segments.add(type == PathIterator.SEG_CUBICTO ? c.clone() : new double[] { type, c[0], c[1] });
        }
        // The top edge from (0, 20) to (30, 20), level at both ends.
        assertArrayEquals(new double[] { 15, 20, 15, 20, 30, 20 }, segments.get(1));
        // The bottom edge back from (30, 40) to the point (0, 20).
        assertArrayEquals(new double[] { 15, 40, 15, 20, 0, 20 }, segments.get(3));
    }
}
