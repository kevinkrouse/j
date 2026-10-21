/*
 * DiffAlignment.java
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

import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.Diff.Edit;

/**
 * Two texts side by side, row by row: each row the old side's line and the
 * new side's, or a filler where a change has fewer lines on one side, so
 * that the lines both have are level. What the side-by-side diff's panes
 * and their scroll lock read.
 */
public final class DiffAlignment {
    /** What a row is: a line both have, one changed into the other, or one only one side has. */
    public enum Kind {
        SAME, CHANGED, ADDED, REMOVED
    }

    /** A row: the old side's line at it and the new's, 0-based, -1 for a filler. */
    public record Row(int oldLine, int newLine, Kind kind) {}

    /**
     * A change: old lines oldStart to oldStart + oldCount became new lines
     * newStart to newStart + newCount. A count of 0 is an insertion or
     * deletion before that line.
     */
    public record Change(int oldStart, int oldCount, int newStart, int newCount) {}

    private final List<Row> rows;
    private final List<Change> changes;
    // Each line's row, by side.
    private final int[] oldRow;
    private final int[] newRow;

    private DiffAlignment(List<Row> rows, List<Change> changes, int oldSize, int newSize) {
        this.rows = List.copyOf(rows);
        this.changes = List.copyOf(changes);
        oldRow = new int[oldSize];
        newRow = new int[newSize];
        for (int r = 0; r < rows.size(); r++) {
            final Row row = rows.get(r);
            if (row.oldLine() >= 0)
                oldRow[row.oldLine()] = r;
            if (row.newLine() >= 0)
                newRow[row.newLine()] = r;
        }
    }

    /** The two lists of lines aligned, by {@link Diff}'s edits. */
    public static DiffAlignment of(List<String> oldLines, List<String> newLines) {
        return of(Diff.diff(oldLines, newLines), oldLines.size(), newLines.size());
    }

    /** Sides of oldSize and newSize lines aligned by edits, which are in order. */
    public static DiffAlignment of(List<Edit> edits, int oldSize, int newSize) {
        final List<Row> rows = new ArrayList<>();
        final List<Change> changes = new ArrayList<>();
        int o = 0;
        int n = 0;
        for (Edit e : edits) {
            // The lines both have before it.
            while (o < e.oldStart() && n < e.newStart())
                rows.add(new Row(o++, n++, Kind.SAME));
            final int both = Math.min(e.oldCount(), e.newCount());
            for (int i = 0; i < both; i++)
                rows.add(new Row(e.oldStart() + i, e.newStart() + i, Kind.CHANGED));
            for (int i = both; i < e.oldCount(); i++)
                rows.add(new Row(e.oldStart() + i, -1, Kind.REMOVED));
            for (int i = both; i < e.newCount(); i++)
                rows.add(new Row(-1, e.newStart() + i, Kind.ADDED));
            changes.add(new Change(e.oldStart(), e.oldCount(), e.newStart(), e.newCount()));
            o = e.oldStart() + e.oldCount();
            n = e.newStart() + e.newCount();
        }
        while (o < oldSize && n < newSize)
            rows.add(new Row(o++, n++, Kind.SAME));
        return new DiffAlignment(rows, changes, oldSize, newSize);
    }

    /** How many lines the old side has. */
    public int oldSize() {
        return oldRow.length;
    }

    /** How many lines the new side has. */
    public int newSize() {
        return newRow.length;
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Change> changes() {
        return changes;
    }

    /** The row old line line is at. */
    public int rowOfOld(int line) {
        return oldRow[line];
    }

    /** The row new line line is at. */
    public int rowOfNew(int line) {
        return newRow[line];
    }

    /**
     * How many filler rows the old side has right after its line line, or
     * before its first line for -1: where a change has more new lines than
     * old.
     */
    public int fillersAfterOld(int line) {
        return fillersAfter(line, true);
    }

    /** The same for the new side, where a change has more old lines than new. */
    public int fillersAfterNew(int line) {
        return fillersAfter(line, false);
    }

    private int fillersAfter(int line, boolean old) {
        int r = line < 0 ? 0 : (old ? oldRow[line] : newRow[line]) + 1;
        int count = 0;
        while (r < rows.size() && (old ? rows.get(r).oldLine() : rows.get(r).newLine()) < 0) {
            ++count;
            ++r;
        }
        return count;
    }

    /**
     * The other side's line at the row of line on one side, or the nearest
     * line before it there when the row is a filler; -1 if there is none.
     * What the caret of one pane takes the other's to.
     */
    public int otherLine(int line, boolean fromOld) {
        int r = fromOld ? oldRow[line] : newRow[line];
        for (; r >= 0; r--) {
            final Row row = rows.get(r);
            final int other = fromOld ? row.newLine() : row.oldLine();
            if (other >= 0)
                return other;
        }
        return -1;
    }
}
