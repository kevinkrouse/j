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
 * header has them; a side with no lines starts at the line before.
 */
public record Hunk(int oldStart, int oldCount, int newStart, int newCount, String section, List<DiffLine> lines) {
    public Hunk {
        lines = List.copyOf(lines);
        section = section == null ? "" : section;
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

    /** "@@ -a,b +c,d @@ section". */
    public String header() {
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
