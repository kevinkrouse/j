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
 * the cursor must read them. Every single-line fixture has an entering
 * state of zero, so a cursor that ignored {@code line.flags()} and
 * started from zero would pass them all. These fixtures put the cursor
 * on a line that enters inside a comment, where the entering state is
 * the only thing that makes the context correct: starting from zero
 * would read the carried comment as ordinary text.
 */
public class CssCarriedStateTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return CSSMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void commentCarriesAcrossLines() {
        // a { /* start
        // end */ b }
        // Line 1 opens the comment; line 2 enters inside it. The text
        // before the */ on line 2 is COMMENT, and after it is NEUTRAL.
        EditorHarness h = EditorHarness.create("a { /* start\nend */ b }").mode(CSSMode.getMode());
        try {
            // The carried comment: line 2's start is still COMMENT.
            assertEquals(COMMENT, ctx(h, 1, 0));
            assertEquals(COMMENT, ctx(h, 1, "end".length()));
            // Just past the */ closer: NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 1, "end */".length() + 1));
            // The line really entered in a comment: the flags carry it.
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
        // A quoted string cannot span lines: the entering quote bits are
        // cleared, so line 2 starts NEUTRAL even though line 1 left an
        // unterminated quote.
        EditorHarness h = EditorHarness.create("a { b: \"unterminated\nc: d; }").mode(CSSMode.getMode());
        try {
            // Line 1 inside the string: STRING.
            assertEquals(STRING, ctx(h, 0, "a { b: \"x".length()));
            // Line 2 enters NEUTRAL: the quote did not carry.
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
        // The reviewer's finding-2 fixture: a slash inside a string on
        // line 1, then a comment with a slash inside it. The old
        // parseBuffer / branch ate the character after every slash
        // unconditionally, so the " after the / was swallowed, the
        // string tracking drifted, and line 2 lost its carried comment
        // state. The cursor is now the single source of the carried
        // comment bit, so line 2 enters in the comment and reads it.
        EditorHarness h = EditorHarness.create(
                "a { content: \"x\"/\"alt\"; /* start / end\n*/ b }").mode(CSSMode.getMode());
        try {
            h.buffer().refreshParse();
            // Line 0 enters neutral (the comment opens mid-line).
            Line line0 = h.buffer().getLine(0);
            assertTrue((line0.flags() & CSSFormatter.CSS_STATE_COMMENT) == 0,
                    "line 0 entered in a comment, flags=" + line0.flags());
            // Line 1 enters inside the comment: the carry survived the
            // slashes on line 0.
            Line line1 = h.buffer().getLine(1);
            assertTrue((line1.flags() & CSSFormatter.CSS_STATE_COMMENT) != 0,
                    "line 1 lost the carried comment, flags=" + line1.flags());
            // The */ on line 1 closes it: after it, NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 1, "*/".length() + 1));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void backslashDoesNotHideCommentCloser() {
        // A CSS comment has no escapes, so a backslash inside one is
        // literal and must not hide the */ closer. /* C:\dir\*/ closes
        // at the */; the text after it is NEUTRAL, not a comment. The
        // old walks checked the backslash escape before the comment
        // state, so the \*/ was consumed as an escaped star and the
        // rest of the file read as a comment, disabling pairing and %
        // matching.
        EditorHarness h = EditorHarness.create("a { /* C:\\dir\\*/ b }").mode(CSSMode.getMode());
        try {
            // Inside the comment: COMMENT.
            assertEquals(COMMENT, ctx(h, 0, "a { /* C".length()));
            // Just past the */ closer: NEUTRAL, not swallowed.
            assertEquals(NEUTRAL, ctx(h, 0, "a { /* C:\\dir\\*/".length() + 1));
            // The line does not carry a comment into the next line.
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
