/*
 * VimRegisterTest.java
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

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/** Yank, put, and which register the text went into. */
public class VimRegisterTest
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

    private static String reg(char name)
    {
        final VimRegisters.Register r = VimRegisters.getInstance().get(name);
        return r == null ? null : r.text;
    }

    // ------------------------------------------------------------ charwise

    @Test
    public void yankThenPutAfterTheCaret()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("yw");
        assertEquals("alpha ", reg(VimRegisters.UNNAMED));
        h.keys("$p");
        h.assertText("alpha bravoalpha \n");
    }

    @Test
    public void putBeforeTheCaret()
    {
        vim("ab\n").cursor(0, 0).keys("ylP");
        h.assertText("aab\n");
    }

    @Test
    public void putLeavesTheCaretOnTheLastCharacter()
    {
        vim("ab\n").cursor(0, 0).keys("ylp");
        h.assertText("aab\n");
        assertEquals(1, h.offset());
    }

    @Test
    public void putRepeatsWithACount()
    {
        vim("ab\n").cursor(0, 0).keys("yl3p");
        h.assertText("aaaab\n");
    }

    // ------------------------------------------------------------ linewise

    @Test
    public void yyThenPutMakesANewLineBelow()
    {
        vim("alpha\nbravo\n").cursor(0, 0).keys("yyp");
        h.assertText("alpha\nalpha\nbravo\n");
    }

    @Test
    public void capitalPPutsTheLineAbove()
    {
        vim("alpha\nbravo\n").cursor(1, 0).keys("yyP");
        h.assertText("alpha\nbravo\nbravo\n");
    }

    @Test
    public void ddThenPutMovesTheLine()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("ddp");
        h.assertText("two\none\nthree\n");
    }

    @Test
    public void aLinewisePutLandsOnTheFirstNonBlank()
    {
        vim("    alpha\nbravo\n").cursor(0, 0).keys("yyp");
        h.assertText("    alpha\n    alpha\nbravo\n");
        assertEquals(1, h.lineNumber());
        assertEquals(4, h.offset());
    }

    @Test
    public void capitalYYanksTheWholeLine()
    {
        vim("alpha\nbravo\n").cursor(0, 2).keys("Yp");
        h.assertText("alpha\nalpha\nbravo\n");
    }

    @Test
    public void puttingALineAtTheEndOfTheBuffer()
    {
        vim("alpha\nbravo\n").cursor(1, 0).keys("yyp");
        h.assertText("alpha\nbravo\nbravo\n");
    }

    // ----------------------------------------------------- which register

    @Test
    public void aYankFillsRegisterZeroAsWellAsTheUnnamedOne()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("yw");
        assertEquals("alpha ", reg(VimRegisters.YANK));
        assertEquals("alpha ", reg(VimRegisters.UNNAMED));
    }

    @Test
    public void aDeleteDoesNotClobberTheYankRegister()
    {
        // The whole point of register 0: delete something, still put back the
        // thing you yanked.
        vim("alpha bravo\n").cursor(0, 0).keys("yw");
        h.keys("dw");
        assertEquals("alpha ", reg(VimRegisters.YANK));
        assertEquals("alpha ", reg(VimRegisters.UNNAMED));
    }

    @Test
    public void asmallDeleteGoesToTheSmallDeleteRegister()
    {
        vim("alpha\n").cursor(0, 0).keys("x");
        assertEquals("a", reg(VimRegisters.SMALL_DELETE));
        assertNull("a small delete leaves register 1 alone", reg('1'));
    }

    @Test
    public void aLineDeleteShiftsThroughTheNumberedRegisters()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("dd");
        assertEquals("one\n", reg('1'));
        h.keys("dd");
        assertEquals("two\n", reg('1'));
        assertEquals("one\n", reg('2'));
    }

    @Test
    public void aNamedRegisterIsUsedWhenAsked()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("\"ayw");
        assertEquals("alpha ", reg('a'));
        h.keys("$\"ap");
        h.assertText("alpha bravoalpha \n");
    }

    @Test
    public void anUpperCaseNameAppends()
    {
        vim("ab\n").cursor(0, 0).keys("\"ayl");
        assertEquals("a", reg('a'));
        h.keys("l\"Ayl");
        assertEquals("ab", reg('a'));
    }

    @Test
    public void theBlackHoleRegisterDiscards()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("yw");
        h.keys("\"_dw");
        assertEquals("the yank survives a black hole delete",
                     "alpha ", reg(VimRegisters.UNNAMED));
        h.assertText("bravo\n");
    }
}
