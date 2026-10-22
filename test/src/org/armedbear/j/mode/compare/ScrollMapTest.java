/*
 * ScrollMapTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.armedbear.j.mode.compare.DiffAlignment.Change;
import org.junit.jupiter.api.Test;

/** Places on one side of a comparison on the other, for scrolling them together. */
public class ScrollMapTest {
    private static final double EPSILON = 1e-9;

    @Test
    public void withNoChangesALineIsItself() {
        final ScrollMap m = ScrollMap.of(List.of(), 10);
        assertEquals(4.5, m.toNew(4.5), EPSILON);
        assertEquals(4.5, m.toOld(4.5), EPSILON);
    }

    @Test
    public void aChangesLinesSpreadOverTheOthersEvenly() {
        // Old lines 2 and 3 became new lines 2 to 5.
        final ScrollMap m = ScrollMap.of(List.of(new Change(2, 2, 2, 4)), 10);
        assertEquals(1.5, m.toNew(1.5), EPSILON);
        assertEquals(2, m.toNew(2), EPSILON);
        assertEquals(4, m.toNew(3), EPSILON, "half way through, half way through");
        assertEquals(6, m.toNew(4), EPSILON);
        assertEquals(8, m.toNew(6), EPSILON, "two lines further on after it");
        assertEquals(3, m.toOld(4), EPSILON);
    }

    @Test
    public void anAddedRunTakesInTheLineBeforeIt() {
        // Three lines added before old line 5.
        final ScrollMap m = ScrollMap.of(List.of(new Change(5, 0, 5, 3)), 10);
        assertEquals(4, m.toNew(4), EPSILON);
        assertEquals(4 + 4 * 0.5, m.toNew(4.5), EPSILON, "old line 4 spans it and the three");
        assertEquals(8, m.toNew(5), EPSILON);
        assertEquals(4.25, m.toOld(5), EPSILON);
    }

    @Test
    public void anAddedRunAtTheTopTakesInTheLineAfterIt() {
        final ScrollMap m = ScrollMap.of(List.of(new Change(0, 0, 0, 3)), 10);
        assertEquals(0, m.toNew(0), EPSILON, "both at the top");
        assertEquals(2, m.toNew(0.5), EPSILON);
        assertEquals(4, m.toNew(1), EPSILON);
        assertEquals(6, m.toNew(3), EPSILON);
    }

    @Test
    public void aRemovedRunIsTheSameTheOtherWay() {
        final ScrollMap m = ScrollMap.of(List.of(new Change(3, 4, 3, 0)), 10);
        assertEquals(2.5, m.toNew(4.5), EPSILON, "old 2 to 7 over new 2 to 3");
        assertEquals(4.5, m.toOld(2.5), EPSILON);
        assertEquals(4, m.toNew(8), EPSILON);
    }

    @Test
    public void anEmptySidesBlankLineSpansAllOfTheOther() {
        final ScrollMap added = ScrollMap.of(List.of(new Change(0, 0, 0, 5)), 0);
        assertEquals(0, added.toNew(0), EPSILON, "both at the top");
        assertEquals(5, added.toNew(1), EPSILON);
        assertEquals(0, added.toOld(0), EPSILON);
        final ScrollMap removed = ScrollMap.of(List.of(new Change(0, 5, 0, 0)), 5);
        assertEquals(0, removed.toOld(0), EPSILON, "both at the top");
        assertEquals(5, removed.toOld(1), EPSILON);
    }

    @Test
    public void changesWithNoLinesBetweenRunTogether() {
        // b removed, then right after it two lines added.
        final ScrollMap m = ScrollMap.of(List.of(new Change(1, 1, 1, 0), new Change(2, 0, 1, 2)), 10);
        // Old 0..2 over new 0..3, then line for line.
        assertEquals(4, m.toNew(3), EPSILON);
        for (double x = 0; x <= 3; x += 0.25)
            assertEquals(x, m.toOld(m.toNew(x)), EPSILON, "both ways at " + x);
    }
}
