/*
 * MarkdownDelimiters.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.markdown;

/**
 * Read-only recognition of Markdown's inline delimiter runs: the
 * emphasis markers {@code *}, {@code **}, {@code ***}, {@code _} and
 * the strikethrough {@code ~~}, plus the code-span backticks that
 * shadow them. The formatter's highlighting and the electric-pairs
 * override both consult this, so the two never disagree about what a
 * run is.
 *
 * <p>Every method is pure over {@code (text, from, end)}: no buffer,
 * no editor, no mutable state. The same text gives the same answer,
 * which is what makes the pairing decision replay-safe.
 */
public final class MarkdownDelimiters {
    private MarkdownDelimiters() {}

    /** The emphasis and strikethrough marker characters. */
    public static boolean isMarker(char c) {
        return c == '*' || c == '_' || c == '~';
    }

    /** The length of the run of {@code text.charAt(i)} starting at {@code i}. */
    public static int runLength(String text, int i, int end) {
        final char c = text.charAt(i);
        int j = i;
        while (j < end && text.charAt(j) == c)
            ++j;
        return j - i;
    }

    /**
     * The index of the code-span closer for a run of {@code run}
     * backticks that opened at {@code from}, or -1. A closer is a run
     * of exactly {@code run} backticks. No escapes: CommonMark has
     * none inside a code span.
     */
    public static int findCodeSpanClose(String text, int from, int end, int run) {
        int j = from;
        while (j < end) {
            if (text.charAt(j) == '`') {
                final int n = runLength(text, j, end);
                if (n == run)
                    return j;
                j += n;
            } else {
                ++j;
            }
        }
        return -1;
    }

    /** Whether {@code c} is ASCII punctuation, the class a backslash escapes. */
    public static boolean isAsciiPunctuation(char c) {
        return c < 128 && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    /**
     * Whether the run of {@code run} marker chars at {@code i} can open
     * emphasis: what follows is not white space, and for {@code _} the
     * run does not follow a letter or digit (so {@code snake_case} is
     * not emphasis).
     */
    public static boolean opens(String text, int i, int run, int end) {
        if (i + run >= end || Character.isWhitespace(text.charAt(i + run)))
            return false;
        return text.charAt(i) != '_' || i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
    }

    /**
     * The index of the closer for a run of {@code run} marker {@code c}
     * that opened at {@code from}, or -1. A closer is a run of exactly
     * {@code run} of the same char, preceded by non-whitespace; for
     * {@code _}, not followed by a letter or digit. Code spans are
     * skipped whole; a backslash escapes the next character.
     */
    public static int findEmphasisClose(String text, int from, int end, char c, int run) {
        int j = from;
        while (j < end) {
            final char ch = text.charAt(j);
            if (ch == '\\') {
                j += 2;
            } else if (ch == '`') {
                final int n = runLength(text, j, end);
                final int close = findCodeSpanClose(text, j + n, end, n);
                j = close < 0 ? j + n : close + n;
            } else if (ch == c) {
                final int n = runLength(text, j, end);
                if (n == run
                        && !Character.isWhitespace(text.charAt(j - 1))
                        && (c != '_' || j + n >= end || !Character.isLetterOrDigit(text.charAt(j + n))))
                    return j;
                j += n;
            } else {
                ++j;
            }
        }
        return -1;
    }

    /**
     * The length of the marker run of {@code c} ending exactly at
     * {@code caret} if that run is an <em>unmatched opener</em> — it
     * can open emphasis and nothing between it and {@code end} closes
     * it — or 0 if it is not.
     *
     * <p>{@code prospective} is the character about to be typed just
     * after the run. Opening eligibility is judged with it in place, so
     * an empty opener at the caret ({@code **|}) is classified against
     * the content that is about to follow, without mutating the buffer.
     *
     * <p>A run that already closes an earlier opener is not an opener
     * here, even when its own surroundings would allow opening: the
     * earlier match is recorded first. That is what keeps
     * {@code *b*|} + {@code c} from treating the closing {@code *} as
     * a fresh opener.
     */
    public static int unmatchedOpenerRun(String text, int caret, char c, int end, char prospective) {
        if (!isMarker(c) || caret <= 0 || text.charAt(caret - 1) != c)
            return 0;
        int start = caret;
        while (start > 0 && text.charAt(start - 1) == c)
            --start;
        final int run = caret - start;
        // An odd run of backslashes before the marker escapes its first
        // character, so the marker is literal, not an opener.
        if (oddEscapeRunBefore(text, start))
            return 0;
        // A tilde run longer than two, or a star/underscore run longer
        // than three, is literal.
        if (c == '~' ? run > 2 : run > 3)
            return 0;
        // Opening needs non-whitespace content right after the run; the
        // prospective char stands in for it.
        if (Character.isWhitespace(prospective))
            return 0;
        // An underscore run that follows a letter or digit is intraword,
        // not emphasis.
        if (c == '_' && start > 0 && Character.isLetterOrDigit(text.charAt(start - 1)))
            return 0;
        // A run that closes an earlier opener is matched, not eligible.
        if (closesEarlierOpener(text, start, caret, c, end))
            return 0;
        // A run that already has its own closer later on the line is
        // matched too: generating another closer would double it.
        if (findEmphasisClose(text, caret, end, c, run) >= 0)
            return 0;
        return run;
    }

    /**
     * Whether the run of backslashes ending just before {@code offset}
     * is odd: an odd run escapes the character at {@code offset}, an
     * even one leaves it alone (each backslash escapes the next).
     */
    public static boolean oddEscapeRunBefore(String text, int offset) {
        int n = 0;
        int i = offset - 1;
        while (i >= 0 && text.charAt(i) == '\\') {
            ++n;
            --i;
        }
        return (n & 1) == 1;
    }

    /**
     * Whether the run of {@code c} at {@code [start, caret)} closes an
     * earlier opener of the same kind: some earlier run opens and its
     * closer is exactly this run.
     */
    private static boolean closesEarlierOpener(String text, int start, int caret, char c, int end) {
        // A closer is preceded by non-whitespace.
        if (start == 0 || Character.isWhitespace(text.charAt(start - 1)))
            return false;
        // For '_', a closer is not followed by a letter or digit.
        if (c == '_' && caret < end && Character.isLetterOrDigit(text.charAt(caret)))
            return false;
        int j = 0;
        while (j < start) {
            final char ch = text.charAt(j);
            if (ch == '\\') {
                j += 2;
            } else if (ch == '`') {
                final int n = runLength(text, j, end);
                final int close = findCodeSpanClose(text, j + n, end, n);
                j = close < 0 ? j + n : close + n;
            } else if (ch == c) {
                final int n = runLength(text, j, end);
                if (n <= (c == '~' ? 2 : 3)
                        && opens(text, j, n, end)
                        && findEmphasisClose(text, j + n, end, c, n) == start)
                    return true;
                j += n;
            } else {
                ++j;
            }
        }
        return false;
    }
}
