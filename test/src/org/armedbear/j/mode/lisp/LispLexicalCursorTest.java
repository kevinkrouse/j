/*
 * LispLexicalCursorTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.lisp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.junit.jupiter.api.Test;

/**
 * The Lisp lexical cursor walks the line once: the number of token
 * advances is linear in the line's length, and a point query stops
 * advancing at its boundary instead of walking the whole line. The
 * fixture carries a string, a line comment and a block comment so the
 * walk exercises the multi-character marker and escape paths.
 */
public class LispLexicalCursorTest {
    private static final String TOKENS = "(foo \"a\\\"b\" ; c #| d |# e)";

    @Test
    public void bulkWalkIsLinear() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(LispMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            LexicalContext[] out = new LexicalContext[len + 1];
            LispFormatter.Cursor bulk = new LispFormatter.Cursor(line, len, out);
            bulk.run();
            // One step per character at most; multi-character tokens
            // (the block-comment markers, the escape) advance by more,
            // so the count never exceeds the length.
            assertTrue(bulk.steps <= len, "bulk steps " + bulk.steps + " exceeds len " + len);
            assertTrue(bulk.steps > 0, "bulk made no progress");
        }
        finally {
            h.close();
        }
    }

    @Test
    public void pointQueryStopsAtBoundary() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(LispMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            // A point query in the middle of the token-bearing line
            // must not walk the whole line.
            int boundary = TOKENS.indexOf("#|");
            LispFormatter.Cursor point = new LispFormatter.Cursor(line, boundary, null);
            point.run();
            assertTrue(point.steps <= boundary, "point steps " + point.steps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }
}
