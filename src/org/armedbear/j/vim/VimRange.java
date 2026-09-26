/*
 * VimRange.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * The span an operator acts on: half open, like every other range.
 *
 * By the time a range exists the inclusive/exclusive question has already been
 * settled by {@link RangeNormalizer}, so an operator never has to ask which
 * motion produced it.
 */
public final class VimRange
{
    public final Position start;
    public final Position end;
    /** True when the operator should act on whole lines. */
    public final boolean linewise;
    /**
     * The last line a linewise range covers, null for a characterwise one.
     *
     * The end alone cannot say: at the end of the buffer it is the last line
     * at its length, elsewhere the line after at 0, and on an empty last line
     * the two look the same.
     */
    public final Line last;

    /** A characterwise range. */
    VimRange(Position start, Position end)
    {
        this(start, end, null);
    }

    private VimRange(Position start, Position end, Line last)
    {
        this.start = start;
        this.end = end;
        this.linewise = last != null;
        this.last = last;
    }

    /**
     * The whole lines first to last. The end is the start of the line after,
     * so the text takes the newlines with it, or the last line's end when
     * there is no line after.
     */
    static VimRange lines(Line first, Line last)
    {
        final Line after = last.next();
        final Position end = after != null ? new Position(after, 0)
                                           : new Position(last, last.length());
        return new VimRange(new Position(first, 0), end, last);
    }

    public boolean isEmpty()
    {
        return !linewise
            && start.getLine() == end.getLine()
            && start.getOffset() == end.getOffset();
    }

    @Override
    public String toString()
    {
        return (linewise ? "linewise " : "") + start.lineNumber() + ":"
            + start.getOffset() + ".." + end.lineNumber() + ":"
            + end.getOffset();
    }
}
