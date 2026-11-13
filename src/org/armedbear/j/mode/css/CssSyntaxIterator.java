/*
 * CssSyntaxIterator.java
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

package org.armedbear.j.mode.css;

import org.armedbear.j.DefaultSyntaxIterator;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Blanks CSS comments and strings so the bracket match walk skips them.
 * The default syntax iterator returns the line verbatim, so a brace
 * inside {@code /* } *}{ or a quoted string was counted as a match.
 *
 * <p>The blanking is derived from the formatter's cursor bulk query
 * rather than a fresh scan: the cursor is the single source of the
 * comment and string rules, so the iterator cannot drift from it the way
 * a fourth copy of the transitions would. Comment characters become
 * spaces (skipped outright by the walk); string characters become
 * {@code 'X'} (returned, but never a brace, so a quoted {@code }} is
 * not mistaken for a delimiter).
 */
public final class CssSyntaxIterator extends DefaultSyntaxIterator {
    public CssSyntaxIterator(Position pos) {
        super(pos);
    }

    // The caller must have parsed the buffer so the line flags carry the
    // entering comment state.
    @Override
    public char[] hideSyntacticWhitespace(Line line) {
        final String text = line.getText();
        if (text == null)
            return new char[0];
        final int len = text.length();
        final char[] chars = text.toCharArray();
        final LexicalContext[] ctx = new LexicalContext[len + 1];
        new CSSFormatter.Cursor(line, line.flags(), len, ctx).run();
        for (int i = 0; i < len; i++) {
            if (ctx[i] == LexicalContext.COMMENT)
                chars[i] = ' ';
            else if (ctx[i] == LexicalContext.STRING)
                chars[i] = 'X';
        }
        return chars;
    }
}
