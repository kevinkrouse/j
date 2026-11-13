/*
 * XmlLexicalContextTest.java
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

package org.armedbear.j.mode.xml;

import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.armedbear.j.LexicalContext.OPAQUE;
import static org.armedbear.j.LexicalContext.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

/**
 * The XML lexical query: carried context across lines, the OPAQUE CDATA
 * region, the empty attribute string, the prose apostrophe that must
 * not open a string, and the linear bulk scan bound.
 */
public class XmlLexicalContextTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return XmlMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    @Test
    public void commentCarriesAcrossLines() {
        EditorHarness h = EditorHarness.create(
                "<!-- first line\nsecond line --> tail").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            // Line 0 is inside the comment from the marker on.
            assertEquals(COMMENT, ctx(h, 0, 4));
            // Line 1 is still comment until the --> closer, then NEUTRAL.
            assertEquals(COMMENT, ctx(h, 1, 0));
            assertEquals(COMMENT, ctx(h, 1, "second line --".length()));
            // Just past the --> closer (offset of the space after -->).
            assertEquals(NEUTRAL, ctx(h, 1, "second line -->".length()));
            assertEquals(NEUTRAL, ctx(h, 1, "second line --> tail".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void cdataIsOpaque() {
        EditorHarness h = EditorHarness.create(
                "<![CDATA[ raw <x> ]]> after").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            // Inside the CDATA body is OPAQUE.
            assertEquals(OPAQUE, ctx(h, 0, "<![CDATA[".length()));
            assertEquals(OPAQUE, ctx(h, 0, "<![CDATA[ raw <x> ".length()));
            // Just past the ]]> closer is NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 0, "<![CDATA[ raw <x> ]]>".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "<![CDATA[ raw <x> ]]> after".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void cdataCarriesAcrossLines() {
        EditorHarness h = EditorHarness.create(
                "<![CDATA[ multi\nline body ]]> tail").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            assertEquals(OPAQUE, ctx(h, 1, 0));
            assertEquals(OPAQUE, ctx(h, 1, "line body ]".length()));
            assertEquals(NEUTRAL, ctx(h, 1, "line body ]]>".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void attributeValueIsString() {
        EditorHarness h = EditorHarness.create(
                "<e a=\"hello\" b='world'>t</e>").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            // The interior of the double-quoted value is STRING.
            assertEquals(STRING, ctx(h, 0, "<e a=\"hell".length()));
            // The interior of the single-quoted value is STRING.
            assertEquals(STRING, ctx(h, 0, "<e a=\"hello\" b='worl".length()));
            // The tag text between attributes is NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 0, "<e a=\"hello\" ".length()));
            // Text after the tag is NEUTRAL.
            assertEquals(NEUTRAL, ctx(h, 0, "<e a=\"hello\" b='world'>t".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void emptyAttributeIsString() {
        // <e a="">  the empty value is still a string region; the
        // closing quote reads as inside the string, the caret before
        // the opening quote is neutral.
        EditorHarness h = EditorHarness.create("<e a=\"\">x</e>").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            int open = "<e a=".length();      // the first "
            int close = open + 1;            // the second "
            assertEquals(NEUTRAL, ctx(h, 0, open));
            assertEquals(STRING, ctx(h, 0, close));
            assertEquals(NEUTRAL, ctx(h, 0, close + 1));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void proseApostropheDoesNotOpenString() {
        // A prose apostrophe in text content is NEUTRAL: only a quote
        // reached from inside a tag opens a string.
        EditorHarness h = EditorHarness.create(
                "<e>don't open a string</e>").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            assertEquals(NEUTRAL, ctx(h, 0, "<e>don".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "<e>don't open a string".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void proseApostropheInTextStaysNeutralAcrossLine() {
        EditorHarness h = EditorHarness.create(
                "plain don't text here").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            assertEquals(NEUTRAL, ctx(h, 0, "plain don".length()));
            assertEquals(NEUTRAL, ctx(h, 0, "plain don't text here".length()));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void bulkCarriesStateLikePoint() {
        EditorHarness h = EditorHarness.create(
                "<!-- multi\nline --> tail").mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line2 = h.buffer().getLine(1);
            int len = line2.length();
            LexicalContext[] bulk = XmlMode.getMode()
                    .lexicalContexts(h.buffer(), line2, len).orElse(null);
            // Point and bulk agree at every offset.
            for (int i = 0; i <= len; i++)
                assertEquals(ctx(h, 1, i), bulk[i], "point/bulk differ at " + i);
        }
        finally {
            h.close();
        }
    }

    @Test
    public void bulkWalkIsLinear() {
        String tokens = "<e a=\"value with a long tail of text here\" b='x'>content</e>";
        EditorHarness h = EditorHarness.create(tokens).mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            XmlFormatter f = (XmlFormatter) h.buffer().getFormatter();
            f.scanSteps = 0;
            h.buffer().getMode().lexicalContexts(h.buffer(), line, len);
            assertTrue(f.scanSteps <= len,
                    "bulk scanSteps " + f.scanSteps + " exceeds len " + len);
            assertTrue(f.scanSteps > 0, "bulk made no progress");
        }
        finally {
            h.close();
        }
    }

    @Test
    public void pointQueryStopsAtBoundary() {
        String tokens = "<e a=\"value with a long tail of text here\">content</e>";
        EditorHarness h = EditorHarness.create(tokens).mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            Line line = h.buffer().getFirstLine();
            int boundary = tokens.indexOf("long");
            XmlFormatter f = (XmlFormatter) h.buffer().getFormatter();
            f.scanSteps = 0;
            h.buffer().getMode().lexicalContext(h.buffer(), new Position(line, boundary));
            assertTrue(f.scanSteps <= boundary,
                    "point scanSteps " + f.scanSteps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }
}
