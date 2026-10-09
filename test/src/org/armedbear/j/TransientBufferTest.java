/*
 * TransientBufferTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** q in simple edit mode: it closes help, results and output, whatever their mode. */
public class TransientBufferTest {
    private EditorHarness file;
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        h.close();
        file.close();
    }

    private void open(boolean isTransient) {
        file = EditorHarness.create("a file");
        file.buffer().setLastActivated(100);
        // Plain text mode binds no q of its own, as plain output's doesn't.
        h = EditorHarness.create("output");
        h.buffer().setTransient(isTransient);
        h.buffer().unmodified();
    }

    @Test
    public void qClosesATransientBufferInAModeThatDoesNotBindIt() throws Exception {
        open(true);
        SwingUtilities.invokeAndWait(() -> h.keys("q"));
        assertFalse(Editor.getBufferList().contains(h.buffer()));
    }

    @Test
    public void qInAnyOtherBufferIsTyped() throws Exception {
        open(false);
        SwingUtilities.invokeAndWait(() -> h.keys("q"));
        assertTrue(Editor.getBufferList().contains(h.buffer()));
        assertTrue(h.text().startsWith("q"), h.text());
    }
}
