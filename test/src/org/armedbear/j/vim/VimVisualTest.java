/*
 * VimVisualTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/**
 * Visual mode. Expectations here were checked against real nvim with
 * tools/vim-oracle.sh.
 */
public class VimVisualTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text)
    {
        h = EditorHarness.create(text).vim();
        return h;
    }

    private void at(int line, int offset)
    {
        assertEquals("line", line, h.lineNumber());
        assertEquals("offset", offset, h.offset());
    }

    // ------------------------------------------------------------ entering

    @Test
    public void vEntersVisualMode()
    {
        vim("abc\n").cursor(0, 1).keys("v");
        assertSame(VimMode.VISUAL, h.vimState().getMode());
        assertNotNull("the selection is j's own mark", h.editor().getMark());
    }

    @Test
    public void vAgainLeavesVisualMode()
    {
        vim("abc\n").cursor(0, 1).keys("vv");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
        assertNull(h.editor().getMark());
    }

    @Test
    public void escapeLeavesVisualMode()
    {
        vim("abc\n").cursor(0, 1).keys("v<Esc>");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
        assertNull(h.editor().getMark());
        at(0, 1);
    }

    @Test
    public void motionsExtendTheSelection()
    {
        vim("abcdef\n").cursor(0, 0).keys("vll");
        at(0, 2);
        assertEquals(0, h.editor().getMarkOffset());
    }

    // ------------------------------------------------------------ deleting

    @Test
    public void theSelectionIncludesTheCharacterUnderTheCaret()
    {
        // One character selected, so v then d takes exactly that character.
        vim("abc\n").cursor(0, 1).keys("vd");
        h.assertText("ac\n");
        at(0, 1);
    }

    @Test
    public void deletingASelectionOfTwoCharacters()
    {
        vim("abc\n").cursor(0, 0).keys("vld");
        h.assertText("c\n");
        at(0, 0);
    }

    @Test
    public void deletingBackwards()
    {
        vim("abcdef\n").cursor(0, 3).keys("vhhd");
        h.assertText("aef\n");
    }

    @Test
    public void capitalVDeletesWholeLines()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("Vd");
        h.assertText("two\nthree\n");
    }

    @Test
    public void capitalVWithAMotionDeletesTheLinesItCovers()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("Vjd");
        h.assertText("three\n");
    }

    @Test
    public void xIsDeleteInVisualMode()
    {
        vim("abc\n").cursor(0, 1).keys("vx");
        h.assertText("ac\n");
    }

    // -------------------------------------------------------- change and yank

    @Test
    public void changingASelection()
    {
        vim("abc\n").cursor(0, 1).keys("vcX");
        h.assertText("aXc\n");
        assertSame(VimMode.INSERT, h.vimState().getMode());
    }

    @Test
    public void yankingASelectionThenPutting()
    {
        vim("abc\n").cursor(0, 0).keys("vly");
        h.keys("$p");
        h.assertText("abcab\n");
    }

    @Test
    public void aLinewiseYankPutsWholeLines()
    {
        vim("one\ntwo\n").cursor(0, 0).keys("Vyp");
        h.assertText("one\none\ntwo\n");
    }

    // -------------------------------------------------------------- o and gv

    @Test
    public void oPutsTheCaretOnTheOtherEnd()
    {
        vim("abcdef\n").cursor(0, 1).keys("vll");
        at(0, 3);
        h.keys("o");
        at(0, 1);
        assertEquals("the anchor is now the far end", 3,
                     h.editor().getMarkOffset());
    }

    @Test
    public void oThenExtendingGrowsTheOtherWay()
    {
        vim("abcdef\n").cursor(0, 1).keys("vllohd");
        h.assertText("ef\n");
    }

    @Test
    public void gvBringsBackTheLastSelection()
    {
        vim("abcdef\n").cursor(0, 1).keys("vll<Esc>");
        h.cursor(0, 0).keys("gvd");
        h.assertText("aef\n");
    }

    // ------------------------------------------------------- the < and > marks

    @Test
    public void leavingVisualModeSetsTheSelectionMarks()
    {
        vim("abcdef\n").cursor(0, 1).keys("vll<Esc>");
        // '<' has to be spelled <lt> in key notation, as it does in a vimrc.
        h.cursor(0, 5).keys("`<lt>");
        at(0, 1);
        h.keys("`>");
        at(0, 3);
    }

    // ------------------------------------------------------------------ undo

    @Test
    public void aVisualDeleteUndoesInOneStep()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("Vjd");
        h.assertText("three\n");
        h.editor().undo();
        h.assertText("one\ntwo\nthree\n");
    }
}
