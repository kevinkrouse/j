/*
 * ElectricPairs.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.Optional;

/**
 * The electric-pairs decision: what to do when a character is typed,
 * backspaced or entered. The decision is pure, so live typing, vim
 * {@code .} replay and j macro replay get the same answer; the caller
 * acts on it inside one compound edit.
 */
public final class ElectricPairs {
    private ElectricPairs() {}

    /**
     * Character cap on the balance walk, keeping a keystroke O(budget)
     * for a huge prefix with no enclosing closer; on overflow the
     * closer is generated.
     */
    private static final int BALANCE_SCAN_BUDGET = 100_000;

    /**
     * What the user did. {@code c} is used only for INSERT. Backspace
     * is not here: it has its own structural rule, {@link #deleteBoth}.
     */
    public enum Operation {
        INSERT, ENTER
    }

    /** What the caller should do. */
    public enum Action {
        /** Insert the opener and its closer, caret between. */
        INSERT_PAIR,
        /** Insert the opener only; a closer is already waiting. */
        INSERT_OPENER,
        /** Step over the closer under the caret; insert nothing. */
        TYPE_OVER,
        /** Wrap the selection in the pair. */
        WRAP,
        /** Insert nothing; the typed closer would leave one unbalanced. */
        SUPPRESS,
        /** Enter opens a line between a pair. */
        OPEN_LINE,
        /** Insert the typed character unchanged. */
        PLAIN
    }

    /** The decision: an action and, for the pair-carrying actions, the normalized pair. */
    public record Decision(Action action, PairTable.Pair pair) {
        static Decision plain() {
            return new Decision(Action.PLAIN, null);
        }

        static Decision typeOver() {
            return new Decision(Action.TYPE_OVER, null);
        }

        static Decision suppress() {
            return new Decision(Action.SUPPRESS, null);
        }

        static Decision of(Action action, PairTable.Pair pair) {
            return new Decision(action, pair);
        }
    }

    /**
     * The decision for one keystroke. {@code c} is meaningful only for
     * INSERT; {@code pairingSuppressed} is the caller's literal-insertion state.
     */
    public static Decision decide(
            Mode mode,
            Buffer buffer,
            Position dot,
            Operation operation,
            char c,
            boolean hasSelection,
            boolean pairingSuppressed) {
        if (pairingSuppressed || !buffer.getBooleanProperty(Property.ELECTRIC_PAIRS))
            return Decision.plain();
        if (operation == Operation.ENTER)
            return decideEnter(mode, buffer, dot, hasSelection);
        PairTable table = mode.getPairTable();
        if (hasSelection) {
            PairTable.Pair wrapped = table.pairFor(c);
            return wrapped != null ? Decision.of(Action.WRAP, wrapped) : Decision.plain();
        }
        Line line = dot.getLine();
        int offset = dot.getOffset();
        PairTable.Pair pair = table.pairFor(c);
        boolean symmetric = pair != null && pair.opener() == pair.closer();
        // An escaped quote is literal: it neither steps over the closer
        // nor opens a pair. Only a symmetric delimiter is a quote.
        if (symmetric && oddEscapeRunBefore(mode, line, offset))
            return Decision.plain();
        // Type over the closer that is already under the caret. Match the
        // typed closer itself, not pair(c): an opener before a closer
        // must not skip it.
        if (buffer.getBooleanProperty(Property.ELECTRIC_PAIRS_TYPE_OVER)
                && table.isCloser(c)
                && charAt(line, offset) == c)
            return Decision.typeOver();
        if (mode.lexicalContext(buffer, dot) != LexicalContext.NEUTRAL)
            return Decision.plain();
        final boolean balance = buffer.getBooleanProperty(Property.ELECTRIC_PAIRS_BALANCE);
        if (table.isOpener(c)) {
            char before = charBefore(line, offset);
            char at = charAt(line, offset);
            // Only symmetric delimiters are inhibited after a word or
            // themselves; brackets after identifiers (foo(, int[) and
            // nested openers (( still pair. The 0 sentinel is skipped:
            // isIdentifierPart can count it as a word character.
            if (symmetric && before != 0 && (mode.isIdentifierPart(before) || before == c))
                return Decision.plain();
            if (at != 0 && mode.isIdentifierPart(at))
                return Decision.plain();
            if (balance) {
                // A closer waits only if it pops the prefix's unmatched-openers
                // seed to zero: in foo(bar|) the ) belongs to foo's (, so the
                // new ( generates its own closer.
                // The caret line is classified once and shared by the
                // closer count and the forward scan; the seed walk
                // classifies the earlier lines itself, under budget.
                final Optional<LexicalContext[]> ahead =
                        mode.lexicalContexts(buffer, line, line.length());
                int[] steps = new int[1];
                int closersAhead = ahead.isPresent()
                        ? countNeutralClosers(mode, line, offset, pair.closer(), ahead.get())
                        : Integer.MAX_VALUE;
                int seed = prefixBalance(mode, buffer, line, offset,
                        pair.opener(), pair.closer(), closersAhead, steps, BALANCE_SCAN_BUDGET);
                if (steps[0] <= BALANCE_SCAN_BUDGET
                        && closerWaiting(mode, buffer, line, offset,
                                pair.opener(), pair.closer(), seed, ahead))
                    return Decision.of(Action.INSERT_OPENER, pair);
            }
            return Decision.of(Action.INSERT_PAIR, pair);
        }
        if (table.isCloser(c)) {
            // Start past a same-kind closer under the caret: it is the one
            // being typed, not one waiting. Anything else under the caret
            // counts: in (|() the ( at the caret matches the ) after it,
            // so the typed ) is not suppressed.
            int from = charAt(line, offset) == c ? offset + 1 : offset;
            // No prefix seed here: a typed closer is redundant only against
            // a closer waiting at the current nesting level.
            if (balance && closerWaiting(mode, buffer, line, from,
                    table.pairFor(c).opener(), c, 0,
                    mode.lexicalContexts(buffer, line, line.length())))
                return Decision.suppress();
            return Decision.plain();
        }
        return Decision.plain();
    }

    /**
     * Whether Enter at {@code dot} opens a line between an empty pair
     * ({@code {|}}). The caret must sit between a configured asymmetric
     * pair in NEUTRAL context; Enter inside {@code ""} stays a plain split.
     */
    private static Decision decideEnter(Mode mode, Buffer buffer, Position dot, boolean hasSelection) {
        if (hasSelection || !buffer.getBooleanProperty(Property.ELECTRIC_PAIRS_OPEN_LINE))
            return Decision.plain();
        Line line = dot.getLine();
        int offset = dot.getOffset();
        char left = charBefore(line, offset);
        char right = charAt(line, offset);
        if (left == 0 || right == 0 || left == right)
            return Decision.plain();
        PairTable.Pair pair = mode.getPairTable().pairFor(left);
        if (pair == null || pair.opener() != left || pair.closer() != right)
            return Decision.plain();
        if (mode.lexicalContext(buffer, dot) != LexicalContext.NEUTRAL)
            return Decision.plain();
        return Decision.of(Action.OPEN_LINE, pair);
    }

    /**
     * Whether Backspace at {@code offset} deletes the pair on either side
     * of the caret ({@code (|)} or {@code "|"}) as one step.
     *
     * <p>An asymmetric pair needs only NEUTRAL context. A symmetric pair
     * goes by the empty-quote rule alone: the lexical context cannot tell
     * {@code "|"} from a quote inside a longer string.
     */
    static boolean deleteBoth(Mode mode, Buffer buffer, Line line, int offset) {
        if (!buffer.getBooleanProperty(Property.ELECTRIC_PAIRS)
                || !buffer.getBooleanProperty(Property.ELECTRIC_PAIRS_DELETE_PAIRS))
            return false;
        char left = charBefore(line, offset);
        char right = charAt(line, offset);
        if (left == 0 || right == 0)
            return false;
        PairTable.Pair pair = mode.getPairTable().pairFor(left);
        // pairFor matches either end, so check the roles: in )|) the
        // left is a closer, not the opener of an empty pair.
        if (pair == null || pair.opener() != left || pair.closer() != right)
            return false;
        // A quote pair is empty only by the empty-quote rule: between
        // "a"|"b" the state is NEUTRAL, but the left quote closes a
        // string rather than opening an empty one.
        if (pair.opener() == pair.closer())
            return isEmptyQuotePair(mode, buffer, line, offset);
        return mode.lexicalContext(buffer, new Position(line, offset)) == LexicalContext.NEUTRAL;
    }

    /**
     * Whether the caret sits in an empty quote pair ({@code "|"}), whose
     * lexical context reports STRING and would otherwise block the delete.
     *
     * <p>True only when both sides are the same unescaped symmetric quote
     * and the state before the left quote is NEUTRAL — which excludes a
     * quote closing an earlier string, one inside a comment, and one
     * inside a triple-quote delimiter.
     */
    static boolean isEmptyQuotePair(Mode mode, Buffer buffer, Line line, int offset) {
        char left = charBefore(line, offset);
        char right = charAt(line, offset);
        if (left == 0 || left != right)
            return false;
        PairTable.Pair pair = mode.getPairTable().pairFor(left);
        if (pair == null || pair.opener() != pair.closer())
            return false;
        if (oddEscapeRunBefore(mode, line, offset - 1))
            return false;
        return mode.lexicalContext(buffer, new Position(line, offset - 1)) == LexicalContext.NEUTRAL;
    }

    /** Whether the run of the mode's escape character ending just before offset is odd. */
    private static boolean oddEscapeRunBefore(Mode mode, Line line, int offset) {
        char escape = mode.getEscapeChar();
        if (escape == 0)
            return false;
        int count = 0;
        for (int i = offset - 1; i >= 0 && line.charAt(i) == escape; i--)
            count++;
        return count % 2 != 0;
    }

    /**
     * The per-kind nesting the prefix leaves open before {@code offset}:
     * unmatched openers at the caret — the depth the forward scan starts
     * from, so a closer ahead reads as reserved for an enclosing form.
     *
     * <p>Walks backward from the caret counting NEUTRAL openers/closers
     * of this kind. Delimiters in strings/comments don't count, and an
     * escaped delimiter is a character literal, not structure: in Lisp
     * {@code #\(} the {@code (} must not count as an enclosing opener.
     * A closer seen backward is debt an earlier opener must pay: it
     * cancels a pending opener further back rather than the unmatched
     * ones lying between it and the caret, so a stray closer in the
     * prefix (a cut or pasted fragment) does not undercount the seed.
     *
     * <p>The walk stops once the seed exceeds {@code closersAhead}, the
     * NEUTRAL closers on the caret's line: the line-bounded forward scan
     * can then never reach depth zero. It is also bounded by
     * {@code budget}, charged for the work actually done — a whole-line
     * classification before it is requested, and each point-query
     * fallback by its line-prefix cost, since without bulk those queries
     * rescan the prefix. On overflow {@code steps[0]} exceeds
     * {@code budget} and the caller falls back to generating the closer.
     * The caret line's own classification is the caller's, not charged
     * here: it is the same pass the forward scan always made.
     */
    private static int prefixBalance(
            Mode mode,
            Buffer buffer,
            Line line,
            int offset,
            char opener,
            char closer,
            int closersAhead,
            int[] steps,
            int budget) {
        int balance = 0;
        int debt = 0;
        Line l = line;
        int i = offset;
        while (l != null) {
            if (i > l.length())
                i = l.length();
            // Bound the whole-line classification before requesting it:
            // a huge line would otherwise allocate an array larger than
            // the keystroke may touch.
            if (steps[0] + i > budget)
                return balance;
            final Optional<LexicalContext[]> bulk =
                    i == 0 ? Optional.empty() : mode.lexicalContexts(buffer, l, i);
            if (bulk.isPresent())
                steps[0] += i;
            while (i > 0) {
                steps[0]++;
                final char ch = l.charAt(i - 1);
                if ((ch == opener || ch == closer) && !oddEscapeRunBefore(mode, l, i - 1)) {
                    final LexicalContext state =
                            bulk.isPresent() ? bulk.get()[i - 1]
                                    : mode.lexicalContext(buffer, new Position(l, i - 1));
                    // Without bulk the point query rescans the line
                    // prefix, so charge its cost, not the character.
                    if (!bulk.isPresent())
                        steps[0] += i;
                    if (state == LexicalContext.NEUTRAL) {
                        if (ch == opener) {
                            if (debt > 0)
                                debt--;
                            else if (++balance > closersAhead)
                                return balance;
                        } else {
                            debt++;
                        }
                    }
                }
                if (steps[0] > budget)
                    return balance;
                i--;
            }
            l = l.previous();
            i = l == null ? 0 : l.length();
        }
        return balance;
    }

    /**
     * The NEUTRAL {@code closer} characters on {@code line} at or after
     * {@code from}, escaped ones excluded: the upper bound on how far a
     * forward scan seeded at the caret can be popped by closers on that
     * line. Reads the caller's bulk classification, so it is O(line).
     */
    private static int countNeutralClosers(
            Mode mode, Line line, int from, char closer, LexicalContext[] bulk) {
        int count = 0;
        for (int i = from; i < line.length(); i++) {
            if (line.charAt(i) == closer
                    && !oddEscapeRunBefore(mode, line, i)
                    && bulk[i] == LexicalContext.NEUTRAL)
                count++;
        }
        return count;
    }

    /**
     * Whether a closer of this kind sits later on the line at the seeded
     * depth with nothing open before it — already waiting to be typed
     * over. Delimiters in strings/comments don't count: in
     * {@code foo(| "x)"} the {@code )} is in the string, so the opener
     * still generates its own. An escaped delimiter is a character
     * literal: in Lisp {@code #\)} the {@code )} is not a closer
     * waiting to be typed over.
     *
     * <p>With seed one ({@code foo(bar|)}) the closer ahead pops the seed
     * and is consumed by the enclosing form; with seed zero ({@code |)})
     * it is free to close the new opener.
     *
     * <p>The caller's bulk classification is reused; without it the
     * per-delimiter fallback is the same cost the scan always paid.
     */
    private static boolean closerWaiting(
            Mode mode,
            Buffer buffer,
            Line line,
            int from,
            char opener,
            char closer,
            int seed,
            Optional<LexicalContext[]> bulk) {
        int depth = seed;
        for (int i = from; i < line.length(); i++) {
            char ch = line.charAt(i);
            if ((ch != opener && ch != closer) || oddEscapeRunBefore(mode, line, i))
                continue;
            final LexicalContext state =
                    bulk.isPresent() ? bulk.get()[i]
                            : mode.lexicalContext(buffer, new Position(line, i));
            if (state != LexicalContext.NEUTRAL)
                continue;
            if (ch == opener)
                depth++;
            else if (depth == 0)
                return true;
            else
                depth--;
        }
        return false;
    }

    private static char charAt(Line line, int offset) {
        return offset >= 0 && offset < line.length() ? line.charAt(offset) : 0;
    }

    private static char charBefore(Line line, int offset) {
        return offset > 0 && offset <= line.length() ? line.charAt(offset - 1) : 0;
    }
}
