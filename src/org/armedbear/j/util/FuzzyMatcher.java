/*
 * FuzzyMatcher.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * fzf-style fuzzy matching: each query term is a subsequence of the
 * candidate, scored for word boundaries, camel humps, consecutive runs and
 * gaps. Terms are separated by white space and match in any order. A term
 * with an upper case letter is case-sensitive.
 *
 * <p>Like fzf's, the search keeps one score per cell, so in rare cases it
 * misses the very best alignment by a few points.
 */
public final class FuzzyMatcher {
    private FuzzyMatcher() {}

    // fzf's constants.
    static final int SCORE_MATCH = 16;
    static final int GAP_START = -3;
    static final int GAP_EXTENSION = -1;
    static final int BONUS_BOUNDARY = 8;
    static final int BONUS_BOUNDARY_WHITE = 10;
    static final int BONUS_BOUNDARY_DELIMITER = 9;
    static final int BONUS_CAMEL = BONUS_BOUNDARY + GAP_EXTENSION;
    static final int BONUS_NON_WORD = 8;
    static final int BONUS_CONSECUTIVE = -(GAP_START + GAP_EXTENSION);
    static final int FIRST_CHAR_MULTIPLIER = 2;

    // Per character in a path's last component, so a file's name outranks its directories.
    static final int BONUS_BASENAME = 2;

    // Larger searches are matched greedily rather than by dynamic programming.
    private static final int MAX_OPTIMAL = 1024;
    private static final int MAX_CELLS = 64 * 1024;

    private static final int NONE = Integer.MIN_VALUE / 2;

    /** A parsed query. */
    public static final class Query {
        private final String[] terms;
        private final boolean[] caseSensitive;

        private Query(String[] terms) {
            this.terms = terms;
            caseSensitive = new boolean[terms.length];
            for (int i = 0; i < terms.length; i++) {
                String t = terms[i];
                caseSensitive[i] = !t.equals(t.toLowerCase(Locale.ROOT));
            }
        }

        public static Query parse(String s) {
            if (s == null)
                return new Query(new String[0]);
            String trimmed = s.strip();
            if (trimmed.isEmpty())
                return new Query(new String[0]);
            return new Query(trimmed.split("[\\s\\p{Z}]+"));
        }

        public boolean isEmpty() {
            return terms.length == 0;
        }
    }

    /** A successful match: its score and the indices of the matched characters, ascending. */
    public record Match(int score, int[] positions) {}

    /** A candidate that matched, with its score. */
    public record Ranked<T>(T item, String text, int score) {}

    /** The match of query in candidate, or null if some term isn't in it. An empty query matches with score 0. */
    public static Match match(String candidate, Query query) {
        if (query.isEmpty())
            return new Match(0, new int[0]);
        Scratch scratch = SCRATCH.get();
        int[] bonus = bonuses(candidate, scratch);
        int base = basenameStart(candidate);
        int total = 0;
        int[] positions = new int[0];
        for (int t = 0; t < query.terms.length; t++) {
            Match m = matchTerm(candidate, bonus, base, query.terms[t], query.caseSensitive[t], true, scratch);
            if (m == null)
                return null;
            total += m.score;
            positions = union(positions, m.positions);
        }
        return new Match(total, positions);
    }

    /** The score of query in candidate, or null if it doesn't match. Cheaper than match(). */
    public static Integer score(String candidate, Query query) {
        if (query.isEmpty())
            return 0;
        for (int t = 0; t < query.terms.length; t++) {
            if (!isSubsequence(candidate, query.terms[t], query.caseSensitive[t]))
                return null;
        }
        Scratch scratch = SCRATCH.get();
        int[] bonus = bonuses(candidate, scratch);
        int base = basenameStart(candidate);
        int total = 0;
        for (int t = 0; t < query.terms.length; t++) {
            Match m = matchTerm(candidate, bonus, base, query.terms[t], query.caseSensitive[t], false, scratch);
            if (m == null)
                return null;
            total += m.score;
        }
        return total;
    }

    // Lists at least this long are ranked in parallel chunks.
    private static final int PARALLEL_THRESHOLD = 20_000;

    /**
     * The best limit items that match, best first: by score, then shorter
     * text, then text. An empty query keeps items' own order.
     */
    public static <T> List<Ranked<T>> rank(
        Iterable<? extends T> items,
        Function<? super T, String> text,
        Query query,
        int limit
    ) {
        List<Ranked<T>> result = new ArrayList<>();
        if (limit <= 0)
            return result;
        if (query.isEmpty()) {
            for (T item : items) {
                String s = text.apply(item);
                if (s == null)
                    continue;
                result.add(new Ranked<>(item, s, 0));
                if (result.size() == limit)
                    break;
            }
            return result;
        }
        if (items instanceof List<? extends T> list && list.size() >= PARALLEL_THRESHOLD) {
            final int chunks = Runtime.getRuntime().availableProcessors() * 2;
            final int size = (list.size() + chunks - 1) / chunks;
            IntStream.range(0, chunks)
                .parallel()
                .mapToObj(
                    c -> FuzzyMatcher.<T>best(
                        list.subList(Math.min(list.size(), c * size), Math.min(list.size(), (c + 1) * size)),
                        text,
                        query,
                        limit
                    )
                )
                .forEachOrdered(result::addAll);
            result.sort(ORDER);
            return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
        }
        result.addAll(best(items, text, query, limit));
        result.sort(ORDER);
        return result;
    }

    private static <T> Collection<Ranked<T>> best(
        Iterable<? extends T> items,
        Function<? super T, String> text,
        Query query,
        int limit
    ) {
        // Worst at the head, so it's the one evicted.
        PriorityQueue<Ranked<T>> heap = new PriorityQueue<>(Math.min(limit, 1024) + 1, ORDER.reversed());
        for (T item : items) {
            String s = text.apply(item);
            if (s == null)
                continue;
            Integer score = score(s, query);
            if (score == null)
                continue;
            if (heap.size() == limit && score < heap.peek().score)
                continue;
            Ranked<T> r = new Ranked<>(item, s, score);
            if (heap.size() < limit) {
                heap.add(r);
            } else if (ORDER.compare(r, heap.peek()) < 0) {
                heap.poll();
                heap.add(r);
            }
        }
        return heap;
    }

    private static final Comparator<Ranked<?>> ORDER = (a, b) -> {
        if (a.score != b.score)
            return Integer.compare(b.score, a.score);
        if (a.text.length() != b.text.length())
            return Integer.compare(a.text.length(), b.text.length());
        return a.text.compareTo(b.text);
    };

    private static boolean isSubsequence(String s, String term, boolean caseSensitive) {
        int j = 0;
        final int n = s.length();
        for (int i = 0; i < term.length(); i++) {
            char p = term.charAt(i);
            while (j < n && !same(s.charAt(j), p, caseSensitive))
                j++;
            if (j == n)
                return false;
            j++;
        }
        return true;
    }

    private static boolean same(char c, char p, boolean caseSensitive) {
        if (c == p)
            return true;
        return !caseSensitive && Character.toLowerCase(c) == p;
    }

    private enum CharClass {
        WHITE, DELIMITER, NON_WORD, LOWER, UPPER, LETTER, DIGIT
    }

    private static final CharClass[] ASCII = new CharClass[128];

    static {
        for (char c = 0; c < 128; c++)
            ASCII[c] = classify(c);
    }

    private static CharClass classOf(char c) {
        return c < 128 ? ASCII[c] : classify(c);
    }

    private static CharClass classify(char c) {
        if (Character.isLowerCase(c))
            return CharClass.LOWER;
        if (Character.isUpperCase(c))
            return CharClass.UPPER;
        if (Character.isDigit(c))
            return CharClass.DIGIT;
        if (Character.isLetter(c))
            return CharClass.LETTER;
        if (Character.isWhitespace(c))
            return CharClass.WHITE;
        if (c == '/' || c == '\\' || c == ',' || c == ':' || c == ';' || c == '|')
            return CharClass.DELIMITER;
        return CharClass.NON_WORD;
    }

    private static boolean isWord(CharClass k) {
        return k == CharClass.LOWER || k == CharClass.UPPER || k == CharClass.LETTER || k == CharClass.DIGIT;
    }

    private static final int[][] BONUS;

    static {
        CharClass[] all = CharClass.values();
        BONUS = new int[all.length][all.length];
        for (CharClass prev : all) {
            for (CharClass cur : all)
                BONUS[prev.ordinal()][cur.ordinal()] = bonusFor(prev, cur);
        }
    }

    // Bonus for matching at a position, given the character before it.
    private static int bonusFor(CharClass prev, CharClass cur) {
        if (isWord(cur)) {
            if (prev == CharClass.WHITE)
                return BONUS_BOUNDARY_WHITE;
            if (prev == CharClass.DELIMITER)
                return BONUS_BOUNDARY_DELIMITER;
            if (prev == CharClass.NON_WORD)
                return BONUS_BOUNDARY;
            if (prev == CharClass.LOWER && cur == CharClass.UPPER)
                return BONUS_CAMEL;
            if (prev != CharClass.DIGIT && cur == CharClass.DIGIT)
                return BONUS_CAMEL;
            return 0;
        }
        if (cur == CharClass.WHITE)
            return BONUS_BOUNDARY_WHITE;
        if (cur == CharClass.DELIMITER)
            return BONUS_BOUNDARY_DELIMITER;
        return BONUS_NON_WORD;
    }

    // Reused buffers: rank() calls this for every candidate.
    private static final class Scratch {
        int[] bonus = new int[0];
        int[] h = new int[0];
        int[] runBonus = new int[0];
        int[] from = new int[0];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static int[] grow(int[] a, int size) {
        return a.length >= size ? a : new int[Math.max(size, a.length * 2)];
    }

    // Each position's boundary bonus.
    private static int[] bonuses(String s, Scratch scratch) {
        final int n = s.length();
        int[] bonus = scratch.bonus = grow(scratch.bonus, n);
        CharClass prev = CharClass.WHITE;
        for (int j = 0; j < n; j++) {
            CharClass cur = classOf(s.charAt(j));
            bonus[j] = BONUS[prev.ordinal()][cur.ordinal()];
            prev = cur;
        }
        return bonus;
    }

    // Where the last path component begins, ignoring a trailing separator.
    private static int basenameStart(String s) {
        int end = s.length();
        while (end > 0 && isSeparator(s.charAt(end - 1)))
            end--;
        for (int i = end; i > 0; i--) {
            if (isSeparator(s.charAt(i - 1)))
                return i;
        }
        return 0;
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    private static Match matchTerm(
        String s,
        int[] bonus,
        int base,
        String term,
        boolean caseSensitive,
        boolean wantPositions,
        Scratch scratch
    ) {
        final int n = s.length();
        final int m = term.length();
        if (m > n)
            return null;
        if (n > MAX_OPTIMAL || m * n > MAX_CELLS)
            return greedy(s, bonus, base, term, caseSensitive);
        // Row-major m by n. h: best score with term[i] matched at s[j]; runBonus:
        // the bonus where the consecutive run ending there began; from: where
        // term[i - 1] went.
        int[] h = scratch.h = grow(scratch.h, m * n);
        int[] runBonus = scratch.runBonus = grow(scratch.runBonus, m * n);
        int[] from = wantPositions ? (scratch.from = grow(scratch.from, m * n)) : null;
        // Columns before the leftmost match of term[0], and after the rightmost
        // match of term[m - 1], can't be on any path.
        int first = 0;
        while (first < n && !same(s.charAt(first), term.charAt(0), caseSensitive))
            first++;
        int stop = n - 1;
        while (stop >= first && !same(s.charAt(stop), term.charAt(m - 1), caseSensitive))
            stop--;
        if (stop - first < m - 1)
            return null;
        for (int i = 0; i < m; i++) {
            final char p = term.charAt(i);
            final int row = i * n;
            final int prev = row - n;
            int gapBest = NONE;
            int gapFrom = -1;
            for (int j = first; j <= stop; j++) {
                if (i > 0 && j >= first + 2) {
                    // Jumping from term[i - 1] at k <= j - 2 skips j - k - 1 characters.
                    int extended = gapBest == NONE ? NONE : gapBest + GAP_EXTENSION;
                    int started = h[prev + j - 2] == NONE ? NONE : h[prev + j - 2] + GAP_START;
                    if (started != NONE && started >= extended) {
                        gapBest = started;
                        gapFrom = j - 2;
                    } else {
                        gapBest = extended;
                    }
                }
                h[row + j] = NONE;
                if (j < first + i || !same(s.charAt(j), p, caseSensitive))
                    continue;
                final int extra = j >= base ? BONUS_BASENAME : 0;
                if (i == 0) {
                    h[row + j] = SCORE_MATCH + bonus[j] * FIRST_CHAR_MULTIPLIER + extra;
                    runBonus[row + j] = bonus[j];
                    continue;
                }
                int consecutive = NONE;
                if (j > first && h[prev + j - 1] != NONE) {
                    int b = Math.max(bonus[j], Math.max(BONUS_CONSECUTIVE, runBonus[prev + j - 1]));
                    consecutive = h[prev + j - 1] + SCORE_MATCH + b + extra;
                }
                int gapped = gapBest == NONE ? NONE : gapBest + SCORE_MATCH + bonus[j] + extra;
                if (consecutive != NONE && consecutive >= gapped) {
                    h[row + j] = consecutive;
                    // A boundary inside a run starts a stronger one.
                    int prior = runBonus[prev + j - 1];
                    runBonus[row + j] = bonus[j] >= BONUS_BOUNDARY && bonus[j] > prior ? bonus[j] : prior;
                    if (from != null)
                        from[row + j] = j - 1;
                } else if (gapped != NONE) {
                    h[row + j] = gapped;
                    runBonus[row + j] = bonus[j];
                    if (from != null)
                        from[row + j] = gapFrom;
                }
            }
        }
        final int last = (m - 1) * n;
        int best = NONE;
        int end = -1;
        for (int j = first + m - 1; j <= stop; j++) {
            if (h[last + j] > best) {
                best = h[last + j];
                end = j;
            }
        }
        if (best == NONE)
            return null;
        if (from == null)
            return new Match(best, null);
        int[] positions = new int[m];
        for (int i = m - 1, j = end; i >= 0; i--) {
            positions[i] = j;
            if (i > 0)
                j = from[i * n + j];
        }
        return new Match(best, positions);
    }

    // The rightmost match, so a path's file name is preferred, scored by the same rules.
    private static Match greedy(String s, int[] bonus, int base, String term, boolean caseSensitive) {
        final int m = term.length();
        int[] positions = new int[m];
        int j = s.length() - 1;
        for (int i = m - 1; i >= 0; i--) {
            char p = term.charAt(i);
            while (j >= 0 && !same(s.charAt(j), p, caseSensitive))
                j--;
            if (j < 0)
                return null;
            positions[i] = j--;
        }
        int score = 0;
        int run = 0;
        for (int i = 0; i < m; i++) {
            final int k = positions[i];
            final int extra = k >= base ? BONUS_BASENAME : 0;
            if (i == 0) {
                score += SCORE_MATCH + bonus[k] * FIRST_CHAR_MULTIPLIER + extra;
                run = bonus[k];
            } else if (positions[i - 1] == k - 1) {
                score += SCORE_MATCH + Math.max(bonus[k], Math.max(BONUS_CONSECUTIVE, run)) + extra;
                if (bonus[k] >= BONUS_BOUNDARY && bonus[k] > run)
                    run = bonus[k];
            } else {
                score += SCORE_MATCH + bonus[k] + extra + GAP_START + GAP_EXTENSION * (k - positions[i - 1] - 2);
                run = bonus[k];
            }
        }
        return new Match(score, positions);
    }

    private static int[] union(int[] a, int[] b) {
        int[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return Arrays.stream(all).sorted().distinct().toArray();
    }
}
