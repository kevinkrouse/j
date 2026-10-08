/*
 * VimTransientBufferTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Help, results and output in vim edit mode: q closes them, and so does
 * Escape when there is nothing else for it to stop.
 */
public class VimTransientBufferTest {
    private EditorHarness file;
    private EditorHarness h;

    @BeforeEach
    public void setUp() {
        file = EditorHarness.create("a file");
        h = EditorHarness.create("results\nmore results").vim();
        h.buffer().setTransient(true);
        h.buffer().unmodified();
        file.buffer().setLastActivated(100);
    }

    @AfterEach
    public void tearDown() {
        h.close();
        file.close();
    }

    // On the event thread, where closing refreshes the sidebar from.
    private void keys(String keys) throws Exception {
        SwingUtilities.invokeAndWait(() -> h.keys(keys));
    }

    private boolean closed() {
        return !Editor.getBufferList().contains(h.buffer());
    }

    @Test
    public void qClosesATransientBuffer() throws Exception {
        keys("q");
        assertTrue(closed());
        assertSame(file.buffer(), h.editor().getBuffer());
    }

    @Test
    public void escapeWithNothingToStopClosesIt() throws Exception {
        keys("<Esc>");
        assertTrue(closed());
    }

    @Test
    public void escapeAfterACountOnlyDropsTheCount() {
        h.keys("2<Esc>");
        assertFalse(closed());
        h.keys("j");
        h.assertCursorAt(1, 0);
    }

    @Test
    public void motionsWork() {
        h.keys("jw");
        h.assertCursorAt(1, 5);
    }
}
