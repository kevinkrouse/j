/*
 * XmlElectricPairsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.xml;

import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.armedbear.j.LexicalContext.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.EditCommands;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

public class XmlElectricPairsTest {
    @Test
    public void doctypeIdentifiersAreStringsInPointAndBulkQueries() {
        String[] declarations = {
            "<!DOCTYPE e SYSTEM \"file:/schema.dtd\">",
            "<!DOCTYPE e SYSTEM 'file:/schema.dtd'>",
            "<!DOCTYPE e SYSTEM \"file:/[schema]>/\">",
            "<!DOCTYPE e PUBLIC \"-//Example//DTD Example//EN\" \"file:/schema.dtd\">",
            "<!DOCTYPE e SYSTEM\n  \"file:/schema.dtd\">",
            "<!DOCTYPE e SYSTEM \"file:/\n  schema.dtd\">" };
        for (String text : declarations) {
            EditorHarness h = EditorHarness.create(text).mode(XmlMode.getMode());
            try {
                h.buffer().refreshParse();
                int quote = 0;
                for (Line line = h.buffer().getFirstLine(); line != null; line = line.next()) {
                    LexicalContext[] bulk =
                            h.buffer().getMode().lexicalContexts(h.buffer(), line, line.length()).orElseThrow();
                    for (int i = 0; i <= line.length(); i++) {
                        LexicalContext expected = quote == 0 ? NEUTRAL : STRING;
                        assertEquals(expected, bulk[i], text + " offset " + i);
                        assertEquals(
                            expected,
                            h.buffer().getMode().lexicalContext(h.buffer(), new Position(line, i)),
                            text + " offset " + i);
                        if (i < line.length()) {
                            char c = line.charAt(i);
                            if (c == quote)
                                quote = 0;
                            else if (quote == 0 && (c == '"' || c == '\''))
                                quote = c;
                        }
                    }
                }
            }
            finally {
                h.close();
            }
        }
    }

    @Test
    public void internalSubsetResumesOrdinaryMarkupContext() {
        EditorHarness h = EditorHarness
                .create("<!DOCTYPE e [\n<!-- internal comment -->\n<!ENTITY msg 'a value'>\n]>\n<e a=\"v\"/>")
                .mode(XmlMode.getMode());
        try {
            h.buffer().refreshParse();
            assertEquals(
                COMMENT,
                h.buffer().getMode().lexicalContext(h.buffer(), new Position(h.buffer().getLine(1), 5)));
            assertEquals(
                STRING,
                h.buffer()
                        .getMode()
                        .lexicalContext(h.buffer(), new Position(h.buffer().getLine(2), "<!ENTITY msg 'a".length())));
            assertEquals(
                NEUTRAL,
                h.buffer().getMode().lexicalContext(h.buffer(), new Position(h.buffer().getLine(3), 2)));
            assertEquals(
                STRING,
                h.buffer().getMode().lexicalContext(h.buffer(), new Position(h.buffer().getLine(4), 6)));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void closingDoctypeQuoteInsertsOnceAndUndoesOnce() {
        String[] prefixes = { "<!DOCTYPE e SYSTEM \"file:/", "<!DOCTYPE e SYSTEM \"file:/\n  schema/" };
        for (String prefix : prefixes) {
            EditorHarness h = EditorHarness.create(prefix).mode(XmlMode.getMode());
            try {
                Line last = h.buffer().getLastLine();
                h.cursor(last.lineNumber(), last.length());
                EditCommands.insertNormalChar(h.editor(), '"');
                assertEquals(prefix + '"', h.value());
                EditCommands.undo(h.editor());
                assertEquals(prefix, h.value());
            }
            finally {
                h.close();
            }
        }
    }

    @Test
    public void quoteInBareDoctypeAutoPairs() {
        // A quote typed in a DOCTYPE that is not already inside a quoted
        // identifier opens a string, so it auto-pairs to "" with the
        // caret between, the same way a neutral quote does.
        EditorHarness h = EditorHarness.create("<!DOCTYPE e ").mode(XmlMode.getMode());
        try {
            h.cursor(0, "<!DOCTYPE e ".length());
            EditCommands.insertNormalChar(h.editor(), '"');
            assertEquals("<!DOCTYPE e \"\"", h.value());
            assertEquals("<!DOCTYPE e ".length() + 1, h.offset());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void emptyAttributeBackspaceDeletesBothQuotes() {
        EditorHarness h = EditorHarness.create("<e a=\"\"/>").mode(XmlMode.getMode());
        try {
            h.cursor(0, 6);
            EditCommands.backspace(h.editor());
            assertEquals("<e a=/>", h.value());
            EditCommands.undo(h.editor());
            assertEquals("<e a=\"\"/>", h.value());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void commentAndCdataQuotesAreLiteral() {
        for (String prefix : new String[] { "<!-- ", "<![CDATA[ " }) {
            EditorHarness h = EditorHarness.create(prefix).mode(XmlMode.getMode());
            try {
                h.cursor(0, prefix.length());
                EditCommands.insertNormalChar(h.editor(), '"');
                assertEquals(prefix + '"', h.value());
                EditCommands.insertNormalChar(h.editor(), '"');
                h.cursor(0, prefix.length() + 1);
                EditCommands.backspace(h.editor());
                assertEquals(prefix + '"', h.value());
            }
            finally {
                h.close();
            }
        }
    }

    @Test
    public void quotePairsImmediatelyAfterCommentAndCdataClosers() {
        for (String text : new String[] { "<!-- x -->", "<![CDATA[ x ]]>" }) {
            EditorHarness h = EditorHarness.create(text).mode(XmlMode.getMode());
            try {
                h.cursor(0, text.length());
                EditCommands.insertNormalChar(h.editor(), '"');
                assertEquals(text + "\"\"", h.value());
            }
            finally {
                h.close();
            }
        }
    }
}
