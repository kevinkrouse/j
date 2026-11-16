/*
 * SchemeCarriedStateTest.java
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

import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.armedbear.j.LexicalContext.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Position;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Block comments and strings carry across lines via the entering flags.
 * Fixtures put the cursor on a line that enters inside one, where
 * {@code line.flags()} alone makes the context correct; single-line
 * fixtures (entering state zero) would pass even if flags were ignored.
 */
public class SchemeCarriedStateTest {
    @BeforeAll
    public static void registerModes() {
        Editor.getModeList().register(new SchemeModes());
    }

    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return SchemeMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void blockCommentCarriesAcrossLines() {
        // #| start
        // end |# x
        // Line 2 enters inside the comment: COMMENT before |#, NEUTRAL after.
        EditorHarness h = EditorHarness.create("#| start\nend |# x").mode(SchemeMode.getMode());
        try {
            assertEquals(COMMENT, ctx(h, 1, 0));
            assertEquals(COMMENT, ctx(h, 1, "end".length()));
            assertEquals(NEUTRAL, ctx(h, 1, "end |#".length() + 1));
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & org.armedbear.j.Constants.STATE_COMMENT) != 0,
                    "line 2 did not enter in a comment, flags=" + line2.flags());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void stringCarriesAcrossLines() {
        // A Scheme string is multiline:
        // (define s "hello
        // world")
        EditorHarness h = EditorHarness.create(
                "(define s \"hello\nworld\")").mode(SchemeMode.getMode());
        try {
            assertEquals(STRING, ctx(h, 0, "(define s \"hel".length()));
            // Line 2 enters inside the string.
            assertEquals(STRING, ctx(h, 1, 0));
            assertEquals(STRING, ctx(h, 1, "world".length()));
            assertEquals(NEUTRAL, ctx(h, 1, "world\"".length() + 1));
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & org.armedbear.j.Constants.STATE_QUOTE) != 0,
                    "the string did not carry onto line 2, flags=" + line2.flags());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void lineCommentDoesNotCarry() {
        // A ; line comment ends at end of line; line 1 enters NEUTRAL.
        EditorHarness h = EditorHarness.create("; a comment\nx").mode(SchemeMode.getMode());
        try {
            assertEquals(COMMENT, ctx(h, 0, "; a".length()));
            assertEquals(NEUTRAL, ctx(h, 1, 0));
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & org.armedbear.j.Constants.STATE_COMMENT) == 0,
                    "a line comment carried onto line 2, flags=" + line2.flags());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void backslashDoesNotHideBlockCommentCloser() {
        // Scheme block comments have no escapes: #| \|# closes at the |#.
        // The old walks checked the backslash escape before the comment
        // state, so \| was consumed as an escape and the rest read as a comment.
        EditorHarness h = EditorHarness.create("#| C:\\dir\\|# x").mode(SchemeMode.getMode());
        try {
            assertEquals(COMMENT, ctx(h, 0, "#| C".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "#| C:\\dir\\|#".length() + 1));
            // The line does not carry a comment into the next line.
            EditorHarness h2 = EditorHarness.create("#| C:\\dir\\|#\nx").mode(SchemeMode.getMode());
            try {
                Line line2 = h2.buffer().getLine(1);
                assertEquals(NEUTRAL, ctx(h2, 1, 0));
                assertTrue((line2.flags() & org.armedbear.j.Constants.STATE_COMMENT) == 0,
                        "the \\|# did not close the comment, flags=" + line2.flags());
            }
            finally {
                h2.close();
            }
        }
        finally {
            h.close();
        }
    }

    @Test
    public void escapedCharLiteralDoesNotOpenStringOrComment() {
        // In #\" and #\; the backslash escapes the marker: neither opens
        // a string or comment, the trailing text is NEUTRAL.
        EditorHarness h = EditorHarness.create(
                "(display #\\\") (display #\\;) x").mode(SchemeMode.getMode());
        try {
            assertEquals(NEUTRAL, ctx(h, 0, "(display #\\\"".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "(display #\\\") (display #\\;".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "(display #\\\") (display #\\;) x".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void nonClosingPipeIsPreserved() {
        // A | inside a block comment not followed by # appends as itself;
        // the old lookahead overwrote it with the next char, rendering
        // #| \|x |# as #| \xx |#.
        EditorHarness h = EditorHarness.create("#| \\|x |#").mode(SchemeMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            h.buffer().refreshParse();
            LineSegmentList segs = h.buffer().getFormatter().formatLine(line);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < segs.size(); i++)
                text.append(segs.getSegment(i).getText());
            assertEquals("#| \\|x |#", text.toString());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void bulkCarriesStateLikePoint() {
        // Bulk query on a carried-comment line matches the point query's split.
        EditorHarness h = EditorHarness.create("#| start\nend |# x").mode(SchemeMode.getMode());
        try {
            Line line2 = h.buffer().getLine(1);
            int len = line2.length();
            LexicalContext[] bulk = h.buffer().getMode()
                    .lexicalContexts(h.buffer(), line2, len).orElse(null);
            assertEquals(COMMENT, bulk[0]);
            assertEquals(COMMENT, bulk["end".length()]);
            assertEquals(NEUTRAL, bulk["end |#".length() + 1]);
        }
        finally {
            h.close();
        }
    }
}
