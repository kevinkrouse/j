/*
 * VimM13LeftoversTest.java
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

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/**
 * The items M13 filed for later, each expectation taken from nvim.
 */
public class VimM13LeftoversTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text, int line, int offset)
    {
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset);
        return h;
    }

    // ---------------------------------------- a count before an insert

    @Test
    public void countedInsertTypesItThreeTimes()
    {
        vim("xy", 0, 0).keys("3iab<Esc>");
        assertEquals("abababxy", h.value());
        h.assertCursorAt(0, 5);
    }

    @Test
    public void countedAppend()
    {
        vim("xy", 0, 0).keys("3aab<Esc>");
        assertEquals("xabababy", h.value());
        h.assertCursorAt(0, 6);
    }

    @Test
    public void countedAppendAtTheEnd()
    {
        vim("xy", 0, 0).keys("3Ahi<Esc>");
        assertEquals("xyhihihi", h.value());
        h.assertCursorAt(0, 7);
    }

    @Test
    public void countedInsertAtTheStart()
    {
        vim("xy", 0, 0).keys("3Ihi<Esc>");
        assertEquals("hihihixy", h.value());
        h.assertCursorAt(0, 5);
    }

    @Test
    public void theRepeatIsWhatWasTypedBackspacesAndAll()
    {
        vim("xy", 0, 0).keys("3iab<BS>c<Esc>");
        assertEquals("acacacxy", h.value());
    }

    @Test
    public void theRepeatTypesEnterToo()
    {
        vim("xy", 0, 0).keys("3ia<CR>b<Esc>");
        assertEquals("a\nba\nba\nbxy", h.value());
        h.assertCursorAt(3, 0);
    }

    @Test
    public void countedOpenLineOpensThatMany()
    {
        vim("  x\ny", 0, 2).keys("3ohi<Esc>");
        assertEquals("  x\n  hi\n  hi\n  hi\ny", h.value());
        h.assertCursorAt(3, 3);
    }

    @Test
    public void countedOpenAboveOpensThatMany()
    {
        vim("  x\ny", 0, 2).keys("3Ohi<Esc>");
        assertEquals("  hi\n  hi\n  hi\n  x\ny", h.value());
        h.assertCursorAt(2, 3);
    }

    @Test
    public void countedOpenWithNothingTypedLeavesEmptyLines()
    {
        vim("  x\ny", 0, 2).keys("3o<Esc>");
        assertEquals("  x\n\n\n\ny", h.value());
        h.assertCursorAt(3, 0);
    }

    @Test
    public void countedReplaceKeepsTypingOver()
    {
        vim("xxxxxxxx", 0, 1).keys("3Rab<Esc>");
        assertEquals("xabababx", h.value());
        h.assertCursorAt(0, 6);
    }

    @Test
    public void countedReplacePastTheEnd()
    {
        vim("xyz", 0, 1).keys("3Rab<Esc>");
        assertEquals("xababab", h.value());
        h.assertCursorAt(0, 6);
    }

    @Test
    public void oneUndoTakesBackEveryRepeat()
    {
        vim("xy", 0, 0).keys("3iab<Esc>").keys("u");
        assertEquals("xy", h.value());
        h.assertCursorAt(0, 0);
    }

    @Test
    public void aCountOnDotReplacesTheInsertCount()
    {
        vim("xy", 0, 0).keys("3iab<Esc>").keys("2.");
        assertEquals("ababaababbxy", h.value());
        h.assertCursorAt(0, 8);
    }

    @Test
    public void dotRepeatsTheCountedInsert()
    {
        vim("xy", 0, 0).keys("3iab<Esc>").keys("$.");
        assertEquals("abababxabababy", h.value());
    }

    @Test
    public void theRepeatRunsCtrlTAgain()
    {
        // nvim, shiftwidth 4: each repeat indents once more.
        vim("xy", 0, 0);
        h.buffer().setIndentSize(4);
        h.keys("3i<C-t>a<Esc>");
        assertEquals("            aaaxy", h.value());
    }
}
