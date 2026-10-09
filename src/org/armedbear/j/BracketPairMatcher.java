/*
 * BracketPairMatcher.java
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
 * The pairs every mode has: ( [ { with ) ] }, and a string's quotes. The
 * brackets are those the mode's syntax iterator leaves showing, so the ones
 * in strings and comments are skipped.
 */
public class BracketPairMatcher implements PairMatcher {
    public static final BracketPairMatcher INSTANCE = new BracketPairMatcher();

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";

    protected BracketPairMatcher() {}

    protected static boolean isBracket(char c) {
        return OPENERS.indexOf(c) >= 0 || CLOSERS.indexOf(c) >= 0;
    }

    @Override
    public Pair pairAt(Editor editor, Position pos, int numLines) {
        final char c = pos.getChar();
        final Position match = isBracket(c)
                ? CaretCommands.findMatchInternal(editor, pos, numLines)
                : CaretCommands.findMatchingQuote(editor, pos, numLines);
        if (match == null)
            return null;
        return new Pair(new Delimiter(pos.copy(), 1), new Delimiter(match, 1));
    }

    /**
     * As vim's % does: the first bracket at or after pos on its line, and
     * the bracket it pairs with by vim's rules for strings and escapes.
     */
    @Override
    public Position findMatch(Editor editor, Position pos) {
        final Line line = pos.getLine();
        for (int i = pos.getOffset(); i < line.length(); i++) {
            if (isBracket(line.charAt(i)))
                return CaretCommands.findMatchInternal(editor, new Position(line, i), 0, true);
        }
        return null;
    }

    @Override
    public int scan(Buffer buffer, Line line, int depth, int[] levels) {
        final char[] chars = codeChars(buffer, line);
        for (int i = 0; i < line.length(); i++) {
            final char c = i < chars.length ? chars[i] : ' ';
            final int level;
            if (OPENERS.indexOf(c) >= 0)
                level = ++depth;
            else if (CLOSERS.indexOf(c) >= 0)
                level = depth > 0 ? depth-- : DelimiterDepths.UNMATCHED;
            else
                level = DelimiterDepths.NONE;
            if (levels != null)
                levels[i] = level;
        }
        return depth;
    }

    // The line with its strings and comments blanked out.
    private static char[] codeChars(Buffer buffer, Line line) {
        if (line.getText() == null)
            return new char[0];
        return buffer.getMode().getSyntaxIterator(null).hideSyntacticWhitespace(line);
    }
}
