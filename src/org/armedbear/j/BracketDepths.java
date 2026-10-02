/*
 * BracketDepths.java
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
 * How deeply each bracket of a buffer is nested, for rainbowDelimiters.
 *
 * The brackets are those the mode's syntax iterator leaves showing, so the
 * ones in strings and comments are skipped exactly as findMatchingChar skips
 * them. The depth at each line's start is kept, as Emacs' syntax-ppss keeps
 * its parse states, and computed from the top no further than a window asks.
 * Any change to the buffer, or a reparse of it, drops them all: the mode's
 * own parse already rescans the whole buffer after every change.
 */
public final class BracketDepths
{
    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";

    /** The level of a closing bracket nothing opened. */
    public static final int UNMATCHED = 0;

    /** Not a bracket, or one in a string or comment. */
    public static final int NONE = -1;

    private final Buffer buffer;

    private int modCount = -1;
    private boolean parsed;

    // depths[i] is the depth at the start of line i, for i <= known.
    private int[] depths = new int[64];
    private int known;
    private Line knownLine;

    public BracketDepths(Buffer buffer)
    {
        this.buffer = buffer;
    }

    /**
     * The level of each character of the line: the depth an opening bracket
     * opens or a closing one closes, from 1 outermost, UNMATCHED for a
     * closing bracket with nothing open, and NONE for everything else.
     */
    public synchronized int[] levels(Line line)
    {
        final int[] levels = new int[line.length()];
        final char[] chars = codeChars(line);
        int depth = depthAt(line);
        for (int i = 0; i < levels.length; i++) {
            final char c = i < chars.length ? chars[i] : ' ';
            if (OPENERS.indexOf(c) >= 0)
                levels[i] = ++depth;
            else if (CLOSERS.indexOf(c) >= 0)
                levels[i] = depth > 0 ? depth-- : UNMATCHED;
            else
                levels[i] = NONE;
        }
        return levels;
    }

    /** The depth at the start of the line. */
    public synchronized int depthAt(Line line)
    {
        validate();
        final int target = line.lineNumber();
        if (target < 0)
            return 0;
        if (knownLine == null) {
            knownLine = buffer.getFirstLine();
            known = 0;
            depths[0] = 0;
        }
        while (known < target && knownLine != null) {
            final Line next = knownLine.next();
            if (next == null)
                break;
            if (known + 1 >= depths.length) {
                final int[] grown = new int[Math.max(depths.length * 2,
                                                     target + 1)];
                System.arraycopy(depths, 0, grown, 0, known + 1);
                depths = grown;
            }
            depths[known + 1] = depthAfter(knownLine, depths[known]);
            knownLine = next;
            ++known;
        }
        return target <= known ? depths[target] : depths[known];
    }

    private void validate()
    {
        final int mc = buffer.getModCount();
        final boolean p = !buffer.needsParsing();
        if (mc != modCount || p != parsed) {
            // Line flags, which say where strings and comments run on from
            // the line before, are only right once the buffer is parsed.
            modCount = mc;
            parsed = p;
            knownLine = null;
            known = 0;
        }
    }

    private char[] codeChars(Line line)
    {
        if (line.getText() == null)
            return new char[0];
        return buffer.getMode().getSyntaxIterator(null)
            .hideSyntacticWhitespace(line);
    }

    private int depthAfter(Line line, int depth)
    {
        for (char c : codeChars(line)) {
            if (OPENERS.indexOf(c) >= 0)
                ++depth;
            else if (CLOSERS.indexOf(c) >= 0 && depth > 0)
                --depth;
        }
        return depth;
    }
}
