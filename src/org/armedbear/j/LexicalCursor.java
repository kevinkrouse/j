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
 * Shared bookkeeping for a formatter's lexical cursor: the line text,
 * the boundary to stop at, the optional output array, the walk position,
 * and the helpers that record or test the context over a range.
 *
 * <p>Each formatter keeps its own cursor because token rules differ
 * (Java block comments, Python triple quotes, Shell heredocs, Markdown
 * code spans) but the walk plumbing is the same: a point query passes no
 * output array and stops at {@code upto}; a bulk query passes one and
 * records every offset. Parser-specific state stays in the subclass.
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
     * Whether the point target lies in {@code [from, to]}. In bulk mode
     * the range is recorded as {@code interiorCtx} and false is returned
     * so the walk continues.
     */
    protected boolean covers(int from, int to, LexicalContext interiorCtx) {
        if (out != null) {
            fill(from, Math.min(to, upto), interiorCtx);
            return false;
        }
        return upto >= from && upto <= to;
    }

    /**
     * Whether the point target is inside a token of length {@code len}
     * starting at the cursor: the caret on the first character is before
     * it, on any interior character inside it.
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
