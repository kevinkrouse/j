/*
 * RegionSelectionTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Region.isSelection and selectedLines: the lines a selection takes. */
public class RegionSelectionTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    // The numbers of the lines selected from line a, offset ao, to line b, offset bo.
    private List<Integer> selected(String text, int a, int ao, int b, int bo) {
        h = EditorHarness.create().value(text);
        h.editor().setMark(new Position(h.buffer().getLine(a), ao));
        h.editor().setDot(h.buffer().getLine(b), bo);
        final List<Integer> out = Region.selectedLines(h.editor()).stream().map(Line::lineNumber).toList();
        assertEquals(!out.isEmpty(), Region.isSelection(h.editor()));
        return out;
    }

    @Test
    public void justTheBreakAfterALinesTextTakesNoLine() {
        assertEquals(List.of(), selected("one\ntwo\n", 0, 3, 1, 0));
        assertFalse(Region.isSelection(h.editor()));
    }

    @Test
    public void anEmptyLineSelectedFromItsStartIsTaken() {
        assertEquals(List.of(1), selected("one\n\nthree\n", 1, 0, 2, 0));
        assertTrue(Region.isSelection(h.editor()));
        assertEquals(List.of(1, 2), selected("one\n\nthree\n", 1, 0, 2, 2));
    }

    @Test
    public void aLineIsTakenWithTextOfItSelected() {
        assertEquals(List.of(0, 1), selected("one\ntwo\n", 0, 2, 1, 1));
        assertEquals(List.of(1), selected("one\ntwo\nthree\n", 0, 3, 2, 0));
    }
}
