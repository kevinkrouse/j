/*
 * NumberCommandsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/**
 * incrementNumber and decrementNumber as j commands: at the caret, and over
 * a selection, which is what vim's visual CTRL-A and g CTRL-A use.
 */
public class NumberCommandsTest {
    private EditorHarness h;

    @After
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private Editor on(String text, int line, int offset) {
        h = EditorHarness.create(text);
        Editor.setCurrentEditor(h.editor());
        h.cursor(line, offset);
        return h.editor();
    }

    private void select(int line, int offset) {
        final Editor editor = h.editor();
        editor.setMarkAtDot();
        h.cursor(line, offset);
    }

    @Test
    public void atTheCaret() {
        on("x 7 9\n", 0, 0);
        NumberCommands.incrementNumber();
        assertEquals("x 8 9\n", h.text());
        NumberCommands.decrementNumber("10");
        assertEquals("x -2 9\n", h.text());
    }

    @Test
    public void overASelectionTheFirstNumberOfEachLine() {
        on("1 1\n1 1\nx\n1\n", 0, 0);
        select(3, 1);
        NumberCommands.incrementNumber("2");
        assertEquals("3 1\n3 1\nx\n3\n", h.text());
        assertEquals(0, h.editor().getDotLineNumber());
        assertEquals(null, h.editor().getMark());
    }

    @Test
    public void progressiveAddsMoreToEachNumber() {
        on("0\n0\nx\n0\n", 0, 0);
        select(3, 1);
        NumberCommands.incrementNumber("progressive");
        assertEquals("1\n2\nx\n3\n", h.text());
    }

    @Test
    public void theSelectionLimitsItsFirstAndLastLines() {
        // From the second 1 on the first line, to before the 1 on the last.
        on("1 1\nx 1\n", 0, 2);
        select(1, 1);
        NumberCommands.incrementNumber();
        assertEquals("1 2\nx 1\n", h.text());
    }

    @Test
    public void anUndoTakesItAllBack() {
        on("1\n1\n", 0, 0);
        select(1, 1);
        NumberCommands.incrementNumber();
        h.editor().undo();
        assertEquals("1\n1\n", h.text());
    }
}
