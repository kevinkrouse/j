/*
 * GutterColumnsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A buffer's gutter columns, side by side after the line numbers. */
public class GutterColumnsTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    // A column of a width, whose tool tip is its name and where it is.
    private record Named(String name, int width) implements GutterColumn {
        @Override
        public void paint(Graphics2D g, Line line, int x, int y, int height, boolean top) {}

        @Override
        public String getToolTipText(Line line, int x, int y) {
            return name + " " + x;
        }
    }

    @Test
    public void columnsSitLeftToRightEachHitAsItsOwn() {
        h = EditorHarness.create("one\ntwo\n");
        final Buffer buffer = h.buffer();
        final Display display = h.editor().getDisplay();
        final int bare = Display.getGutterWidth(buffer);
        final Named a = new Named("a", 10);
        final Named b = new Named("b", 20);
        buffer.addGutterColumn(a);
        buffer.addGutterColumn(b);
        buffer.addGutterColumn(a);
        assertEquals(List.of(a, b), buffer.getGutterColumns(), "each once, in the order added");
        final int gutter = Display.getGutterWidth(buffer);
        assertEquals(bare + 30, gutter);
        final int y = buffer.getFirstLine().getHeight() / 2;
        final GutterColumn.At inA = display.gutterColumnAt(bare + 3, y);
        assertEquals(a, inA.column());
        assertEquals(3, inA.x());
        final GutterColumn.At inB = display.gutterColumnAt(bare + 12, y);
        assertEquals(b, inB.column());
        assertEquals(2, inB.x(), "from its own left");
        assertEquals("b 19", display.getToolTipText(new MouseEvent(display, 0, 0, 0, gutter - 1, y, 0, false)));
        assertNull(display.gutterColumnAt(gutter, y), "the text");
        buffer.removeGutterColumn(a);
        assertEquals(bare + 20, Display.getGutterWidth(buffer));
        assertEquals(b, display.gutterColumnAt(bare, y).column());
    }
}
