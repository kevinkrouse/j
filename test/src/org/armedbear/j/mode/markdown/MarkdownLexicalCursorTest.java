/*
 * MarkdownLexicalCursorTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.markdown;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.junit.jupiter.api.Test;

/**
 * The Markdown lexical cursor walks the line once. Resolving a code
 * span's close inspects ahead of the boundary — that is lookahead, not
 * extra passes — so the bound is a small multiple of the line's
 * length, not one step per character. A point query stops advancing
 * at its boundary instead of walking the whole line.
 */
public class MarkdownLexicalCursorTest {
    @Test
    public void bulkWalkIsLinear() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++)
            sb.append("a ");
        EditorHarness h = EditorHarness.create(sb.toString()).mode(MarkdownMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            LexicalContext[] out = new LexicalContext[len + 1];
            MarkdownFormatter.Cursor bulk = new MarkdownFormatter.Cursor(line, len, out);
            bulk.run();
            assertTrue(bulk.steps <= len, "bulk steps " + bulk.steps + " exceeds len " + len);
        }
        finally {
            h.close();
        }
    }

    @Test
    public void unmatchedBackticksStayLinear() {
        // Every backtick opens a run whose close must be searched for.
        // The search is bounded by the line, so the total stays linear
        // rather than quadratic in the number of runs.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++)
            sb.append("`x");
        EditorHarness h = EditorHarness.create(sb.toString()).mode(MarkdownMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            LexicalContext[] out = new LexicalContext[len + 1];
            MarkdownFormatter.Cursor bulk = new MarkdownFormatter.Cursor(line, len, out);
            bulk.run();
            // Token advances plus the bounded lookahead for each run's
            // close: a small multiple of the length, not length^2.
            assertTrue(bulk.steps <= 2L * len, "bulk steps " + bulk.steps + " exceeds 2*len " + (2 * len));
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
        EditorHarness h = EditorHarness.create(sb.toString()).mode(MarkdownMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            // A point query near the front must not walk the whole line.
            MarkdownFormatter.Cursor point = new MarkdownFormatter.Cursor(line, 5, null);
            point.run();
            assertTrue(point.steps <= 5, "point steps " + point.steps + " exceeds boundary 5");
        }
        finally {
            h.close();
        }
    }
}
