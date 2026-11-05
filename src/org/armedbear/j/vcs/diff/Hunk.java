/*
 * Hunk.java
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
import org.armedbear.j.util.Diff.Edit;

/**
 * A hunk of a unified diff. Starts are 1-based line numbers as the "@@"
 * header has them; a side with no lines starts at the line before. A
 * combined diff's has its header's ranges, "-1,3 -1,3 +1,9", the old
 * side its first parent's, and its lines' marks.
 */
public record Hunk(int oldStart, int oldCount, int newStart, int newCount, String section, List<DiffLine> lines,
        String combinedRanges) {
    public Hunk {
        lines = List.copyOf(lines);
        section = section == null ? "" : section;
    }

    public Hunk(int oldStart, int oldCount, int newStart, int newCount, String section, List<DiffLine> lines) {
        this(oldStart, oldCount, newStart, newCount, section, lines, null);
    }

    /** Whether it is a combined diff's, which no patch can apply. */
    public boolean isCombined() {
        return combinedRanges != null;
    }

    /** A combined hunk's parent p's, from 0, start, from its header. */
    public int parentStart(int p) {
        final String range = combinedRanges.split(" ")[p].substring(1);
        final int comma = range.indexOf(',');
        return Integer.parseInt(comma < 0 ? range : range.substring(0, comma));
    }

    /**
     * The line of a combined hunk's line at index in parent p, from 1;
     * for a line not in it, the parent's next line's.
     */
    public int parentLine(int index, int p) {
        int n = parentStart(p);
        for (int i = 0; i < index; i++)
            if (lines.get(i).inParent(p))
                n++;
        return n;
    }

    /** A hunk of these lines, its counts from them. */
    static Hunk of(int oldStart, int newStart, String section, List<DiffLine> lines) {
        int oldCount = 0;
        int newCount = 0;
        for (DiffLine line : lines) {
            if (line.isOld())
                oldCount++;
            if (line.isNew())
                newCount++;
        }
        return new Hunk(oldStart, oldCount, newStart, newCount, section, lines);
    }

    /** "@@ -a,b +c,d @@ section", or a combined diff's "@@@ -a,b -c,d +e,f @@@ section". */
    public String header() {
        if (isCombined())
            return "@@@ " + combinedRanges + " @@@" + section;
        return "@@ -" + range(oldStart, oldCount) + " +" + range(newStart, newCount) + " @@" + section;
    }

    private static String range(int start, int count) {
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }

    /**
     * A change of a hunk: its lines from start to end, removed up to
     * added, added from there, as git writes a change; and the rows, from
     * 0, its first removed line was at and its first added line is at, or
     * with none, the row after where they would be.
     */
    public record Run(int start, int added, int end, int oldRow, int newRow) {
        public int removedCount() {
            return added - start;
        }

        public int addedCount() {
            return end - added;
        }

        /**
         * How many of its removed lines have a line added for them, the
         * k-th removed the k-th added's, as a word diff pairs them.
         */
        public int paired() {
            return Math.min(removedCount(), addedCount());
        }
    }

    /** The old side's row, from 0, its lines start at: with none, the row after the line its start names. */
    public int oldRow() {
        return oldCount == 0 ? oldStart : oldStart - 1;
    }

    /** The new side's row, from 0, its lines start at, as oldRow. */
    public int newRow() {
        return newCount == 0 ? newStart : newStart - 1;
    }

    /** Its changes, in order: each its removed lines, then its added. */
    public List<Run> runs() {
        final List<Run> out = new ArrayList<>();
        int or = oldRow();
        int nr = newRow();
        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).isContext()) {
                or++;
                nr++;
                i++;
                continue;
            }
            final int start = i;
            while (i < lines.size() && lines.get(i).isRemoved())
                i++;
            final int added = i;
            while (i < lines.size() && lines.get(i).isAdded())
                i++;
            final Run run = new Run(start, added, i, or, nr);
            out.add(run);
            or += run.removedCount();
            nr += run.addedCount();
        }
        return out;
    }

    /** Its changes as edits, their rows from 0. */
    public List<Edit> edits() {
        final List<Edit> out = new ArrayList<>();
        for (Run r : runs())
            out.add(new Edit(r.oldRow(), r.removedCount(), r.newRow(), r.addedCount()));
        return out;
    }

    /** Whether any line is added or removed. */
    public boolean hasChanges() {
        for (DiffLine line : lines) {
            if (!line.isContext())
                return true;
        }
        return false;
    }

    /** The hunk as patch text, header first, each line ending in '\n'. */
    public void appendTo(StringBuilder sb) {
        sb.append(header()).append('\n');
        for (DiffLine line : lines) {
            sb.append(line.kind()).append(line.text()).append('\n');
            if (line.noNewline())
                sb.append("\\ No newline at end of file\n");
        }
    }
}
