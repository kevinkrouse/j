/*
 * PropertiesSyntaxIterator.java
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

package org.armedbear.j.mode.properties;

import org.armedbear.j.DefaultSyntaxIterator;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Blanks Properties comments so the bracket match walk skips them. The
 * default syntax iterator returns the line verbatim, so a bracket in a
 * comment was counted as a match.
 *
 * <p>The blanking is derived from the formatter's cursor bulk query
 * rather than a fresh scan: the cursor is the single source of the
 * comment rule, so the iterator cannot drift from it. Properties has no
 * string syntax, so only comments are masked; there is no string case to
 * preserve for vim's relative matching.
 */
public final class PropertiesSyntaxIterator extends DefaultSyntaxIterator {
    public PropertiesSyntaxIterator(Position pos) {
        super(pos);
    }

    // The caller must have parsed the buffer so the line flags carry the
    // entering continuation state.
    @Override
    public char[] hideSyntacticWhitespace(Line line) {
        final String text = line.getText();
        if (text == null)
            return new char[0];
        final int len = text.length();
        final char[] chars = text.toCharArray();
        final LexicalContext[] ctx = new LexicalContext[len + 1];
        new PropertiesFormatter.Cursor(line, line.flags(), len, ctx).run();
        for (int i = 0; i < len; i++)
            if (ctx[i] == LexicalContext.COMMENT)
                chars[i] = ' ';
        return chars;
    }
}
