/*
 * ShellLexicalCursorTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.sh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.junit.jupiter.api.Test;

/**
 * The Shell lexical cursor walks the line once: token advances are
 * linear in line length, and a point query stops at its boundary. The
 * fixture carries escaped double quotes, a single-quoted string and a
 * line comment to exercise the quote and escape paths.
 */
public class ShellLexicalCursorTest {
    private static final String TOKENS = "x=\"a\\\"b\" # c 'd' e";

    @Test
    public void bulkWalkIsLinear() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(ShellScriptMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            LexicalContext[] out = new LexicalContext[len + 1];
            ShellScriptFormatter.Cursor bulk = new ShellScriptFormatter.Cursor(line, len, out);
            bulk.run();
            // One step per char at most; the escape advances by two.
            assertTrue(bulk.steps <= len, "bulk steps " + bulk.steps + " exceeds len " + len);
            assertTrue(bulk.steps > 0, "bulk made no progress");
        }
        finally {
            h.close();
        }
    }

    @Test
    public void pointQueryStopsAtBoundary() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(ShellScriptMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int boundary = TOKENS.indexOf("'d'");
            ShellScriptFormatter.Cursor point = new ShellScriptFormatter.Cursor(line, boundary, null);
            point.run();
            assertTrue(point.steps <= boundary, "point steps " + point.steps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }
}
