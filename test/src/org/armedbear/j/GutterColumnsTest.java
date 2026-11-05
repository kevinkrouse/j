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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.util.List;
import org.armedbear.j.mode.text.PlainTextMode;
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

    // A column filled with a color.
    private record Filled(java.awt.Color color) implements GutterColumn {
        @Override
        public int width() {
            return 6;
        }

        @Override
        public void paint(Graphics2D g, Line line, int x, int y, int height, boolean top) {
            g.setColor(color);
            g.fillRect(x, y, width(), height);
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

    @Test
    public void aGutterAtTheRightIsClickedAndPaintedThere() {
        h = EditorHarness.create();
        final Buffer right = new Buffer(File.getInstance(
            new java.io.File(System.getProperty("java.io.tmpdir"), "j-right-" + System.nanoTime() + ".txt")
                    .getPath())) {
            @Override
            public boolean isGutterOnRight() {
                return true;
            }
        };
        right.setMode(PlainTextMode.getMode());
        right.setFormatter(PlainTextMode.getMode().getFormatter(right));
        right.autosaveEnabled = false;
        right.setText("one\ntwo\n");
        h.showBuffer(right);
        final Named a = new Named("a", 10);
        right.addGutterColumn(a);
        final Display display = h.editor().getDisplay();
        final int width = display.getWidth();
        final int gutter = Display.getGutterWidth(right);
        final int y = right.getFirstLine().getHeight() / 2;
        // The column, last in the gutter, at the display's right edge.
        assertEquals(width - 1, display.toScreenX(gutter - 1));
        assertEquals(0, display.toScreenX(gutter), "the text at the left edge");
        // Events as laid out, the gutter at the left, to the listeners.
        final List<Integer> xs = new java.util.ArrayList<>();
        // The editor's own wants a frame.
        for (java.awt.event.MouseListener l : display.getMouseListeners())
            display.removeMouseListener(l);
        display.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                xs.add(e.getX());
            }
        });
        for (int x : new int[] { width - 1, 0 })
            display.dispatchEvent(
                new MouseEvent(display, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1));
        assertEquals(List.of(gutter - 1, gutter), xs);
        assertEquals(a, display.gutterColumnAt(xs.get(0), y).column());
        // A drag over the gutter goes on past the text's end, not to its start.
        for (java.awt.event.MouseMotionListener l : display.getMouseMotionListeners())
            display.removeMouseMotionListener(l);
        final List<Integer> dragged = new java.util.ArrayList<>();
        display.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                dragged.add(e.getX());
            }
        });
        display.dispatchEvent(
            new MouseEvent(display, MouseEvent.MOUSE_DRAGGED, 0, 0, width - 1, y, 0, false, MouseEvent.BUTTON1));
        assertTrue(dragged.get(0) >= width, "past the text's end: " + dragged);
        // Painted: the gutter's background at the right, the text's at the left.
        final java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, display.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.awt.Color gutterColor = new java.awt.Color(0x12, 0x34, 0x56);
        right.addGutterColumn(new Filled(gutterColor));
        final boolean ready = Editor.displayReady();
        Editor.setDisplayReady(true);
        try {
            final Graphics2D g = image.createGraphics();
            g.setClip(0, 0, width, display.getHeight());
            display.paintComponent(g);
            g.dispose();
        }
        finally {
            Editor.setDisplayReady(ready);
        }
        assertEquals(gutterColor.getRGB(), image.getRGB(width - 2, y));
        assertNotEquals(gutterColor.getRGB(), image.getRGB(1, y), "not at the left");
    }
}
