/*
 * ConflictMarkers.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Git's conflict markers, as git writes them: a marker is its character
 * repeated at least seven times (its `conflict-marker-size` default,
 * longer when nested), then a space or the end of the line. A region
 * opens with `&lt;`, may have one `|` (diff3, zdiff3), then `=`, then
 * closes with `&gt;`, each the same length as its opener. One parser for
 * the editor's shading and the status's count.
 */
public final class ConflictMarkers {

    /**
     * A conflict's lines, as indexes into the text's lines. base is -1
     * for none. ours and theirs are the labels after the markers,
     * trimmed; empty when none.
     */
    public record Region(int start, int base, int separator, int end, String ours, String theirs) {
        public boolean hasBase() {
            return base >= 0;
        }
    }

    private ConflictMarkers() {}

    /** The complete regions in lines, in order. */
    public static List<Region> regions(List<String> lines) {
        final List<Region> regions = new ArrayList<>();
        int start = -1;
        int base = -1;
        int separator = -1;
        int len = 0;
        String ours = "";
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (start < 0) {
                len = opener(line);
                if (len > 0) {
                    start = i;
                    base = -1;
                    separator = -1;
                    ours = label(line);
                }
                continue;
            }
            switch (marker(line, len)) {
                case '<' -> {
                    // A second opener abandons the one in hand.
                    start = i;
                    base = -1;
                    separator = -1;
                    ours = label(line);
                }
                case '|' -> {
                    if (separator < 0 && base < 0)
                        base = i;
                }
                case '=' -> {
                    if (separator < 0)
                        separator = i;
                }
                case '>' -> {
                    if (separator >= 0) {
                        regions.add(new Region(start, base, separator, i, ours, label(line)));
                        start = -1;
                    }
                }
                default -> {}
            }
        }
        return regions;
    }

    /** Lines that open a conflict, complete or not: what s's question counts. */
    public static int count(List<String> lines) {
        int n = 0;
        int len = 0;
        boolean open = false;
        for (String line : lines) {
            if (!open) {
                len = opener(line);
                if (len > 0) {
                    n++;
                    open = true;
                }
            } else if (marker(line, len) == '<') {
                n++;
            } else if (marker(line, len) == '>') {
                open = false;
            }
        }
        return n;
    }

    // The length of line's opener, or 0.
    private static int opener(String line) {
        if (line.isEmpty() || line.charAt(0) != '<')
            return 0;
        final int n = run(line, '<');
        return n >= 7 && ends(line, n) ? n : 0;
    }

    // line's marker character, if it is one of length len; 0 otherwise.
    private static char marker(String line, int len) {
        if (line.isEmpty())
            return 0;
        final char c = line.charAt(0);
        if (c != '<' && c != '|' && c != '=' && c != '>')
            return 0;
        if (run(line, c) != len)
            return 0;
        // The separator takes nothing after it; the rest take a label.
        if (c == '=')
            return endsOnly(line, len) ? c : 0;
        return ends(line, len) ? c : 0;
    }

    private static boolean ends(String line, int n) {
        return n == line.length() || line.charAt(n) == ' ' || line.charAt(n) == '\r';
    }

    private static boolean endsOnly(String line, int n) {
        return n == line.length() || (n + 1 == line.length() && line.charAt(n) == '\r');
    }

    // The label after the marker, trimmed; empty when none.
    private static String label(String line) {
        final int n = run(line, line.charAt(0));
        return n < line.length() ? line.substring(n).trim() : "";
    }

    private static int run(String line, char c) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == c)
            n++;
        return n;
    }
}
