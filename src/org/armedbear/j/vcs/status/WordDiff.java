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
import java.util.Arrays;
import java.util.List;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.Diff.Edit;

/**
 * The words that differ between a removed line and the added line paired
 * with it, for highlighting inside a diff. Words are runs of letters, digits
 * and underscores, runs of white space, and single other characters.
 */
public final class WordDiff {
    // Lines sharing less of their text than this are too different to refine.
    private static final double MIN_COMMON = 0.3;
    // Past this many token pairs, as long minified lines have, a pair is not
    // refined: too slow on the event thread.
    private static final long MAX_CELLS = 40_000;

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
        final Result r = refine(removed, added, tokens(removed), tokens(added));
        synchronized (cache) {
            cache.put(key, r);
        }
        return r;
    }

    /** As diff, character by character. */
    public static Result chars(String removed, String added) {
        return refine(removed, added, chars(removed), chars(added));
    }

    private static Result refine(String removed, String added, List<int[]> a, List<int[]> b) {
        if (a.isEmpty() || b.isEmpty() || (long) a.size() * b.size() > MAX_CELLS)
            return Result.NONE;
        final boolean[] keptA = new boolean[a.size()];
        final boolean[] keptB = new boolean[b.size()];
        Arrays.fill(keptA, true);
        Arrays.fill(keptB, true);
        for (Edit e : Diff.diff(texts(removed, a), texts(added, b))) {
            Arrays.fill(keptA, e.oldStart(), e.oldStart() + e.oldCount(), false);
            Arrays.fill(keptB, e.newStart(), e.newStart() + e.newCount(), false);
        }
        int common = 0;
        for (int i = 0; i < a.size(); i++)
            if (keptA[i])
                common += a.get(i)[1] - a.get(i)[0];
        final int shorter = Math.min(removed.strip().length(), added.strip().length());
        if (common < MIN_COMMON * shorter)
            return Result.NONE;
        return new Result(ranges(a, keptA), ranges(b, keptB));
    }

    private static List<String> texts(String s, List<int[]> tokens) {
        final List<String> texts = new ArrayList<>(tokens.size());
        for (int[] t : tokens)
            texts.add(s.substring(t[0], t[1]));
        return texts;
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

    private static List<int[]> chars(String s) {
        final List<int[]> chars = new ArrayList<>(s.length());
        for (int i = 0; i < s.length(); i++)
            chars.add(new int[] { i, i + 1 });
        return chars;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
