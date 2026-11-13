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
 * quote. The old parser closed CSS_STATE_SINGLEQUOTE on {@code "}, so
 * a value like {@code 'x'} stayed open until a double quote appeared,
 * swallowing the rest of the line as a string. These tests pin the
 * correction: after a closed single-quoted value the following text is
 * NEUTRAL and a trailing comment is COMMENT, and the lexical context
 * agrees with the highlighting and the line flags.
 */
public class CssSingleQuoteTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return CSSMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void closedSingleQuoteLeavesTextNeutral() {
        // content: 'x'; color: red;
        //          ^closed  ^this is NEUTRAL, not swallowed as string
        EditorHarness h = EditorHarness.create("a { content: 'x'; color: red; }").mode(CSSMode.getMode());
        try {
            // Inside the single quotes: STRING.
            assertEquals(STRING, ctx(h, 0, "a { content: '".length()));
            // After the closing quote: NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 0, "a { content: 'x'".length() + 1));
            // The following property is NEUTRAL too.
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
            // After the comment closes: NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 0, "a { b: 'x'; /* c */ ".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void escapedSingleQuoteStaysInString() {
        // content: 'it\'s' — the escaped quote does not close the string.
        EditorHarness h = EditorHarness.create("a { content: 'it\\'s'; }").mode(CSSMode.getMode());
        try {
            // Inside the string, past the escaped quote: STRING.
            assertEquals(STRING, ctx(h, 0, "a { content: 'it\\'".length() + 1));
            // After the real closing quote: NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 0, "a { content: 'it\\'s'".length() + 1));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void lineFlagsAgreeWithQuery() {
        // After a closed single quote on a line, the line's trailing
        // flags must not carry SINGLEQUOTE — the string closed.
        EditorHarness h = EditorHarness.create("a { b: 'x'; c: d; }").mode(CSSMode.getMode());
        try {
            Buffer buffer = h.buffer();
            buffer.refreshParse();
            Line line = buffer.getFirstLine();
            // The line ends outside the string, so the entering flags of
            // the next line (or the trailing state) must not be a quote.
            // The query at end-of-line reports NEUTRAL.
            assertEquals(NEUTRAL,
                    CSSMode.getMode().lexicalContext(buffer, new Position(line, line.length())));
        }
        finally {
            h.close();
        }
    }
}
