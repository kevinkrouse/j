/*
 * SignsMath.java
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

package org.armedbear.j.vcs.signs;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.armedbear.j.Line;
import org.armedbear.j.Signs;
import org.armedbear.j.Signs.Kind;
import org.armedbear.j.Signs.Sign;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.Diff.Edit;

/**
 * A buffer's signs from its text and its bases: the buffer against what is
 * staged, and what is staged against the last commit, put on the buffer's
 * lines through the first.
 */
public final class SignsMath {
    private SignsMath() {}

    /**
     * Where each line of the index is in the buffer, through the buffer's
     * edits against it; -1 for a line the buffer changed or dropped.
     */
    public static int[] indexToBuffer(int indexSize, List<Edit> unstaged) {
        final int[] map = new int[indexSize];
        int i = 0;
        int shift = 0;
        for (Edit e : unstaged) {
            for (; i < e.oldStart(); i++)
                map[i] = i + shift;
            for (int k = 0; k < e.oldCount(); k++)
                map[i++] = -1;
            shift += e.newCount() - e.oldCount();
        }
        for (; i < indexSize; i++)
            map[i] = i + shift;
        return map;
    }

    /** The buffer's line for the nearest index line at or before i it still has, or -1. */
    public static int atOrBefore(int[] map, int i) {
        for (int k = Math.min(i, map.length - 1); k >= 0; k--)
            if (map[k] >= 0)
                return map[k];
        return -1;
    }

    /**
     * The signs of lines, whose texts are buffer, against index, and index
     * against head (null for a file new to the index).
     */
    public static Signs compute(List<Line> lines, List<String> buffer, List<String> index, List<String> head) {
        return compute(lines, buffer, index, head, null);
    }

    /**
     * The signs of lines, whose texts are buffer, against index, by edits
     * of the two if given, which must turn one into the other (see
     * Diff.isDiffOf), else j's own; and index against head (null for a file
     * new to the index).
     */
    public static Signs compute(
            List<Line> lines,
            List<String> buffer,
            List<String> index,
            List<String> head,
            List<Edit> unstagedEdits) {
        final Map<Line, Sign> signs = new IdentityHashMap<>();
        final TreeSet<Integer> starts = new TreeSet<>();
        final List<Edit> unstaged = unstagedEdits != null ? unstagedEdits : Diff.diff(index, buffer);
        for (Edit e : unstaged)
            mark(lines, signs, starts, e.newStart(), e.newCount(), e.oldCount() == 0, false);
        final int[] map = indexToBuffer(index.size(), unstaged);
        final List<Edit> staged = head == null
                ? index.isEmpty() ? List.of() : List.of(new Edit(0, 0, 0, index.size()))
                : Diff.diff(head, index);
        for (Edit e : staged) {
            if (e.newCount() == 0) {
                // Deleted in the index: below the nearest line before that the
                // buffer still has, or above the first.
                final int at = atOrBefore(map, e.newStart() - 1);
                put(lines, signs, starts, Math.max(0, at), new Sign(Kind.DELETED, true, at < 0));
                continue;
            }
            for (int k = 0; k < e.newCount(); k++) {
                final int at = map[e.newStart() + k];
                if (at >= 0)
                    put(lines, signs, starts, at, new Sign(e.oldCount() == 0 ? Kind.ADDED : Kind.CHANGED, true, false));
            }
        }
        final List<Line> startLines = new ArrayList<>();
        for (int n : starts)
            startLines.add(lines.get(n));
        return new Signs(signs, startLines);
    }

    /** git's edits of index into buffer, if given and still theirs, else j's own. */
    public static List<Edit> unstaged(List<String> index, List<String> buffer, List<Edit> git) {
        return git != null && Diff.isDiffOf(index, buffer, git) ? git : Diff.diff(index, buffer);
    }

    private static void mark(
            List<Line> lines,
            Map<Line, Sign> signs,
            TreeSet<Integer> starts,
            int newStart,
            int newCount,
            boolean added,
            boolean staged) {
        if (lines.isEmpty())
            return;
        if (newCount == 0) {
            // Deleted below the line before, or above the first.
            final boolean above = newStart == 0;
            final int at = above ? 0 : Math.min(newStart - 1, lines.size() - 1);
            signs.put(lines.get(at), new Sign(Kind.DELETED, staged, above));
            starts.add(at);
            return;
        }
        for (int n = newStart; n < newStart + newCount && n < lines.size(); n++)
            signs.put(lines.get(n), new Sign(added ? Kind.ADDED : Kind.CHANGED, staged, false));
        starts.add(Math.min(newStart, lines.size() - 1));
    }

    // A staged sign, where the line has no unstaged one.
    private static void put(List<Line> lines, Map<Line, Sign> signs, TreeSet<Integer> starts, int at, Sign sign) {
        if (at >= lines.size())
            return;
        final Line line = lines.get(at);
        if (signs.containsKey(line))
            return;
        signs.put(line, sign);
        // A run of staged lines starts a hunk where the line above has none.
        if (at == 0 || !signs.containsKey(lines.get(at - 1)))
            starts.add(at);
    }
}
