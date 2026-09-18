/*
 * VimWords.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.Mode;
import org.armedbear.j.Position;

/**
 * Where words begin and end, the way vim counts them.
 *
 * Vim sorts characters into three classes and a word is a run of one class:
 * keyword characters, other non-blanks, and whitespace. So {@code foo.bar} is
 * three words -- {@code foo}, {@code .}, {@code bar} -- which is why {@code w}
 * stops on the dot. A WORD, which the capitalised motions use, merges the first
 * two classes, so {@code foo.bar} is one.
 *
 * <p>Which characters are keyword characters is j's own per-language notion,
 * {@link Mode#isIdentifierPart}, which is the same thing vim's
 * {@code 'iskeyword'} expresses.
 */
final class VimWords
{
    static final int BLANK = 0;
    static final int KEYWORD = 1;
    static final int PUNCTUATION = 2;
    /** A line boundary, which stops every word motion. */
    static final int NEWLINE = 3;

    private VimWords()
    {
    }

    static int classOf(char c, Mode mode, boolean bigWord)
    {
        if (c == '\n')
            return NEWLINE;
        if (Character.isWhitespace(c))
            return BLANK;
        if (bigWord)
            return KEYWORD;
        return mode.isIdentifierPart(c) ? KEYWORD : PUNCTUATION;
    }

    private static int classAt(Position pos, Mode mode, boolean bigWord)
    {
        return classOf(pos.getChar(), mode, bigWord);
    }

    /**
     * w and W: to the start of the next word.
     *
     * An empty line counts as a word, which is why a run of blank lines is
     * stepped through one at a time rather than skipped.
     */
    static Position forwardToWordStart(Position from, Mode mode, boolean bigWord)
    {
        final Position pos = new Position(from);
        final int startClass = classAt(pos, mode, bigWord);

        if (startClass == NEWLINE) {
            if (!pos.next())
                return null;
            if (pos.getLine().length() == 0)
                return pos;
        } else {
            // Off the end of the word we are on.
            while (classAt(pos, mode, bigWord) == startClass)
                if (!pos.next())
                    return null;
        }
        return skipToWordStart(pos, mode, bigWord);
    }

    /** b and B: to the start of this word, or of the one before it. */
    static Position backwardToWordStart(Position from, Mode mode, boolean bigWord)
    {
        final Position pos = new Position(from);
        if (!pos.prev())
            return null;
        while (true) {
            final int cls = classAt(pos, mode, bigWord);
            if (cls == NEWLINE) {
                // An empty line is a word; a line end is just a gap.
                if (pos.getLine().length() == 0)
                    return pos;
                if (!pos.prev())
                    return pos;
            } else if (cls == BLANK) {
                if (!pos.prev())
                    return pos;
            } else {
                break;
            }
        }
        return toStartOfCurrentWord(pos, mode, bigWord);
    }

    /** e and E: to the last character of this word, or of the next one. */
    static Position forwardToWordEnd(Position from, Mode mode, boolean bigWord)
    {
        final Position pos = new Position(from);
        if (!pos.next())
            return null;
        if (skipBlanksForward(pos, mode, bigWord) == null)
            return null;
        return toEndOfCurrentWord(pos, mode, bigWord);
    }

    /** ge and gE: back to the last character of the previous word. */
    static Position backwardToWordEnd(Position from, Mode mode, boolean bigWord)
    {
        final Position pos = new Position(from);
        if (!pos.prev())
            return null;
        final int here = classAt(pos, mode, bigWord);
        if (here != BLANK && here != NEWLINE) {
            // Step off the word we are still inside.
            final Position start = toStartOfCurrentWord(new Position(pos), mode,
                                                        bigWord);
            if (start.getOffset() == pos.getOffset()
                && start.getLine() == pos.getLine()) {
                if (!pos.prev())
                    return null;
            } else {
                pos.moveTo(start);
                if (!pos.prev())
                    return null;
            }
        }
        skipBlanksBackward(pos, mode, bigWord);
        return pos;
    }

    // ------------------------------------------------------------ helpers

    /**
     * Forward over blanks and line ends to the start of a word.
     *
     * An empty line stops it: vim counts one as a word, which is what makes w
     * step through a run of blank lines one at a time.
     */
    private static Position skipToWordStart(Position pos, Mode mode,
                                            boolean bigWord)
    {
        while (true) {
            final int cls = classAt(pos, mode, bigWord);
            if (cls == BLANK || cls == NEWLINE) {
                final boolean wasNewline = cls == NEWLINE;
                // Out of buffer: w stops here rather than failing, so that
                // dw on the last word still deletes it.
                if (!pos.next())
                    return pos;
                if (wasNewline && pos.getLine().length() == 0)
                    return pos;
            } else {
                return pos;
            }
        }
    }

    /**
     * Forward over blanks and line ends to any non-blank.
     *
     * Unlike {@link #skipToWordStart} this does not stop on an empty line,
     * because an empty line has no character for e to land on.
     */
    private static Position skipBlanksForward(Position pos, Mode mode,
                                              boolean bigWord)
    {
        while (true) {
            final int cls = classAt(pos, mode, bigWord);
            if (cls != BLANK && cls != NEWLINE)
                return pos;
            if (!pos.next())
                return null;
        }
    }

    private static void skipBlanksBackward(Position pos, Mode mode,
                                           boolean bigWord)
    {
        while (classAt(pos, mode, bigWord) == BLANK)
            if (!pos.prev())
                return;
    }

    private static Position toStartOfCurrentWord(Position pos, Mode mode,
                                                 boolean bigWord)
    {
        final int wordClass = classAt(pos, mode, bigWord);
        final Position scan = new Position(pos);
        while (scan.prev()) {
            if (classOf(scan.getChar(), mode, bigWord) != wordClass) {
                scan.next();
                return scan;
            }
            pos.moveTo(scan);
        }
        return pos;
    }

    private static Position toEndOfCurrentWord(Position pos, Mode mode,
                                               boolean bigWord)
    {
        final int wordClass = classAt(pos, mode, bigWord);
        final Position scan = new Position(pos);
        while (scan.next()) {
            if (classOf(scan.getChar(), mode, bigWord) != wordClass)
                break;
            pos.moveTo(scan);
        }
        return pos;
    }
}
