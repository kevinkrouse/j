/*
 * Words.java
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
public final class Words
{
    public static final int BLANK = 0;
    public static final int KEYWORD = 1;
    public static final int PUNCTUATION = 2;
    /** A line boundary, which stops every word motion. */
    public static final int NEWLINE = 3;

    private Words()
    {
    }

    public static int classOf(char c, Mode mode, boolean bigWord)
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
    public static Position forwardToWordStart(Position from, Mode mode, boolean bigWord)
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
    public static Position backwardToWordStart(Position from, Mode mode, boolean bigWord)
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
    public static Position forwardToWordEnd(Position from, Mode mode, boolean bigWord)
    {
        final Position pos = new Position(from);
        if (!pos.next())
            return null;
        if (skipBlanksForward(pos, mode, bigWord) == null)
            return null;
        return toEndOfCurrentWord(pos, mode, bigWord);
    }

    /** ge and gE: back to the last character of the previous word. */
    public static Position backwardToWordEnd(Position from, Mode mode, boolean bigWord)
    {
        // Vim's bckend_word: off the word the caret started in, then back
        // over blanks to the end of the one before. A line end is a blank
        // there, so a word never runs on across one; an empty line is a word.
        final Position pos = new Position(from);
        final int startClass = blankIfNewline(classAt(pos, mode, bigWord));
        if (!pos.prev())
            return null;
        if (startClass != BLANK)
            while (blankIfNewline(classAt(pos, mode, bigWord)) == startClass)
                if (!pos.prev())
                    return pos;
        while (blankIfNewline(classAt(pos, mode, bigWord)) == BLANK
               && pos.getLineLength() > 0)
            if (!pos.prev())
                return pos;
        return pos;
    }

    private static int blankIfNewline(int cls)
    {
        return cls == NEWLINE ? BLANK : cls;
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
