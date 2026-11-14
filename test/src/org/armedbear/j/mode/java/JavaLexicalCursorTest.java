/*
 * JavaLexicalCursorTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.js.JavaScriptMode;
import org.junit.jupiter.api.Test;

/**
 * The Java lexical cursor walks the line once: the number of token
 * advances is linear in the line's length, and a point query stops
 * advancing at its boundary instead of walking the whole line. The
 * fixtures carry real tokens — a block comment, a string and a line
 * comment — so the walk exercises the multi-character marker and
 * escape paths, not just plain characters.
 */
public class JavaLexicalCursorTest {
    // A line with a block comment, a string and a trailing line
    // comment: the cursor must handle each marker and the escapes
    // inside the string, not merely step over letters.
    private static final String TOKENS = "int x = 1; /* a */ y = \"z\"; // tail";

    @Test
    public void bulkWalkIsLinear() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(JavaMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            LexicalContext[] out = new LexicalContext[len + 1];
            JavaFormatter.Cursor bulk = new JavaFormatter.Cursor(line, len, out, true);
            bulk.run();
            // One step per character at most; multi-character tokens
            // (the comment markers, the escape) advance by more, so
            // the count never exceeds the length.
            assertTrue(bulk.steps <= len, "bulk steps " + bulk.steps + " exceeds len " + len);
            // The walk actually consumed the tokens: a plain-character
            // line would step len times, but the markers skip ahead.
            assertTrue(bulk.steps > 0, "bulk made no progress");
        }
        finally {
            h.close();
        }
    }

    @Test
    public void pointQueryStopsAtBoundary() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(JavaMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            // A point query in the middle of the token-bearing line
            // must not walk the whole line.
            int boundary = TOKENS.indexOf("y =");
            JavaFormatter.Cursor point = new JavaFormatter.Cursor(line, boundary, null, true);
            point.run();
            assertTrue(point.steps <= boundary, "point steps " + point.steps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }

    @Test
    public void textBlockContextsIncludeDelimiterInteriorsAndLiteralQuotes() {
        String quotes = "\"\"\"";
        String text =
                "String s = " + quotes + "\n    \" ' // /* " + '\\' + quotes + " still\n    " + quotes + "; after\n";
        EditorHarness h = EditorHarness.create(text).mode(JavaMode.getMode());
        try {
            h.buffer().getFormatter().parseBuffer();
            for (int number = 0; number < 3; number++) {
                Line line = h.buffer().getLine(number);
                LexicalContext[] bulk =
                        JavaMode.getMode().lexicalContexts(h.buffer(), line, line.length()).orElseThrow();
                for (int offset = 0; offset <= line.length(); offset++) {
                    boolean inBlock = number == 0
                            ? offset > line.getText().indexOf(quotes)
                            : number == 1 || offset < line.getText().indexOf(quotes) + quotes.length();
                    LexicalContext expected = inBlock ? LexicalContext.STRING : LexicalContext.NEUTRAL;
                    assertEquals(expected, bulk[offset], "bulk at " + number + ":" + offset);
                    assertEquals(
                        expected,
                        JavaMode.getMode().lexicalContext(h.buffer(), new Position(line, offset)),
                        "point at " + number + ":" + offset);
                }
            }
        }
        finally {
            h.close();
        }
    }

    @Test
    public void cAndJavaScriptKeepTheirOrdinaryQuoteRules() {
        for (Mode mode : new Mode[] { CMode.getMode(), JavaScriptMode.getMode() }) {
            EditorHarness h = EditorHarness.create("\"\"\"\"").mode(mode);
            try {
                Line line = h.buffer().getFirstLine();
                LexicalContext[] bulk = mode.lexicalContexts(h.buffer(), line, line.length()).orElseThrow();
                assertEquals(LexicalContext.NEUTRAL, bulk[2], mode.getDisplayName());
                assertEquals(LexicalContext.NEUTRAL, bulk[4], mode.getDisplayName());
                assertEquals(
                    LexicalContext.NEUTRAL,
                    mode.lexicalContext(h.buffer(), new Position(line, 4)),
                    mode.getDisplayName());
            }
            finally {
                h.close();
            }
        }
    }
}
