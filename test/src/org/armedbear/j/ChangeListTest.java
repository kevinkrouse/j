/*
 * ChangeListTest.java
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

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Where a buffer was changed, as vim's change list, and g; and g, through it. */
public class ChangeListTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private List<Integer> lines() {
        return ChangeList.getEntries(h.buffer()).stream().map(m -> m.getLineNumber()).toList();
    }

    @Test
    public void editsAreRecordedOncePerLine() {
        h = EditorHarness.create("zero\none\ntwo\nthree\n").vim();
        Editor.setCurrentEditor(h.editor());
        h.cursor(0, 0).keys("ia<Esc>").keys("ib<Esc>");
        h.cursor(3, 0).keys("Ac<Esc>");
        assertEquals(List.of(0, 3), lines());
        assertEquals(2, ChangeList.getIndex(h.buffer()));
    }

    @Test
    public void gSemicolonAndCommaTravel() {
        h = EditorHarness.create("zero\none\ntwo\nthree\n").vim();
        Editor.setCurrentEditor(h.editor());
        h.cursor(0, 0).keys("ia<Esc>");
        h.cursor(2, 0).keys("ib<Esc>");
        h.cursor(3, 0);
        h.keys("g;");
        assertEquals(2, h.lineNumber());
        h.keys("g;");
        assertEquals(0, h.lineNumber());
        h.keys("g,");
        assertEquals(2, h.lineNumber());
    }
}
