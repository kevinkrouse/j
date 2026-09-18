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

    VimRange(Position start, Position end, boolean linewise)
    {
        this.start = start;
        this.end = end;
        this.linewise = linewise;
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
