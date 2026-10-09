/*
 * PairMatcher.java
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
 * A mode's matching pairs: brackets and quotes by default, and whatever else
 * the mode pairs, such as C's #if and #endif or XML's start and end tags.
 * One matcher serves findMatchingPair and vim's %, highlightMatchingBracket
 * and rainbowDelimiters. Each end of a pair is a delimiter.
 */
public interface PairMatcher {
    /** One end of a pair: where its text starts and how many characters it covers. */
    record Delimiter(Position pos, int length) {}

    /** The delimiter at a position and the one it pairs with. */
    record Pair(Delimiter at, Delimiter match) {}

    /**
     * The pair a delimiter at pos is one end of, for highlighting; null if
     * there is none, or if its match is more than numLines away (0 for no
     * limit).
     */
    Pair pairAt(Editor editor, Position pos, int numLines);

    /**
     * Where findMatchingPair and vim's % go from pos: the delimiter at pos,
     * or the first after it on its line, and then the start of its match;
     * null if none.
     */
    Position findMatch(Editor editor, Position pos);

    /**
     * Walks a line for rainbowDelimiters. Fills levels, if not null, with the
     * level of each character: the depth a delimiter opens or closes, from 1
     * outermost, {@link DelimiterDepths#UNMATCHED} for a closing one with
     * nothing open, {@link DelimiterDepths#NONE} for everything else. Returns
     * the depth after the line.
     */
    int scan(Buffer buffer, Line line, int depth, int[] levels);
}
