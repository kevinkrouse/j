/*
 * VimAppearanceTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.InputHandler;
import org.junit.After;
import org.junit.Test;

/**
 * What the editor shows about the mode it is in.
 *
 * The drawing itself needs a screen, so what is checked here is the answer the
 * display and the status bar are given.
 */
public class VimAppearanceTest {
    private EditorHarness h;

    @After
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private InputHandler handler() {
        return h.editor().getInputHandler();
    }

    private EditorHarness vim(String text) {
        h = EditorHarness.create(text).vim();
        return h;
    }

    @Test
    public void simpleModeKeepsTheOrdinaryCaret() {
        h = EditorHarness.create("abc\n");
        assertNull("no handler, so nothing to ask", handler());
    }

    @Test
    public void normalModeUsesABlockCaret() {
        vim("abc\n").cursor(0, 0);
        assertSame(InputHandler.CaretShape.BLOCK, handler().getCaretShape());
    }

    @Test
    public void insertModeUsesABarCaret() {
        vim("abc\n").cursor(0, 0).keys("i");
        assertSame(InputHandler.CaretShape.BAR, handler().getCaretShape());
        h.keys("<Esc>");
        assertSame(InputHandler.CaretShape.BLOCK, handler().getCaretShape());
    }

    @Test
    public void replaceModeUsesAnUnderlineCaret() {
        vim("abc\n").cursor(0, 0).keys("R");
        assertSame(InputHandler.CaretShape.UNDERLINE, handler().getCaretShape());
        assertEquals("REPLACE", handler().getModeIndicator());
        h.keys("<Esc>");
        assertSame(InputHandler.CaretShape.BLOCK, handler().getCaretShape());
    }

    @Test
    public void visualModeKeepsTheBlockCaret() {
        // The caret has to stay visible inside the selection.
        vim("abc\n").cursor(0, 0).keys("vl");
        assertSame(InputHandler.CaretShape.BLOCK, handler().getCaretShape());
    }

    @Test
    public void normalModeAnnouncesNothing() {
        // An empty status line is what vim shows in normal mode.
        vim("abc\n").cursor(0, 0);
        assertNull(handler().getModeIndicator());
    }

    @Test
    public void insertAndVisualAnnounceThemselves() {
        vim("abc\n").cursor(0, 0).keys("i");
        assertEquals("INSERT", handler().getModeIndicator());
        h.keys("<Esc>v");
        assertEquals("VISUAL", handler().getModeIndicator());
        h.keys("<Esc>V");
        assertEquals("VISUAL LINE", handler().getModeIndicator());
    }

    @Test
    public void aHalfTypedCommandIsShown() {
        vim("abc\n").cursor(0, 0);
        assertNull(handler().getPendingCommand());

        h.keys("2");
        assertEquals("2", handler().getPendingCommand());
        h.keys("d");
        assertEquals("2d", handler().getPendingCommand());
        h.keys("w");
        assertNull(
            "the command ran, so nothing is pending",
            handler().getPendingCommand()
        );
    }

    @Test
    public void aPendingRegisterAndCountAreShownTogether() {
        vim("abc\n").cursor(0, 0).keys("\"a2d");
        assertEquals("2d", handler().getPendingCommand());
    }

    @Test
    public void escapeClearsAHalfTypedCommand() {
        vim("abc\n").cursor(0, 0).keys("2d<Esc>");
        assertNull(handler().getPendingCommand());
    }
}
