/*
 * DiffAlignmentTest.java
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

package org.armedbear.j.vcs.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.vcs.diff.DiffAlignment.Change;
import org.armedbear.j.vcs.diff.DiffAlignment.Row;
import org.armedbear.j.vcs.signs.EditorHunks.Side;
import org.junit.jupiter.api.Test;

public class DiffAlignmentTest {
    private static DiffAlignment align(String oldText, String newText) {
        return DiffAlignment.of(lines(oldText), lines(newText));
    }

    private static List<String> lines(String text) {
        return Side.of(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).lines();
    }

    // Each row as "old|new kind": the lines' numbers, "-" for a filler.
    private static List<String> rows(DiffAlignment a) {
        final List<String> out = new ArrayList<>();
        for (Row r : a.rows())
            out.add(
                (r.oldLine() < 0 ? "-" : String.valueOf(r.oldLine())) + "|"
                        + (r.newLine() < 0 ? "-" : String.valueOf(r.newLine())) + " " + r.kind());
        return out;
    }

    @Test
    public void aChangeOfMoreNewLinesHasFillersOnTheOldSide() {
        final DiffAlignment a = align("a\nb\nc\n", "a\nB1\nB2\nB3\nc\n");
        assertEquals(List.of("0|0 SAME", "1|1 CHANGED", "-|2 ADDED", "-|3 ADDED", "2|4 SAME"), rows(a));
        assertEquals(2, a.fillersAfterOld(1), "under the changed line");
        assertEquals(0, a.fillersAfterNew(1));
        assertEquals(List.of(new Change(1, 1, 1, 3)), a.changes());
        assertEquals(4, a.rowOfOld(2), "the line after, level with its new one");
        assertEquals(4, a.rowOfNew(4));
    }

    @Test
    public void changesAtTheTopAndTheBottom() {
        final DiffAlignment a = align("x\na\nb\ny\n", "a\nb\nz1\nz2\n");
        assertEquals(List.of("0|- REMOVED", "1|0 SAME", "2|1 SAME", "3|2 CHANGED", "-|3 ADDED"), rows(a));
        assertEquals(1, a.fillersAfterNew(-1), "before the new side's first line");
        assertEquals(1, a.fillersAfterOld(3), "after the old side's last");
    }

    @Test
    public void aNewFileAndADeletedOneAreAllFillersOnOneSide() {
        final DiffAlignment added = align("", "a\nb\n");
        assertEquals(List.of("-|0 ADDED", "-|1 ADDED"), rows(added));
        assertEquals(2, added.fillersAfterOld(-1));
        assertEquals(List.of(new Change(0, 0, 0, 2)), added.changes());
        final DiffAlignment deleted = align("a\nb\n", "");
        assertEquals(List.of("0|- REMOVED", "1|- REMOVED"), rows(deleted));
        assertEquals(2, deleted.fillersAfterNew(-1));
        assertEquals(List.of(), rows(align("", "")));
    }

    @Test
    public void aChangeOfOnlyBlankLines() {
        final DiffAlignment a = align("a\n\nb\n", "a\n\n\n\nb\n");
        assertEquals(List.of("0|0 SAME", "1|1 SAME", "-|2 ADDED", "-|3 ADDED", "2|4 SAME"), rows(a));
        assertEquals(1, a.changes().size());
    }

    @Test
    public void crLfAndLfLinesAreTheSame() {
        assertEquals(List.of("a", "b"), lines("a\r\nb\r\n"));
        assertEquals(List.of("a", "b", ""), lines("a\nb\n\n"), "a last blank line kept");
        assertEquals(List.of("a"), lines("a"), "no newline at the end");
        assertEquals(List.of("0|0 SAME", "1|1 SAME"), rows(align("a\r\nb\r\n", "a\nb\n")));
    }

    @Test
    public void theOtherSidesLineIsTheRowsOrTheOneBeforeAFiller() {
        final DiffAlignment a = align("a\nb\nc\n", "a\nB1\nB2\nB3\nc\n");
        assertEquals(1, a.otherLine(1, true));
        assertEquals(1, a.otherLine(3, false), "a filler on the old side: the changed line above it");
        assertEquals(2, a.otherLine(4, false));
        final DiffAlignment top = align("x\na\n", "a\n");
        assertEquals(-1, top.otherLine(0, true), "nothing on the new side before it");
    }
}
