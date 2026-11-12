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
 * advancing at its boundary instead of walking the whole line.
 */
public class LispLexicalCursorTest {
    @Test
    public void bulkWalkIsLinear() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++)
            sb.append("a ");
        EditorHarness h = EditorHarness.create(sb.toString()).mode(LispMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            LexicalContext[] out = new LexicalContext[len + 1];
            LispFormatter.Cursor bulk = new LispFormatter.Cursor(line, len, out);
            bulk.run();
            // One step per character at most; multi-character tokens
            // advance by more, so the count never exceeds the length.
            assertTrue(bulk.steps <= len, "bulk steps " + bulk.steps + " exceeds len " + len);
        }
        finally {
            h.close();
        }
    }

    @Test
    public void pointQueryStopsAtBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++)
            sb.append("a ");
        EditorHarness h = EditorHarness.create(sb.toString()).mode(LispMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            // A point query near the front must not walk the whole line.
            LispFormatter.Cursor point = new LispFormatter.Cursor(line, 5, null);
            point.run();
            assertTrue(point.steps <= 5, "point steps " + point.steps + " exceeds boundary 5");
        }
        finally {
            h.close();
        }
    }
}
