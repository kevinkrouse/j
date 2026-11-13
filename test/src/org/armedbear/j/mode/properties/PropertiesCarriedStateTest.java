/*
 * PropertiesCarriedStateTest.java
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

import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

/**
 * The continuation flag carries across lines through the line flags, and
 * the cursor must read it. A continuation whose text starts with a
 * comment marker is NEUTRAL value text, not a comment; the marker only
 * makes a comment on a line that is not a continuation.
 */
public class PropertiesCarriedStateTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return PropertiesMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void continuationCarriesAcrossLines() {
        // key = value \
        // more value
        // Line 1 ends with an unescaped backslash; line 2 continues it.
        EditorHarness h = EditorHarness.create("key = value \\\nmore value").mode(PropertiesMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0,
                    "line 2 did not continue, flags=" + line2.flags());
            // The continuation is NEUTRAL value text.
            assertEquals(NEUTRAL, ctx(h, 1, 0));
            assertEquals(NEUTRAL, ctx(h, 1, "more value".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void continuationStartingWithMarkerIsNotComment() {
        // cont = value \
        // # this starts with a marker but continues the value
        // The marker on line 2 is value text, not a comment, because
        // line 2 continues line 1.
        EditorHarness h = EditorHarness.create(
                "cont = value \\\n# this starts with a marker").mode(PropertiesMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0,
                    "line 2 did not continue, flags=" + line2.flags());
            // The marker is NEUTRAL, not COMMENT.
            assertEquals(NEUTRAL, ctx(h, 1, 0));
            assertEquals(NEUTRAL, ctx(h, 1, "# this".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void evenBackslashDoesNotContinue() {
        // escaped = ends with double backslash \\
        // next = value
        // The even trailing backslashes are escaped, so line 2 is a
        // fresh key/value line, not a continuation.
        EditorHarness h = EditorHarness.create(
                "escaped = ends with double backslash \\\\\nnext = value").mode(PropertiesMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line2 = h.buffer().getLine(1);
            assertTrue((line2.flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) == 0,
                    "line 2 continued on even backslashes, flags=" + line2.flags());
            // Line 2 is a fresh key/value line: NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 1, 0));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void commentIsCommentFromLineStart() {
        //   # indented comment
        // The whole comment line is COMMENT, matching the highlighting,
        // which paints the leading whitespace as part of the comment.
        EditorHarness h = EditorHarness.create("  # indented comment").mode(PropertiesMode.getMode());
        try {
            assertEquals(COMMENT, ctx(h, 0, 0));
            assertEquals(COMMENT, ctx(h, 0, 1));
            assertEquals(COMMENT, ctx(h, 0, 2));
            assertEquals(COMMENT, ctx(h, 0, "# indented".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void literalQuotesAndApostrophesAreNeutral() {
        // Properties has no string syntax: a quote or apostrophe in
        // ordinary text is NEUTRAL, never a string.
        EditorHarness h = EditorHarness.create("literal = \"quoted\" and don't").mode(PropertiesMode.getMode());
        try {
            assertEquals(NEUTRAL, ctx(h, 0, "literal = \"".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "literal = \"quoted\"".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "literal = \"quoted\" and don't".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void bulkCarriesStateLikePoint() {
        EditorHarness h = EditorHarness.create(
                "cont = value \\\n# this starts with a marker").mode(PropertiesMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line2 = h.buffer().getLine(1);
            int len = line2.length();
            LexicalContext[] bulk = h.buffer().getMode()
                    .lexicalContexts(h.buffer(), line2, len).orElse(null);
            // The continuation marker is NEUTRAL across the whole line.
            assertEquals(NEUTRAL, bulk[0]);
            assertEquals(NEUTRAL, bulk["# this".length()]);
            assertEquals(NEUTRAL, bulk[len]);
        }
        finally {
            h.close();
        }
    }
}
