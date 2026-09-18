/*
 * VimMotionTest.java
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

/** Motions move the caret and nothing else. */
public class VimMotionTest
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

    // ------------------------------------------------------------ h and l

    @Test
    public void lMovesRight()
    {
        vim("alpha\n").cursor(0, 0).keys("l");
        at(0, 1);
    }

    @Test
    public void hMovesLeft()
    {
        vim("alpha\n").cursor(0, 3).keys("h");
        at(0, 2);
    }

    @Test
    public void lStopsOnTheLastCharacter()
    {
        vim("alpha\n").cursor(0, 0).keys("llllllllll");
        at(0, 4);
    }

    @Test
    public void hStopsAtTheStartOfTheLine()
    {
        vim("alpha\n").cursor(0, 2).keys("hhhhh");
        at(0, 0);
    }

    @Test
    public void countRepeatsAMotion()
    {
        vim("alphabet\n").cursor(0, 0).keys("3l");
        at(0, 3);
    }

    @Test
    public void countsAreMultiDigit()
    {
        vim("abcdefghijklmnopqrstuvwxyz\n").cursor(0, 0).keys("12l");
        at(0, 12);
    }

    @Test
    public void aCountThatOvershootsStopsAtTheEnd()
    {
        vim("alpha\n").cursor(0, 0).keys("99l");
        at(0, 4);
    }

    // ------------------------------------------------------------ j and k

    @Test
    public void jAndKMoveBetweenLines()
    {
        vim("alpha\nbravo\ncharlie\n").cursor(0, 0).keys("jj");
        at(2, 0);
        h.keys("k");
        at(1, 0);
    }

    @Test
    public void jKeepsTheColumn()
    {
        vim("alpha\nbravo\n").cursor(0, 3).keys("j");
        at(1, 3);
    }

    @Test
    public void theColumnSurvivesAShortLine()
    {
        // Passing through the short line must not lose the column.
        vim("alphabet\nxy\nalphabet\n").cursor(0, 6).keys("jj");
        at(2, 6);
    }

    @Test
    public void jAtTheLastLineDoesNothing()
    {
        vim("alpha\nbravo\n").cursor(1, 2).keys("j");
        at(1, 2);
    }

    // -------------------------------------------------------- line motions

    @Test
    public void zeroGoesToTheFirstColumn()
    {
        vim("    alpha\n").cursor(0, 7).keys("0");
        at(0, 0);
    }

    @Test
    public void caretGoesToTheFirstNonBlank()
    {
        vim("    alpha\n").cursor(0, 8).keys("^");
        at(0, 4);
    }

    @Test
    public void dollarGoesToTheLastCharacter()
    {
        vim("alpha\n").cursor(0, 0).keys("$");
        at(0, 4);
    }

    @Test
    public void dollarSticksToTheEndOfEachLine()
    {
        vim("alphabet\nxy\nalphabet\n").cursor(0, 0).keys("$j");
        at(1, 1);
        h.keys("j");
        at(2, 7);
    }

    @Test
    public void zeroIsAMotionButOnlyWithoutACount()
    {
        vim("alphabet\n").cursor(0, 4).keys("0");
        at(0, 0);
        // In "10l" the zero is part of the count, not a motion.
        h.cursor(0, 0).keys("10l");
        at(0, 7);
    }

    // ------------------------------------------------------ gg, G and bar

    @Test
    public void ggGoesToTheFirstLine()
    {
        vim("alpha\nbravo\ncharlie\n").cursor(2, 3).keys("gg");
        at(0, 0);
    }

    @Test
    public void capitalGGoesToTheLastLine()
    {
        vim("alpha\nbravo\ncharlie\n").cursor(0, 0).keys("G");
        at(2, 0);
    }

    @Test
    public void aCountWithGGoesToThatLine()
    {
        vim("alpha\nbravo\ncharlie\n").cursor(0, 0).keys("2G");
        at(1, 0);
    }

    @Test
    public void ggLandsOnTheFirstNonBlank()
    {
        vim("    alpha\nbravo\n").cursor(1, 0).keys("gg");
        at(0, 4);
    }

    @Test
    public void barGoesToAScreenColumn()
    {
        vim("alphabet\n").cursor(0, 0).keys("5|");
        at(0, 4);
    }

    // ------------------------------------------------------- key to key

    @Test
    public void arrowKeysAreBoundToTheMotions()
    {
        vim("alpha\nbravo\n").cursor(0, 0).keys("<Right><Right><Down>");
        at(1, 2);
    }

    @Test
    public void aCountAppliesThroughAKeyToKeyBinding()
    {
        vim("alphabet\n").cursor(0, 0).keys("3<Right>");
        at(0, 3);
    }

    @Test
    public void spaceAndBackspaceMove()
    {
        vim("alpha\n").cursor(0, 0).keys("<Space><Space>");
        at(0, 2);
        h.keys("<BS>");
        at(0, 1);
    }

    // ------------------------------------------------- motions do not edit

    @Test
    public void noMotionChangesTheText()
    {
        vim("alpha\nbravo\n").cursor(0, 0).keys("lllj0^$ggG3l");
        h.assertText("alpha\nbravo\n");
    }
}
