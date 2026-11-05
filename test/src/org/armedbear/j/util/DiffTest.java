/*
 * DiffTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.armedbear.j.util.Diff.Edit;
import org.junit.jupiter.api.Test;

public class DiffTest {
    private static List<String> lines(String s) {
        return s.isEmpty() ? List.of() : List.of(s.split(""));
    }

    // a with the edits made, taking the new lines from b.
    private static List<String> apply(List<String> a, List<String> b, List<Edit> edits) {
        final List<String> out = new ArrayList<>();
        int i = 0;
        for (Edit e : edits) {
            assertTrue(e.oldStart() >= i, "in order: " + edits);
            while (i < e.oldStart())
                out.add(a.get(i++));
            out.addAll(b.subList(e.newStart(), e.newStart() + e.newCount()));
            i += e.oldCount();
        }
        while (i < a.size())
            out.add(a.get(i++));
        return out;
    }

    private static int lcs(List<String> a, List<String> b) {
        final int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--)
            for (int j = b.size() - 1; j >= 0; j--)
                t[i][j] = a.get(i).equals(b.get(j)) ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
        return t[0][0];
    }

    private static int kept(List<String> a, List<Edit> edits) {
        int changed = 0;
        for (Edit e : edits)
            changed += e.oldCount();
        return a.size() - changed;
    }

    @Test
    public void aChangedLineInTheMiddle() {
        assertEquals(List.of(new Edit(1, 1, 1, 1)), Diff.diff(lines("abc"), lines("aXc")));
    }

    @Test
    public void insertionsAndDeletions() {
        assertEquals(List.of(new Edit(1, 0, 1, 2)), Diff.diff(lines("ab"), lines("aXYb")));
        assertEquals(List.of(new Edit(1, 2, 1, 0)), Diff.diff(lines("aXYb"), lines("ab")));
        assertEquals(List.of(new Edit(0, 0, 0, 3)), Diff.diff(List.of(), lines("abc")));
        assertEquals(List.of(), Diff.diff(lines("abc"), lines("abc")));
    }

    @Test
    public void aLineChangedAmongBlankOnesIsAChangeNotAnAddedAndARemovedBlank() {
        // A blank line among blank ones changed, and a line further down removed:
        // the blank isn't counted as removed below the change.
        assertEquals(
            List.of(new Edit(1, 1, 1, 1), new Edit(6, 1, 6, 0)),
            Diff.diff(lines("a____#X_b"), lines("aC___#_b")));
        // As reported: line 7 changed, line 11 deleted.
        final List<String> old = List.of(
            "this is a test",
            "hello text",
            "",
            "",
            "and this too",
            "",
            "",
            "",
            "",
            "## header1",
            "changed text",
            "",
            "some changed lines");
        final List<String> now = new ArrayList<>(old);
        now.set(6, "changed");
        now.remove(10);
        assertEquals(List.of(new Edit(6, 1, 6, 1), new Edit(10, 1, 10, 0)), Diff.diff(old, now));
    }

    @Test
    public void randomEditsTurnOneIntoTheOtherKeepingALongestCommonRun() {
        final Random rnd = new Random(7);
        for (int round = 0; round < 3000; round++) {
            final StringBuilder sa = new StringBuilder();
            final int n = rnd.nextInt(14);
            for (int i = 0; i < n; i++)
                sa.append((char) ('a' + rnd.nextInt(4)));
            final StringBuilder sb = new StringBuilder(sa);
            for (int k = rnd.nextInt(5); k > 0; k--) {
                final int at = sb.length() == 0 ? 0 : rnd.nextInt(sb.length() + 1);
                switch (rnd.nextInt(3)) {
                    case 0 -> sb.insert(at, (char) ('a' + rnd.nextInt(6)));
                    case 1 -> {
                        if (at < sb.length())
                            sb.deleteCharAt(at);
                    }
                    default -> {
                        if (at < sb.length())
                            sb.setCharAt(at, (char) ('a' + rnd.nextInt(6)));
                    }
                }
            }
            final List<String> a = lines(sa.toString());
            final List<String> b = lines(sb.toString());
            final List<Edit> edits = Diff.diff(a, b);
            assertEquals(b, apply(a, b, edits), sa + " -> " + sb + ": " + edits);
            assertEquals(lcs(a, b), kept(a, edits), sa + " -> " + sb + ": " + edits);
            for (Edit e : edits)
                assertTrue(e.oldCount() > 0 || e.newCount() > 0, "no empty edits");
        }
    }

    @Test
    public void filesDifferingEverywhereGiveUpQuicklyAndStillApply() {
        final List<String> a = new ArrayList<>();
        final List<String> b = new ArrayList<>();
        final Random rnd = new Random(3);
        for (int i = 0; i < 20000; i++) {
            a.add("x" + rnd.nextInt(40));
            b.add("x" + rnd.nextInt(40));
        }
        final long start = System.nanoTime();
        final List<Edit> edits = Diff.diff(a, b);
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "past MAX_D it gives up, not searches on");
        assertEquals(b, apply(a, b, edits));
    }

    @Test
    public void aLongFileWithOneChangeIsOneEdit() {
        final List<String> a = new ArrayList<>();
        for (int i = 0; i < 20000; i++)
            a.add("line " + i);
        final List<String> b = new ArrayList<>(a);
        b.set(12345, "changed");
        assertEquals(List.of(new Edit(12345, 1, 12345, 1)), Diff.diff(a, b));
    }

    @Test
    public void anotherDiffsEditsAreTakenOnlyIfTheyTurnOneIntoTheOther() {
        final List<String> a = List.of("  y", "{", "{", "{", "  y");
        final List<String> b = List.of("  y", "{", "{", "  y");
        // git's: the second { removed, where j's removes the third.
        assertTrue(Diff.isDiffOf(a, b, List.of(new Edit(2, 1, 2, 0))));
        assertTrue(Diff.isDiffOf(a, b, Diff.diff(a, b)));
        assertFalse(Diff.isDiffOf(a, b, List.of(new Edit(4, 1, 3, 0))), "the lines left differ");
        assertFalse(Diff.isDiffOf(a, b, List.of()), "the lengths differ");
        assertFalse(Diff.isDiffOf(a, b, List.of(new Edit(2, 1, 2, 0), new Edit(1, 0, 1, 0))), "out of order");
        assertFalse(Diff.isDiffOf(a, a, List.of(new Edit(2, 1, 2, 1))), "changes nothing: staged since");
        assertFalse(Diff.isDiffOf(a, b, List.of(new Edit(5, 1, 5, 0))), "past the end");
    }
}
