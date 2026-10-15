/*
 * Diff.java
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A diff of two lists of lines, as the editor's change marks want it as you
 * type: the edits that turn one into the other, in order. Myers' algorithm
 * between the lines the two do not share at the ends, with lines that are
 * in only one side taken out first, as git's histogram diff does, so that a
 * change in a long file is cheap. Past a limit of differences it gives one
 * edit for the rest rather than search on.
 */
public final class Diff {
    // Past this many differences, the middle is one replacement: the search
    // keeps a row per difference, MAX_D squared ints in all.
    private static final int MAX_D = 1024;

    private Diff() {}

    /**
     * Lines oldStart to oldStart + oldCount of the old side, 0-based, become
     * lines newStart to newStart + newCount of the new. A count of 0 is an
     * insertion or deletion before that line.
     */
    public record Edit(int oldStart, int oldCount, int newStart, int newCount) {}

    public static List<Edit> diff(List<String> a, List<String> b) {
        // The ends they share.
        int start = 0;
        while (start < a.size() && start < b.size() && a.get(start).equals(b.get(start)))
            start++;
        int endA = a.size();
        int endB = b.size();
        while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        final List<Edit> edits = new ArrayList<>();
        if (start == endA && start == endB)
            return edits;
        if (start == endA || start == endB) {
            edits.add(new Edit(start, endA - start, start, endB - start));
            return edits;
        }
        // Lines in only one side match nothing, so they are left out of the
        // search and put back as changes.
        final Map<String, int[]> counts = new HashMap<>();
        for (int i = start; i < endA; i++)
            counts.computeIfAbsent(a.get(i), k -> new int[2])[0]++;
        for (int j = start; j < endB; j++)
            counts.computeIfAbsent(b.get(j), k -> new int[2])[1]++;
        final int[] ia = shared(a, start, endA, counts, 1);
        final int[] ib = shared(b, start, endB, counts, 0);
        final boolean[] keptA = new boolean[endA - start];
        final boolean[] keptB = new boolean[endB - start];
        if (!myers(a, ia, b, ib, keptA, keptB, start)) {
            edits.add(new Edit(start, endA - start, start, endB - start));
            return edits;
        }
        // Runs of lines not kept on either side are the edits.
        int i = 0;
        int j = 0;
        while (i < keptA.length || j < keptB.length) {
            if (i < keptA.length && j < keptB.length && keptA[i] && keptB[j]) {
                i++;
                j++;
                continue;
            }
            final int oi = i;
            final int oj = j;
            while (i < keptA.length && !keptA[i])
                i++;
            while (j < keptB.length && !keptB[j])
                j++;
            edits.add(new Edit(start + oi, i - oi, start + oj, j - oj));
        }
        return join(a, b, edits);
    }

    // As git's diff does: a run only removed or only added that can slide
    // over lines the same as its own to meet the edit before or after it
    // joins that edit, so that a line changed among blank ones is a change,
    // not a blank line added beside another removed.
    private static List<Edit> join(List<String> a, List<String> b, List<Edit> edits) {
        final List<Edit> out = new ArrayList<>();
        for (int k = 0; k < edits.size(); k++) {
            final Edit e = edits.get(k);
            final Edit up = out.isEmpty() ? null : slideUp(a, b, e, out.get(out.size() - 1));
            if (up != null) {
                out.set(out.size() - 1, up);
                continue;
            }
            final Edit down = k + 1 < edits.size() ? slideDown(a, b, e, edits.get(k + 1)) : null;
            if (down != null) {
                edits.set(k + 1, down);
                continue;
            }
            out.add(e);
        }
        return out;
    }

    // Edit `e` slid up to just after prev and joined to it; null if it can't slide there.
    private static Edit slideUp(List<String> a, List<String> b, Edit e, Edit prev) {
        if ((e.oldCount() == 0) == (e.newCount() == 0))
            return null;
        final boolean removed = e.newCount() == 0;
        final List<String> side = removed ? a : b;
        final int start = removed ? e.oldStart() : e.newStart();
        final int count = removed ? e.oldCount() : e.newCount();
        final int gap = start - (removed ? prev.oldStart() + prev.oldCount() : prev.newStart() + prev.newCount());
        // Each line it slides over is the one it leaves at its end.
        for (int t = 1; t <= gap; t++)
            if (!side.get(start - t).equals(side.get(start - t + count)))
                return null;
        return new Edit(prev.oldStart(),
                prev.oldCount() + e.oldCount(),
                prev.newStart(),
                prev.newCount() + e.newCount());
    }

    // Edit `e` slid down to just before next and joined to it; null if it can't slide there.
    private static Edit slideDown(List<String> a, List<String> b, Edit e, Edit next) {
        if ((e.oldCount() == 0) == (e.newCount() == 0))
            return null;
        final boolean removed = e.newCount() == 0;
        final List<String> side = removed ? a : b;
        final int start = removed ? e.oldStart() : e.newStart();
        final int count = removed ? e.oldCount() : e.newCount();
        final int gap = (removed ? next.oldStart() : next.newStart()) - (start + count);
        for (int t = 0; t < gap; t++)
            if (!side.get(start + count + t).equals(side.get(start + t)))
                return null;
        return new Edit(next.oldStart() - e.oldCount(),
                next.oldCount() + e.oldCount(),
                next.newStart() - e.newCount(),
                next.newCount() + e.newCount());
    }

    // The indexes of side's lines, from start to end, that the other side
    // has too; other is which count to look at.
    private static int[] shared(List<String> side, int start, int end, Map<String, int[]> counts, int other) {
        int n = 0;
        final int[] out = new int[end - start];
        for (int i = start; i < end; i++)
            if (counts.get(side.get(i))[other] > 0)
                out[n++] = i;
        final int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /**
     * Myers' greedy diff of the lines at `ia` in `a` and at `ib` in `b`, marking the
     * ones in the longest common run as kept. False if it gives up.
     */
    private static boolean myers(
            List<String> a,
            int[] ia,
            List<String> b,
            int[] ib,
            boolean[] keptA,
            boolean[] keptB,
            int start) {
        final int n = ia.length;
        final int m = ib.length;
        if (n == 0 || m == 0)
            return true;
        final int max = Math.min(n + m, MAX_D);
        final int offset = max;
        final int[] v = new int[2 * max + 2];
        final List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= max && found < 0; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || k != d && v[offset + k - 1] < v[offset + k + 1]
                        ? v[offset + k + 1]
                        : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(ia[x]).equals(b.get(ib[y]))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break;
                }
            }
        }
        if (found < 0)
            return false;
        // Back through the trace, marking the diagonals: the lines kept.
        int x = n;
        int y = m;
        for (int d = found; d > 0; d--) {
            final int[] vd = trace.get(d);
            final int k = x - y;
            final int prevK = k == -d || k != d && vd[offset + k - 1] < vd[offset + k + 1] ? k + 1 : k - 1;
            final int prevX = vd[offset + prevK];
            final int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                keptA[ia[x] - start] = true;
                keptB[ib[y] - start] = true;
            }
            x = prevX;
            y = prevY;
        }
        while (x > 0 && y > 0) {
            x--;
            y--;
            keptA[ia[x] - start] = true;
            keptB[ib[y] - start] = true;
        }
        return true;
    }
}
