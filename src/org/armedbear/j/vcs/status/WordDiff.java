/*
 * WordDiff.java
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

package org.armedbear.j.vcs.status;

import java.util.ArrayList;
import java.util.List;

/**
 * The words that differ between a removed line and the added line paired
 * with it, for highlighting inside a diff. Words are runs of letters, digits
 * and underscores, runs of white space, and single other characters.
 */
public final class WordDiff {
    // Past this many token pairs, lines are left unrefined.
    private static final int MAX_CELLS = 40_000;
    // Lines sharing less of their text than this are too different to refine.
    private static final double MIN_COMMON = 0.3;

    private WordDiff() {}

    /** The differing ranges of each side, as {start, end} offsets. */
    public record Result(List<int[]> removed, List<int[]> added) {
        static final Result NONE = new Result(List.of(), List.of());
    }

    // The last lines refined: a status buffer lays its lines out again on
    // each key that opens or closes something.
    private static final int CACHED = 2000;
    private static final java.util.Map<String, Result> cache = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Result> eldest) {
            return size() > CACHED;
        }
    };

    public static Result diff(String removed, String added) {
        final String key = removed + '\0' + added;
        synchronized (cache) {
            final Result r = cache.get(key);
            if (r != null)
                return r;
        }
        final Result r = refine(removed, added);
        synchronized (cache) {
            cache.put(key, r);
        }
        return r;
    }

    private static Result refine(String removed, String added) {
        final List<int[]> a = tokens(removed);
        final List<int[]> b = tokens(added);
        final int n = a.size();
        final int m = b.size();
        if (n == 0 || m == 0 || (long) n * m > MAX_CELLS)
            return Result.NONE;
        // lcs[i][j]: the longest common run of tokens a[i..] and b[j..] share.
        final int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lcs[i][j] = same(removed, a.get(i), added, b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        final boolean[] keptA = new boolean[n];
        final boolean[] keptB = new boolean[m];
        int common = 0;
        for (int i = 0, j = 0; i < n && j < m;) {
            if (same(removed, a.get(i), added, b.get(j))) {
                keptA[i] = true;
                keptB[j] = true;
                common += a.get(i)[1] - a.get(i)[0];
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1])
                i++;
            else
                j++;
        }
        final int shorter = Math.min(removed.strip().length(), added.strip().length());
        if (common < MIN_COMMON * shorter)
            return Result.NONE;
        return new Result(ranges(a, keptA), ranges(b, keptB));
    }

    private static boolean same(String s, int[] a, String t, int[] b) {
        return a[1] - a[0] == b[1] - b[0] && s.regionMatches(a[0], t, b[0], a[1] - a[0]);
    }

    // The tokens not kept, adjacent ones joined.
    private static List<int[]> ranges(List<int[]> tokens, boolean[] kept) {
        final List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (kept[i])
                continue;
            final int[] t = tokens.get(i);
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] == t[0])
                ranges.get(ranges.size() - 1)[1] = t[1];
            else
                ranges.add(new int[] { t[0], t[1] });
        }
        return ranges;
    }

    static List<int[]> tokens(String s) {
        final List<int[]> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            final int start = i;
            final char c = s.charAt(i);
            if (isWord(c))
                while (i < s.length() && isWord(s.charAt(i)))
                    i++;
            else if (Character.isWhitespace(c))
                while (i < s.length() && Character.isWhitespace(s.charAt(i)))
                    i++;
            else
                i++;
            tokens.add(new int[] { start, i });
        }
        return tokens;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
