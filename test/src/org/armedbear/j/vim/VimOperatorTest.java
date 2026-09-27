/*
 * VimOperatorTest.java
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
 * Operators, and the range rules that decide how far they reach.
 */
public class VimOperatorTest
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

    // -------------------------------------------------------------- delete

    @Test
    public void dwDeletesToTheNextWord()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("dw");
        h.assertText("bravo\n");
    }

    @Test
    public void deDeletesToTheEndOfTheWord()
    {
        // e is inclusive where w is exclusive, so the space survives.
        vim("alpha bravo\n").cursor(0, 0).keys("de");
        h.assertText(" bravo\n");
    }

    @Test
    public void dollarDeletesToTheEndOfTheLine()
    {
        vim("alpha bravo\n").cursor(0, 5).keys("d$");
        h.assertText("alpha\n");
    }

    @Test
    public void dhAndDlDeleteOneCharacter()
    {
        vim("alpha\n").cursor(0, 2).keys("dl");
        h.assertText("alha\n");
        h.text("alpha\n").cursor(0, 2).keys("dh");
        h.assertText("apha\n");
    }

    @Test
    public void ddDeletesTheLine()
    {
        vim("alpha\nbravo\ncharlie\n").cursor(1, 2).keys("dd");
        h.assertText("alpha\ncharlie\n");
    }

    @Test
    public void ddWithACountDeletesThatManyLines()
    {
        vim("one\ntwo\nthree\nfour\n").cursor(0, 0).keys("2dd");
        h.assertText("three\nfour\n");
    }

    @Test
    public void ddLeavesTheCaretOnTheFirstNonBlank()
    {
        vim("alpha\n    bravo\n").cursor(0, 0).keys("dd");
        assertEquals(4, h.offset());
    }

    @Test
    public void djDeletesBothLines()
    {
        // j is linewise, so dj takes the line below as well.
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("dj");
        h.assertText("three\n");
    }

    // ---------------------------------------------- the counts multiply

    @Test
    public void aCountBeforeTheOperatorRepeatsTheMotion()
    {
        vim("one two three four\n").cursor(0, 0).keys("2dw");
        h.assertText("three four\n");
    }

    @Test
    public void countsOnBothSidesMultiply()
    {
        // 2d3w is six words, which is what vim does.
        vim("a b c d e f g\n").cursor(0, 0).keys("2d3w");
        h.assertText("g\n");
    }

    // ------------------------------------------------ the exclusive rules

    @Test
    public void anExclusiveMotionEndingInColumnOneBecomesLinewise()
    {
        // :help exclusive, rule 2. Starting at the first non-blank and ending
        // in column 1 means whole lines were meant.
        vim("one\ntwo\n\nthree\n").cursor(0, 0).keys("d}");
        h.assertText("\nthree\n");
    }

    @Test
    public void dwAtTheEndOfALineDoesNotJoinLines()
    {
        // The single most noticeable way a word motion can feel wrong.
        vim("alpha bravo\ncharlie\n").cursor(0, 6).keys("dw");
        h.assertText("alpha \ncharlie\n");
    }

    @Test
    public void dwFromTheFirstNonBlankStillDoesNotGoLinewise()
    {
        // The case that tells the two rules apart. Starting at the first
        // non-blank and ending in column 1 would make an exclusive motion
        // linewise -- but the w rule pulls the end back first, so the line
        // survives. Without it this deletes the whole line.
        vim(" word1\nword2\n").cursor(0, 1).keys("dw");
        h.assertText(" \nword2\n");
    }

    @Test
    public void dwOnTheOnlyWordLeftStopsAtTheEndOfTheLine()
    {
        vim(" alpha \n").cursor(0, 1).keys("dw");
        h.assertText(" \n");
    }

    // -------------------------------------------------------------- change

    @Test
    public void cwChangesToTheEndOfTheWordNotTheNextOne()
    {
        // The documented cw special case: the space after alpha survives.
        vim("alpha bravo\n").cursor(0, 0).keys("cwX");
        h.assertText("X bravo\n");
    }

    @Test
    public void cwOnWhitespaceBehavesLikeAnOrdinaryChange()
    {
        vim("alpha  bravo\n").cursor(0, 5).keys("cwX");
        h.assertText("alphaXbravo\n");
    }

    @Test
    public void ceChangesToTheEndOfTheWord()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("ceX");
        h.assertText("X bravo\n");
    }

    @Test
    public void ccEmptiesTheLineAndInserts()
    {
        vim("alpha\nbravo\n").cursor(0, 2).keys("ccX");
        h.assertText("X\nbravo\n");
    }

    @Test
    public void countedCcReachingEndOfBufferChangesEveryLine()
    {
        // RangeNormalizer represents a range that reaches end of buffer
        // differently from one that does not, which changeLinewise has to
        // account for or it silently drops the last line from the change.
        vim("alpha\nbravo\n").cursor(0, 0).keys("2ccX");
        h.assertText("X\n");
    }

    @Test
    public void changeLeavesInsertMode()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("cwX<Esc>");
        h.assertText("X bravo\n");
        assertEquals(VimMode.NORMAL, h.vimState().getMode());
    }

    // ------------------------------------------------ operators with a motion

    @Test
    public void xDeletesTheCharacterUnderTheCaret()
    {
        vim("alpha\n").cursor(0, 2).keys("x");
        h.assertText("alha\n");
    }

    @Test
    public void xTakesACount()
    {
        vim("alphabet\n").cursor(0, 0).keys("3x");
        h.assertText("habet\n");
    }

    @Test
    public void capitalDDeletesToTheEndOfTheLine()
    {
        vim("alpha bravo\n").cursor(0, 5).keys("D");
        h.assertText("alpha\n");
    }

    @Test
    public void capitalCChangesToTheEndOfTheLine()
    {
        vim("alpha bravo\n").cursor(0, 6).keys("CX");
        h.assertText("alpha X\n");
    }

    @Test
    public void sSubstitutesOneCharacter()
    {
        vim("alpha\n").cursor(0, 0).keys("sX");
        h.assertText("Xlpha\n");
    }

    // ---------------------------------------------------------------- undo

    @Test
    public void aDeleteUndoesInOneStep()
    {
        vim("one two three\n").cursor(0, 0).keys("2dw");
        h.assertText("three\n");
        h.editor().undo();
        h.assertText("one two three\n");
    }

    @Test
    public void aChangeUndoesTheDeleteAndTheInsertTogether()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("cwXY<Esc>");
        h.assertText("XY bravo\n");
        h.editor().undo();
        h.assertText("alpha bravo\n");
    }

    // ------------------------------------------------- a motion that fails

    @Test
    public void anOperatorWhoseMotionFailsChangesNothing()
    {
        vim("alpha\n").cursor(0, 0).keys("dfz");
        h.assertText("alpha\n");
    }

    @Test
    public void escapeAbandonsAPendingOperator()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("d<Esc>w");
        h.assertText("alpha bravo\n");
        assertEquals(6, h.offset());
    }
}
