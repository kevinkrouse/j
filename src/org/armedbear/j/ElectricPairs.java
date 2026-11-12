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

    /** What the user did. {@code c} is used only for INSERT. */
    public enum Operation {
        INSERT, BACKSPACE, ENTER
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
        /** Backspace deletes an empty pair. */
        DELETE_BOTH,
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
     * @param operation        INSERT, BACKSPACE or ENTER
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
        // BACKSPACE and ENTER use their own structural rules, which
        // arrive with their call sites; nothing pair-aware applies here
        // until then, so the answer is the plain edit.
        if (operation != Operation.INSERT)
            return Decision.plain();
        if (pairingSuppressed || !buffer.getBooleanProperty(Property.ELECTRIC_PAIRS))
            return Decision.plain();
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
        if (table.isCloser(c) && charAt(line, offset) == c)
            return Decision.typeOver();
        if (mode.lexicalState(buffer, dot) != LexicalState.NEUTRAL)
            return Decision.plain();
        if (table.isOpener(c)) {
            char before = charBefore(line, offset);
            char at = charAt(line, offset);
            // The same-delimiter inhibit is for symmetric delimiters:
            // a quote typed right after itself must not open a pair.
            // An asymmetric opener after itself is just nesting, so
            // (( is allowed. The 0 sentinel means no character, which
            // isIdentifierPart would wrongly count as a word char.
            if (before != 0 && (mode.isIdentifierPart(before) || (symmetric && before == c)))
                return Decision.plain();
            if (at != 0 && mode.isIdentifierPart(at))
                return Decision.plain();
            if (hasUnmatchedCloserAhead(mode, buffer, line, offset, pair.closer(), pair.opener()))
                return Decision.of(Action.INSERT_OPENER, pair);
            return Decision.of(Action.INSERT_PAIR, pair);
        }
        if (table.isCloser(c)) {
            if (hasUnmatchedCloserAhead(mode, buffer, line, offset, c, table.pairFor(c).opener()))
                return Decision.suppress();
            return Decision.plain();
        }
        return Decision.plain();
    }

    /**
     * Whether Backspace at {@code offset} should delete the pair on
     * either side of the caret — {@code (|)} or {@code "|"} — as one
     * step. The left character must be a configured opener and the
     * character at the caret its closer.
     *
     * <p>In a NEUTRAL context that is enough: {@code (|)} is an
     * empty pair the user just typed. In a STRING or COMMENT context
     * the ordinary rule blocks the delete, so only the empty-quote
     * exception lets it through — the {@code "|"} between two quotes
     * of a symmetric pair, which the lexical state alone cannot tell
     * from a quote inside a longer string.
     */
    static boolean deleteBoth(Mode mode, Buffer buffer, Line line, int offset) {
        if (!buffer.getBooleanProperty(Property.ELECTRIC_PAIRS))
            return false;
        char left = charBefore(line, offset);
        char right = charAt(line, offset);
        if (left == 0 || right == 0)
            return false;
        PairTable.Pair pair = mode.getPairTable().pairFor(left);
        if (pair == null || pair.closer() != right)
            return false;
        if (mode.lexicalState(buffer, new Position(line, offset)) == LexicalState.NEUTRAL)
            return true;
        return isEmptyQuotePair(mode, buffer, line, offset);
    }

    /**
     * Whether the caret sits between the two characters of an empty
     * quote pair — {@code "|"} — so that Backspace may delete both.
     * The lexical state at the caret reports STRING there, which
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
        return mode.lexicalState(buffer, new Position(line, offset - 1)) == LexicalState.NEUTRAL;
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
     * Whether a closer of this kind sits later on the line with nothing
     * open before it — a closer already waiting to be typed over.
     * Delimiters inside a string or a comment are not code and do not
     * count: in {@code foo(| "x)"} the {@code )} is inside the string,
     * so the opener still gets its own closer.
     */
    private static boolean hasUnmatchedCloserAhead(
            Mode mode,
            Buffer buffer,
            Line line,
            int from,
            char closer,
            char opener) {
        int depth = 0;
        for (int i = from; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch != opener && ch != closer)
                continue;
            Position pos = new Position(line, i);
            if (mode.lexicalState(buffer, pos) != LexicalState.NEUTRAL)
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
