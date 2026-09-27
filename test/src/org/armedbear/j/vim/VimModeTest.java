/*
 * VimModeTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/**
 * The input seam: a key in normal mode never means itself, and simple mode is
 * exactly what it was.
 */
public class VimModeTest
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

    // ------------------------------------------------- simple mode is intact

    @Test
    public void simpleModeHasNoHandlerAtAll()
    {
        h = EditorHarness.create("alpha\n");
        assertNull("an ordinary buffer must not pay for modal editing",
                   h.editor().getInputHandler());
        h.cursor(0, 0).keys("xy");
        h.assertText("xyalpha\n");
    }

    @Test
    public void unknownEditModeFallsBackToSimple()
    {
        h = EditorHarness.create("alpha\n");
        h.buffer().setProperty(org.armedbear.j.Property.EDIT_MODE, "emacs");
        assertNull(h.editor().getInputHandler());
        h.cursor(0, 0).keys("x");
        h.assertText("xalpha\n");
        // It says so in the log.
        EditorHarness.forgetLoggedErrors();
    }

    // ------------------------------------------------------- normal mode

    @Test
    public void startsInNormalMode()
    {
        vim("alpha\n");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
    }

    @Test
    public void normalModeDoesNotInsertText()
    {
        // None of these are bound to anything; what matters is that not one
        // of them ends up in the buffer as a character.
        vim("alpha bravo\n").cursor(0, 0).keys("zQvV");
        h.assertText("alpha bravo\n");
    }

    @Test
    public void normalModeSwallowsUnboundKeysRatherThanRunningJCommands()
    {
        // 'q' is not a vim command yet, but it must not reach j's key maps
        // and it must not be inserted either.
        vim("alpha\n").cursor(0, 0).keys("q");
        h.assertText("alpha\n");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
    }

    // ---------------------------------------------------- entering insert

    @Test
    public void iInsertsBeforeTheCaret()
    {
        vim("alpha\n").cursor(0, 2).keys("iXY");
        h.assertText("alXYpha\n");
        assertSame(VimMode.INSERT, h.vimState().getMode());
    }

    @Test
    public void aInsertsAfterTheCaret()
    {
        vim("alpha\n").cursor(0, 2).keys("aXY");
        h.assertText("alpXYha\n");
    }

    @Test
    public void aAtEndOfLineAppends()
    {
        vim("alpha\n").cursor(0, 4).keys("aZ");
        h.assertText("alphaZ\n");
    }

    @Test
    public void capitalIInsertsAtTheFirstNonBlank()
    {
        vim("    alpha\n").cursor(0, 7).keys("IX");
        h.assertText("    Xalpha\n");
    }

    @Test
    public void capitalAAppendsToTheLine()
    {
        vim("alpha\n").cursor(0, 0).keys("AX");
        h.assertText("alphaX\n");
    }

    @Test
    public void oOpensALineBelow()
    {
        vim("alpha\nbravo\n").cursor(0, 2).keys("oX");
        h.assertText("alpha\nX\nbravo\n");
    }

    @Test
    public void capitalOOpensALineAbove()
    {
        vim("alpha\nbravo\n").cursor(1, 2).keys("OX");
        h.assertText("alpha\nX\nbravo\n");
    }

    // ---------------------------------------------------- leaving insert

    @Test
    public void escapeReturnsToNormalAndStepsBack()
    {
        vim("alpha\n").cursor(0, 0).keys("iXY<Esc>");
        h.assertText("XYalpha\n");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
        assertEquals("the caret lands on the last character typed",
                     1, h.offset());
    }

    @Test
    public void afterEscapeKeysAreCommandsAgain()
    {
        vim("alpha\n").cursor(0, 0).keys("iX<Esc>zzz");
        h.assertText("Xalpha\n");
    }

    @Test
    public void controlBracketIsEscape()
    {
        // CTRL-[ is what Escape sends on a terminal; vim treats them as one
        // key, and people who learned vim on a terminal type it.
        vim("alpha\n").cursor(0, 0).keys("iXY<C-[>");
        h.assertText("XYalpha\n");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
        assertEquals("and it steps back like Escape does", 1, h.offset());
    }

    @Test
    public void controlBracketLeavesVisualModeToo()
    {
        vim("alpha\n").cursor(0, 0).keys("vl<C-[>");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
        assertNull(h.editor().getMark());
    }

    @Test
    public void escapeInNormalModeIsSwallowed()
    {
        vim("alpha\n").cursor(0, 0).keys("<Esc>");
        h.assertText("alpha\n");
        assertSame(VimMode.NORMAL, h.vimState().getMode());
    }

    // ------------------------------------------------------------- undo

    @Test
    public void anInsertSessionUndoesInOneStep()
    {
        vim("alpha\n").cursor(0, 0).keys("ione two three<Esc>");
        h.assertText("one two threealpha\n");

        h.editor().undo();
        h.assertText("alpha\n");
    }

    @Test
    public void anInsertSessionSpanningLinesUndoesInOneStep()
    {
        vim("alpha\n").cursor(0, 5).keys("i<CR>second<CR>third<Esc>");
        h.assertText("alpha\nsecond\nthird\n");

        h.editor().undo();
        h.assertText("alpha\n");
    }

    @Test
    public void escapeClosesTheUndoStep()
    {
        vim("alpha\n").cursor(0, 0).keys("iX<Esc>");
        assertFalse("no undo step may be left open",
                    h.vimState().isInsertEditOpen());
    }

    // ------------------------------------------------------------ caret

    @Test
    public void normalModeCaretCannotRestPastTheLastCharacter()
    {
        // Insert mode allows the caret one past the end; normal mode does not.
        vim("alpha\n").cursor(0, 0).keys("AX<Esc>");
        assertEquals(5, h.offset());
    }

    @Test
    public void caretClampingCopesWithAnEmptyLine()
    {
        vim("\nbravo\n").cursor(0, 0).keys("<Esc>");
        assertEquals(0, h.offset());
    }
}
