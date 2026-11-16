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
 * backspaced or entered, given the buffer and the mode's pair table.
 * The function is pure — it reads the buffer and the mode and touches
 * nothing — so the same context gives the same answer on live typing,
 * vim {@code .} replay and j macro replay. The caller acts on the
 * result inside one compound edit.
 */
public final class ElectricPairs {
    private ElectricPairs() {}

    /**
     * The most characters the balance walk will price before giving up
     * and generating the closer. In well-formed code the enclosing form
     * is a few lines away, so the walk stops long before this; the
     * budget only bounds the pathological case of a huge prefix with
     * no enclosing closer, keeping the keystroke O(budget) instead of
     * O(file).
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
     * The decision for one keystroke.
     *
     * @param mode             the buffer's mode
     * @param buffer           the buffer
     * @param dot              the caret position
     * @param operation        INSERT or ENTER
     * @param c                the character typed (INSERT only)
     * @param hasSelection     whether a region is selected
     * @param pairingSuppressed the caller's literal-insertion state
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
            // themselves. Brackets after identifiers (foo(, int[) and
            // nested openers (( should still pair. Skip the 0 sentinel:
            // isIdentifierPart can count it as a word character.
            if (symmetric && before != 0 && (mode.isIdentifierPart(before) || before == c))
                return Decision.plain();
            if (at != 0 && mode.isIdentifierPart(at))
                return Decision.plain();
            if (balance) {
                // A closer is waiting for this opener only when the
                // prefix before the caret leaves nothing open for it:
                // the forward scan, seeded with the unmatched openers
                // already before the caret, finds a closer at depth
                // zero. In foo(bar|) the ) ahead is reserved for foo's
                // (, so the seed is one and the scan never reaches
                // depth zero: this opener must generate its own closer.
                // In |) the seed is zero and the ) is free to close
                // the new opener, so only the opener is inserted.
                int[] steps = new int[1];
                int seed = prefixBalance(mode, buffer, line, offset,
                        pair.opener(), pair.closer(), steps, BALANCE_SCAN_BUDGET);
                if (steps[0] <= BALANCE_SCAN_BUDGET
                        && closerWaiting(mode, buffer, line, offset,
                                pair.opener(), pair.closer(), seed, steps, BALANCE_SCAN_BUDGET))
                    return Decision.of(Action.INSERT_OPENER, pair);
            }
            return Decision.of(Action.INSERT_PAIR, pair);
        }
        if (table.isCloser(c)) {
            // A closer of the same kind under the caret is the one being
            // typed, not one already waiting: with type-over on, rule 4
            // already stepped over it; with type-over off, the typed
            // closer inserts and only a closer later on the line should
            // suppress it, so the scan starts past it. Anything else
            // under the caret, an opener in particular, takes part in
            // the balance: in (|() the ( at the caret matches the ) after
            // it, so the typed ) is not suppressed.
            int from = charAt(line, offset) == c ? offset + 1 : offset;
            // The closer branch keeps the single-line seed: a typed
            // closer is redundant only against a closer waiting at the
            // current nesting level, and the enclosing-form reservation
            // that the opener branch needs does not apply to suppression.
            int[] steps = new int[1];
            if (balance && closerWaiting(mode, buffer, line, from,
                    table.pairFor(c).opener(), c, 0, steps, BALANCE_SCAN_BUDGET))
                return Decision.suppress();
            return Decision.plain();
        }
        return Decision.plain();
    }

    /**
     * Whether Enter at {@code dot} should open a line between an
     * empty pair — {@code {|}} — rather than split the line as
     * ordinary. The caret must sit between a configured asymmetric
     * pair: a quote or other symmetric delimiter does not expand, so
     * Enter inside {@code ""} stays a plain split. The lexical context
     * at the caret must be NEUTRAL; a pair inside a string or comment
     * is content, not a structure to open. The empty-quote Backspace
     * exception does not apply here.
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
     * Whether Backspace at {@code offset} should delete the pair on
     * either side of the caret — {@code (|)} or {@code "|"} — as one
     * step. The left character must be a configured opener and the
     * character at the caret its closer.
     *
     * <p>For an asymmetric pair a NEUTRAL context is enough: {@code
     * (|)} is an empty pair the user just typed. A symmetric pair goes
     * by the empty-quote rule alone, since the lexical context cannot
     * tell {@code "|"} from a quote inside a longer string, or from
     * the quotes between two strings.
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
     * Whether the caret sits between the two characters of an empty
     * quote pair — {@code "|"} — so that Backspace may delete both.
     * The lexical context at the caret reports STRING there, which
     * would otherwise block the delete; this identifies the real
     * empty string.
     *
     * <p>True only when the characters on either side of the caret
     * are the same configured symmetric quote, the left one is
     * unescaped, and the state immediately before that left quote
     * is NEUTRAL. The NEUTRAL-before condition is what excludes a
     * quote that closes an earlier string on the same line, a quote
     * inside a comment, and a quote that is part of a longer
     * delimiter such as a Python triple: in each of those the state
     * before the left character is not NEUTRAL.
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
     * the count of this opener's unmatched openers at the caret, floored
     * at zero. It is the depth the forward scan starts from, so a closer
     * ahead is seen as reserved for an enclosing form rather than as
     * free to close the one being typed.
     *
     * <p>The walk goes backward from the caret on the current line, then
     * over previous lines, counting NEUTRAL openers and closers of this
     * kind. Delimiters inside a string or comment do not count, the same
     * rule the forward scan uses. The walk is bounded by {@code budget}
     * characters; on overflow {@code steps[0]} exceeds the budget and
     * the caller falls back to generating the closer, the safe answer
     * when the prefix is too large to price.
     */
    private static int prefixBalance(
            Mode mode,
            Buffer buffer,
            Line line,
            int offset,
            char opener,
            char closer,
            int[] steps,
            int budget) {
        int balance = 0;
        Line l = line;
        int i = offset;
        while (l != null) {
            final int len = l.length();
            if (i > len)
                i = len;
            final Optional<LexicalContext[]> bulk = mode.lexicalContexts(buffer, l, len);
            while (i > 0) {
                if (steps[0] > budget)
                    return balance;
                final char ch = l.charAt(i - 1);
                if (ch == opener || ch == closer) {
                    final LexicalContext state =
                            bulk.isPresent() ? bulk.get()[i - 1]
                                    : mode.lexicalContext(buffer, new Position(l, i - 1));
                    if (state == LexicalContext.NEUTRAL) {
                        if (ch == opener)
                            balance++;
                        else
                            balance--;
                    }
                }
                steps[0]++;
                i--;
            }
            l = l.previous();
            i = l == null ? 0 : l.length();
        }
        return Math.max(0, balance);
    }

    /**
     * Whether a closer of this kind sits later on the line with nothing
     * open before it at the seeded depth — a closer already waiting to
     * be typed over. Delimiters inside a string or a comment are not
     * code and do not count: in {@code foo(| "x)"} the {@code )} is
     * inside the string, so the opener still gets its own closer.
     *
     * <p>The seed is the unmatched-openers-before-the-caret count from
     * {@link #prefixBalance}. With seed one, as in {@code foo(bar|)},
     * the {@code )} ahead pops the seed to zero and is consumed by the
     * enclosing form, so it is not waiting for the new opener. With
     * seed zero, as in {@code |)}, the {@code )} sits at depth zero and
     * is free to close the new opener.
     *
     * <p>The lexical context for the whole span is taken in one pass.
     * Asking for it per delimiter rescans the line prefix each time,
     * which on a line with thousands of balanced pairs stalls the input
     * path.
     */
    private static boolean closerWaiting(
            Mode mode,
            Buffer buffer,
            Line line,
            int from,
            char opener,
            char closer,
            int seed,
            int[] steps,
            int budget) {
        final Optional<LexicalContext[]> bulk = mode.lexicalContexts(buffer, line, line.length());
        int depth = seed;
        for (int i = from; i < line.length(); i++) {
            if (steps[0] > budget)
                return false;
            char ch = line.charAt(i);
            if (ch != opener && ch != closer)
                continue;
            // The bulk array answers every offset in one pass. Without
            // it, ask only at the delimiters: the fallback predicates
            // are O(offset) each, so a line with no delimiters costs
            // no predicate work at all, and one with few of them pays
            // for few instead of for every position on the line.
            final LexicalContext state =
                    bulk.isPresent() ? bulk.get()[i] : mode.lexicalContext(buffer, new Position(line, i));
            if (state != LexicalContext.NEUTRAL)
                continue;
            if (ch == opener)
                depth++;
            else if (depth == 0)
                return true;
            else
                depth--;
            steps[0]++;
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
