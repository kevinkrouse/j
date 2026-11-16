/*
 * CssCarriedStateTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

/**
 * A block comment carries across lines through the entering flags, and
 * the cursor must read them. Single-line fixtures all enter with state
 * zero, so only a line that enters inside a comment distinguishes a
 * cursor that reads {@code line.flags()} from one that starts at zero.
 */
public class CssCarriedStateTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return CSSMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void commentCarriesAcrossLines() {
        // a { /* start
        // end */ b }   -- line 2 enters inside the comment: COMMENT
        // before the */, NEUTRAL after.
        EditorHarness h = EditorHarness.create("a { /* start\nend */ b }").mode(CSSMode.getMode());
        try {
            assertEquals(COMMENT, ctx(h, 1, 0));
            assertEquals(COMMENT, ctx(h, 1, "end".length()));
            assertEquals(NEUTRAL, ctx(h, 1, "end */".length() + 1));
            // The carry is in the line flags.
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & CSSFormatter.CSS_STATE_COMMENT) != 0,
                    "line 2 did not enter in a comment, flags=" + line2.flags());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void stringDoesNotCarryAcrossLines() {
        // A quoted string can't span lines: the entering quote bits are
        // cleared, so line 2 starts NEUTRAL despite the unterminated
        // quote on line 1.
        EditorHarness h = EditorHarness.create("a { b: \"unterminated\nc: d; }").mode(CSSMode.getMode());
        try {
            assertEquals(STRING, ctx(h, 0, "a { b: \"x".length()));
            assertEquals(NEUTRAL, ctx(h, 1, 0));
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & (CSSFormatter.CSS_STATE_QUOTE
                    | CSSFormatter.CSS_STATE_SINGLEQUOTE)) == 0,
                    "a quote carried onto line 2, flags=" + line2.flags());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void bulkCarriesStateLikePoint() {
        // The bulk query on the carried-comment line must report the
        // same COMMENT/NEUTRAL split as the point query.
        EditorHarness h = EditorHarness.create("a { /* start\nend */ b }").mode(CSSMode.getMode());
        try {
            Line line2 = h.buffer().getLine(1);
            int len = line2.length();
            LexicalContext[] bulk = h.buffer().getMode()
                    .lexicalContexts(h.buffer(), line2, len).orElse(null);
            assertEquals(COMMENT, bulk[0]);
            assertEquals(COMMENT, bulk["end".length()]);
            assertEquals(NEUTRAL, bulk["end */".length() + 1]);
        }
        finally {
            h.close();
        }
    }

    @Test
    public void slashInStringDoesNotBreakCarriedComment() {
        // The old parseBuffer ate the character after every slash, so
        // the " after "x"/ was swallowed, string tracking drifted, and
        // line 2 lost its carried comment. The cursor is now the single
        // source of the carried comment bit.
        EditorHarness h = EditorHarness.create(
                "a { content: \"x\"/\"alt\"; /* start / end\n*/ b }").mode(CSSMode.getMode());
        try {
            h.buffer().refreshParse();
            // Line 0 enters neutral; the comment opens mid-line.
            Line line0 = h.buffer().getLine(0);
            assertTrue((line0.flags() & CSSFormatter.CSS_STATE_COMMENT) == 0,
                    "line 0 entered in a comment, flags=" + line0.flags());
            // Line 1 enters inside the comment: the carry survived the
            // slashes on line 0.
            Line line1 = h.buffer().getLine(1);
            assertTrue((line1.flags() & CSSFormatter.CSS_STATE_COMMENT) != 0,
                    "line 1 lost the carried comment, flags=" + line1.flags());
            assertEquals(NEUTRAL, ctx(h, 1, "*/".length() + 1));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void backslashDoesNotHideCommentCloser() {
        // CSS comments have no escapes: /* C:\dir\*/ closes at the */.
        // The old walks checked backslash escapes before the comment
        // state, so \*/ was consumed as an escaped star and the rest
        // of the file read as a comment.
        EditorHarness h = EditorHarness.create("a { /* C:\\dir\\*/ b }").mode(CSSMode.getMode());
        try {
            assertEquals(COMMENT, ctx(h, 0, "a { /* C".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "a { /* C:\\dir\\*/".length() + 1));
            // The comment must not carry into the next line.
            EditorHarness h2 = EditorHarness.create("a { /* C:\\dir\\*/\nb }").mode(CSSMode.getMode());
            try {
                Line line2 = h2.buffer().getLine(1);
                assertEquals(NEUTRAL, ctx(h2, 1, 0));
                assertTrue((line2.flags() & CSSFormatter.CSS_STATE_COMMENT) == 0,
                        "the \\*/ did not close the comment, flags=" + line2.flags());
            }
            finally {
                h2.close();
            }
        }
        finally {
            h.close();
        }
    }
}
