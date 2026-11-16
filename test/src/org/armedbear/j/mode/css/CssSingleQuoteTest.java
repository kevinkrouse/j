/*
 * CssSingleQuoteTest.java
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

import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.armedbear.j.LexicalContext.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.Buffer;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

/**
 * A single-quoted CSS value closes on a single quote, not a double
 * quote: the old parser closed CSS_STATE_SINGLEQUOTE on {@code "}, so
 * {@code 'x'} stayed open until a double quote appeared, swallowing
 * the rest of the line as a string.
 */
public class CssSingleQuoteTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return CSSMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void closedSingleQuoteLeavesTextNeutral() {
        // content: 'x'; color: red;   -- after the closing quote the
        // rest of the line is NEUTRAL, not swallowed as string.
        EditorHarness h = EditorHarness.create("a { content: 'x'; color: red; }").mode(CSSMode.getMode());
        try {
            assertEquals(STRING, ctx(h, 0, "a { content: '".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "a { content: 'x'".length() + 1));
            assertEquals(NEUTRAL, ctx(h, 0, "a { content: 'x'; color".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void commentAfterClosedSingleQuoteIsComment() {
        EditorHarness h = EditorHarness.create("a { b: 'x'; /* c */ }").mode(CSSMode.getMode());
        try {
            // The comment interior is COMMENT, not string.
            assertEquals(COMMENT, ctx(h, 0, "a { b: 'x'; /* c".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "a { b: 'x'; /* c */ ".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void escapedSingleQuoteStaysInString() {
        // content: 'it\'s' -- the escaped quote does not close the string.
        EditorHarness h = EditorHarness.create("a { content: 'it\\'s'; }").mode(CSSMode.getMode());
        try {
            assertEquals(STRING, ctx(h, 0, "a { content: 'it\\'".length() + 1));
            assertEquals(NEUTRAL, ctx(h, 0, "a { content: 'it\\'s'".length() + 1));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void lineFlagsAgreeWithQuery() {
        // The line ends outside the string, so no SINGLEQUOTE state
        // carries past it: end-of-line queries NEUTRAL.
        EditorHarness h = EditorHarness.create("a { b: 'x'; c: d; }").mode(CSSMode.getMode());
        try {
            Buffer buffer = h.buffer();
            buffer.refreshParse();
            Line line = buffer.getFirstLine();
            assertEquals(NEUTRAL,
                    CSSMode.getMode().lexicalContext(buffer, new Position(line, line.length())));
        }
        finally {
            h.close();
        }
    }
}
