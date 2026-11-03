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

import java.util.List;

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

    /** A combined hunk's parent p's count, from its header. */
    public int parentCount(int p) {
        final String range = combinedRanges.split(" ")[p];
        final int comma = range.indexOf(',');
        return comma < 0 ? 1 : Integer.parseInt(range.substring(comma + 1));
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
