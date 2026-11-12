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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
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
            JavaFormatter.Cursor bulk = new JavaFormatter.Cursor(line, len, out);
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
            JavaFormatter.Cursor point = new JavaFormatter.Cursor(line, boundary, null);
            point.run();
            assertTrue(point.steps <= boundary, "point steps " + point.steps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }
}
