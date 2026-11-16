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
 * The Java lexical cursor walks the line once: token advances are linear
 * in line length, and a point query stops at its boundary. The fixture
 * carries a block comment, a string and a line comment so the walk
 * exercises multi-character markers and escapes, not just plain chars.
 */
public class JavaLexicalCursorTest {
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
            // One step per char at most; multi-char tokens advance by more.
            assertTrue(bulk.steps <= len, "bulk steps " + bulk.steps + " exceeds len " + len);
            // Non-vacuous: the walk actually consumed the tokens.
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
