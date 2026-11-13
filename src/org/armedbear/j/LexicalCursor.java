/*
 * LexicalCursor.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

/**
 * The shared bookkeeping for a formatter's lexical cursor: the line
 * text, the boundary to stop at, the optional output array, the walk
 * position, and the helpers that record or test the context over a
 * range of offsets.
 *
 * <p>Each formatter keeps its own cursor because the token rules differ
 * — Java's block comments, Python's triple quotes, Shell's heredocs,
 * Markdown's code spans — but the plumbing around the walk is the same.
 * A point query passes no output array and stops at {@code upto}; a
 * bulk query passes one and records every offset. The {@code covers}
 * and {@code inside} helpers let a cursor ask, in one place, whether
 * the point target falls inside a token and, in bulk mode, record the
 * token's interior.
 *
 * <p>The cursor's own state (the parser state, the block kind, the
 * carried line) stays in the subclass; this base holds only what every
 * cursor needs to walk and record.
 */
public abstract class LexicalCursor {
    protected final String text;
    protected final int limit;
    protected final int upto;
    protected final LexicalContext[] out;
    protected int i;

    protected LexicalCursor(Line line, int upto, LexicalContext[] out) {
        this.text = line.getText();
        this.limit = text.length();
        this.upto = upto;
        this.out = out;
    }

    /**
     * Whether the point target falls inside the inclusive range
     * {@code [from, to]}. In bulk mode the range is recorded as
     * {@code interiorCtx} and the walk continues, so this returns
     * false; in point mode it returns whether the boundary lies in the
     * range, letting the caller stop there.
     */
    protected boolean covers(int from, int to, LexicalContext interiorCtx) {
        if (out != null) {
            fill(from, Math.min(to, upto), interiorCtx);
            return false;
        }
        return upto >= from && upto <= to;
    }

    /**
     * Whether the point target falls inside a token of length
     * {@code len} starting at the cursor, whose interior carries
     * {@code interiorCtx}. Equivalent to {@code covers(i + 1, i + len
     * - 1, interiorCtx)}: the caret on the token's first character is
     * before it, the caret on any interior character is inside it.
     */
    protected boolean inside(int len, LexicalContext interiorCtx) {
        return covers(i + 1, i + len - 1, interiorCtx);
    }

    /** Records {@code value} over the inclusive range in bulk mode. */
    protected void fill(int from, int to, LexicalContext value) {
        for (int j = from; j <= to; j++)
            out[j] = value;
    }
}
