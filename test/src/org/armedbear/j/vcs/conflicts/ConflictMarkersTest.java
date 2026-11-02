/*
 * ConflictMarkersTest.java
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

package org.armedbear.j.vcs.conflicts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.armedbear.j.vcs.conflicts.ConflictMarkers.Region;
import org.junit.jupiter.api.Test;

/** Git's conflict markers, as the parser reads them. */
public class ConflictMarkersTest {

    @Test
    public void aTwoWayRegion() {
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "top", //
                "<<<<<<< HEAD", //
                "ours", //
                "=======", //
                "theirs", //
                ">>>>>>> feature", //
                "bottom"));
        assertEquals(1, regions.size());
        final Region r = regions.get(0);
        assertEquals(1, r.start());
        assertEquals(-1, r.base());
        assertEquals(3, r.separator());
        assertEquals(5, r.end());
        assertFalse(r.hasBase());
        assertEquals("HEAD", r.ours());
        assertEquals("feature", r.theirs());
    }

    @Test
    public void aDiff3RegionHasABase() {
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<< HEAD", //
                "ours", //
                "||||||| merged common ancestors", //
                "base", //
                "=======", //
                "theirs", //
                ">>>>>>> feature"));
        final Region r = regions.get(0);
        assertEquals(0, r.start());
        assertEquals(2, r.base());
        assertEquals(4, r.separator());
        assertEquals(6, r.end());
        assertTrue(r.hasBase());
        assertEquals("HEAD", r.ours());
        assertEquals("feature", r.theirs());
    }

    @Test
    public void aZdiff3RegionIsReadAsDiff3() {
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<< HEAD", //
                "ours", //
                "||||||| base", //
                "base", //
                "=======", //
                "theirs", //
                ">>>>>>> feature"));
        assertTrue(regions.get(0).hasBase());
    }

    @Test
    public void emptyLabelsReadEmpty() {
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<<", //
                "ours", //
                "=======", //
                "theirs", //
                ">>>>>>>"));
        final Region r = regions.get(0);
        assertEquals("", r.ours());
        assertEquals("", r.theirs());
    }

    @Test
    public void aLongerMarkerHoldsAShorterOneAsText() {
        // Nine opens; a seven-long one inside is text, not a separator.
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<<<< HEAD", //
                "=======", //
                "|||||||", //
                "our text", //
                "=========", //
                "their text", //
                ">>>>>>>>> feature"));
        assertEquals(1, regions.size());
        final Region r = regions.get(0);
        assertEquals(0, r.start());
        assertEquals(4, r.separator());
        assertEquals(6, r.end());
        assertFalse(r.hasBase());
    }

    @Test
    public void aSeparatorTakesNothingAfterIt() {
        // "======= text" is not a separator: no region closes.
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<< HEAD", //
                "ours", //
                "======= not a separator", //
                "theirs", //
                ">>>>>>> feature"));
        assertTrue(regions.isEmpty());
    }

    @Test
    public void anOpenerWithNoCloserIsNotARegionButCounts() {
        final List<String> lines = List.of( //
            "<<<<<<< HEAD", //
            "ours", //
            "still ours");
        assertTrue(ConflictMarkers.regions(lines).isEmpty());
        assertEquals(1, ConflictMarkers.count(lines));
    }

    @Test
    public void twoRegions() {
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<< HEAD", //
                "a ours", //
                "=======", //
                "a theirs", //
                ">>>>>>> one", //
                "between", //
                "<<<<<<< HEAD", //
                "b ours", //
                "=======", //
                "b theirs", //
                ">>>>>>> two"));
        assertEquals(2, regions.size());
        assertEquals(0, regions.get(0).start());
        assertEquals(2, regions.get(0).separator());
        assertEquals(4, regions.get(0).end());
        assertEquals(6, regions.get(1).start());
        assertEquals(8, regions.get(1).separator());
        assertEquals(10, regions.get(1).end());
    }

    @Test
    public void aSecondOpenerAbandonsTheFirst() {
        // The first region never closed; the second is the one read.
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<< HEAD", //
                "abandoned", //
                "<<<<<<< HEAD", //
                "kept", //
                "=======", //
                "theirs", //
                ">>>>>>> feature"));
        assertEquals(1, regions.size());
        assertEquals(2, regions.get(0).start());
    }

    @Test
    public void countCountsOpenersOnly() {
        final List<String> lines = List.of( //
            "x", //
            "<<<<<<< HEAD", //
            "=======", //
            ">>>>>>> one", //
            "<<<<<<< HEAD", //
            "no closer");
        assertEquals(2, ConflictMarkers.count(lines));
    }

    @Test
    public void crlfLinesParse() {
        // Read with the \r kept: the marker ends at the \r.
        final List<Region> regions = ConflictMarkers.regions(
            List.of( //
                "<<<<<<< HEAD\r", //
                "ours\r", //
                "=======\r", //
                "theirs\r", //
                ">>>>>>> feature\r"));
        assertEquals(1, regions.size());
        assertEquals("HEAD", regions.get(0).ours());
    }

    @Test
    public void aMarkerNeedsSeven() {
        final List<String> lines = List.of( //
            "<<<<<<< HEAD", //
            "ours", //
            "======", //
            "theirs", //
            ">>>>>>> feature");
        // The six-long separator is text; nothing closes.
        assertTrue(ConflictMarkers.regions(lines).isEmpty());
    }
}
