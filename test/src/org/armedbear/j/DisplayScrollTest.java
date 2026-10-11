/*
 * DisplayScrollTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Scrolling by the pixel, and what the caret does about a line partly off the top. */
public class DisplayScrollTest {
    private EditorHarness h;
    private Display display;
    private int ch;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    // Thirty numbered lines in a window ten rows high.
    private void thirtyLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++)
            sb.append(i).append('\n');
        h = EditorHarness.create(sb.toString()).rows(10);
        display = h.editor().getDisplay();
        ch = Display.getCharHeight();
        Editor.setCurrentEditor(h.editor());
    }

    private Line line(int n) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < n; i++)
            l = l.next();
        return l;
    }

    private void top(int line, int pixels) {
        assertEquals(line, display.getTopLine().lineNumber(), "top line");
        assertEquals(pixels, display.getPixelsAboveTopLine(), "pixels above the top line");
    }

    @Test
    public void partOfALine() {
        thirtyLines();
        assertEquals(5, display.scrollByPixels(5));
        top(0, 5);
    }

    @Test
    public void acrossLinesBothWays() {
        thirtyLines();
        display.scrollByPixels(2 * ch + 3);
        top(2, 3);
        display.scrollByPixels(-ch - 4);
        top(0, ch - 1);
    }

    @Test
    public void stopsAtTheTop() {
        thirtyLines();
        display.scrollByPixels(ch + 2);
        assertEquals(-ch - 2, display.scrollByPixels(-10 * ch));
        top(0, 0);
    }

    @Test
    public void stopsWithTheLastLineAtTheBottom() {
        thirtyLines();
        assertEquals(20 * ch, display.scrollByPixels(100 * ch));
        top(20, 0);
        assertEquals(0, display.scrollByPixels(1));
    }

    @Test
    public void aBufferThatFitsDoesNotScroll() {
        h = EditorHarness.create("1\n2\n3\n").rows(10);
        display = h.editor().getDisplay();
        assertEquals(0, display.scrollByPixels(50));
        assertEquals(0, display.getPixelsAboveTopLine());
    }

    @Test
    public void windowUpAndDownFinishThePartLine() {
        thirtyLines();
        display.scrollByPixels(5);
        display.windowDown();
        top(1, 0);
        display.scrollByPixels(5);
        display.windowUp();
        top(1, 0);
        display.windowUp();
        top(0, 0);
    }

    @Test
    public void aCaretOnThePartLineGoesToTheFirstWholeOne() {
        thirtyLines();
        h.editor().setDot(display.getTopLine(), 0);
        display.scrollByPixels(5);
        h.editor().maybeScrollCaret();
        assertEquals(1, h.editor().getDotLineNumber());
    }

    @Test
    public void upOntoThePartLineShowsAllOfIt() {
        thirtyLines();
        display.scrollByPixels(5);
        h.editor().setDot(display.getTopLine().next(), 0);
        display.up(false);
        assertEquals(0, h.editor().getDotLineNumber());
        top(0, 0);
    }

    @Test
    public void downOntoThePartLineAtTheBottomShowsAllOfIt() {
        thirtyLines();
        display.scrollByPixels(5);
        Line nine = display.getTopLine();
        for (int i = 0; i < 9; i++)
            nine = nine.next();
        h.editor().setDot(nine, 0);
        display.down(false);
        assertEquals(10, h.editor().getDotLineNumber());
        top(1, 0);
    }

    @Test
    public void upWithTheCaretFarAboveLeavesItToReframing() {
        thirtyLines();
        display.setTopLine(line(20));
        h.editor().setDot(line(5), 0);
        display.up(false);
        assertEquals(4, h.editor().getDotLineNumber());
        top(20, 0);
    }

    // The window at the end, one blank line below the last, as reframing
    // leaves it; a command moves it up ten lines. Easing there starts from
    // the end, not from the line above it where scrolling would stop.
    @Test
    public void aCommandsScrollStartsAndEndsWhereTheWindowWas() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            thirtyLines();
            h.editor().setDot(line(11), 0);
            display.setTopLine(line(21));
            display.recordCommandStart(line(21), 0);
            display.setTopLine(line(11));
            display.endCommand();
            top(21, 0);
            // Finishes the scroll.
            display.beginCommand();
            display.forgetCommand();
            top(11, 0);
        });
    }

    @Test
    public void aReloadIsNotAScroll() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            thirtyLines();
            display.setTopLine(line(20));
            display.recordCommandStart(line(20), 0);
            final int modCount = h.buffer().getModCount();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++)
                sb.append(i).append('\n');
            h.buffer().setText(sb.toString());
            h.buffer().setModCount(modCount);
            display.setTopLine(line(20));
            display.endCommand();
            assertEquals(line(20), display.getTopLine());
            assertEquals(0, display.getPixelsAboveTopLine());
        });
    }

    @Test
    public void quickerClicksEaseForLess() {
        final long ms = 1_000_000L;
        assertEquals(120, SmoothScroller.wheelMillis(Long.MAX_VALUE, 120), "a lone click");
        assertEquals(60, SmoothScroller.wheelMillis(30 * ms, 120), "twice the time since the last batch");
        assertEquals(21, SmoothScroller.wheelMillis(10_500_000L, 120), "a fast wheel's batches");
        assertEquals(0, SmoothScroller.wheelMillis(5 * ms, 0), "smoothScrollingDuration 0 turns easing off");
    }

    @Test
    public void theEasingStartsFastAndEndsAtOne() {
        assertEquals(0.0, SmoothScroller.easeOut(0.0));
        assertEquals(1.0, SmoothScroller.easeOut(1.0));
        assertTrue(SmoothScroller.easeOut(0.25) > 0.5, "most of the way in the first quarter");
        double last = 0;
        for (double t = 0.05; t <= 1.0; t += 0.05) {
            assertTrue(SmoothScroller.easeOut(t) >= last);
            last = SmoothScroller.easeOut(t);
        }
    }
}
