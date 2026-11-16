/*
 * SchemeSyntaxIterator.java
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

package org.armedbear.j.mode.scheme;

import org.armedbear.j.DefaultSyntaxIterator;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Blanks Scheme comments and strings so the bracket match walk skips them;
 * the default iterator returns the line verbatim, so a paren inside
 * {@code #| ) |#} or a quoted string was counted as a match. Blanking
 * comes from the formatter's cursor bulk query, not a fresh scan, so the
 * iterator can't drift from the comment/string rules. Comment characters
 * become spaces; string characters become {@code 'X'} — returned, but
 * never a paren.
 *
 * <p>Vim's {@code %} matches relative to its start: from inside a string
 * it must still find brackets there, so when the iterator starts in a
 * string the string stays transparent and vim's own quote-parity check
 * ({@code isSkippedByVim}) decides which brackets count. Comments are
 * masked regardless. Non-vim matching never starts inside a string (the
 * pairing guard returns null first) and rainbow highlighting has no start
 * position, so both keep full masking.
 */
public final class SchemeSyntaxIterator extends DefaultSyntaxIterator {
    private final boolean startInString;

    public SchemeSyntaxIterator(Position pos) {
        super(pos);
        this.startInString = pos != null && isStringAt(pos);
    }

    private static boolean isStringAt(Position pos) {
        final Line line = pos.getLine();
        if (line == null || line.getText() == null)
            return false;
        final int upto = Math.min(pos.getOffset(), line.length());
        final LexicalContext[] ctx = new LexicalContext[upto + 1];
        new SchemeFormatter.Cursor(line, line.flags(), upto, ctx).run();
        return ctx[upto] == LexicalContext.STRING;
    }

    // The caller must have parsed the buffer so the line flags carry the
    // entering comment or quote state.
    @Override
    public char[] hideSyntacticWhitespace(Line line) {
        final String text = line.getText();
        if (text == null)
            return new char[0];
        final int len = text.length();
        final char[] chars = text.toCharArray();
        final LexicalContext[] ctx = new LexicalContext[len + 1];
        new SchemeFormatter.Cursor(line, line.flags(), len, ctx).run();
        for (int i = 0; i < len; i++) {
            if (ctx[i] == LexicalContext.COMMENT)
                chars[i] = ' ';
            else if (ctx[i] == LexicalContext.STRING && !startInString)
                chars[i] = 'X';
        }
        return chars;
    }
}
