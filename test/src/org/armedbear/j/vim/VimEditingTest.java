/*
 * VimEditingTest.java
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
 * Undo, join, replace, case and indent. Expectations were checked against real
 * nvim with tools/vim-oracle.sh.
 */
public class VimEditingTest
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

    // ------------------------------------------------------------ undo/redo

    @Test
    public void uUndoesAndCtrlRRedoes()
    {
        vim("abcd\n").cursor(0, 0).keys("x");
        h.assertText("bcd\n");
        h.keys("u");
        h.assertText("abcd\n");
        h.keys("<C-r>");
        h.assertText("bcd\n");
    }

    @Test
    public void uUndoesAWholeInsertSession()
    {
        vim("abc\n").cursor(0, 0).keys("iXY<Esc>u");
        h.assertText("abc\n");
    }

    @Test
    public void uTakesACount()
    {
        vim("abcd\n").cursor(0, 0).keys("xxx");
        h.assertText("d\n");
        h.keys("2u");
        h.assertText("bcd\n");
    }

    // ---------------------------------------------------------------- join

    @Test
    public void jJoinsTwoLinesWithASpace()
    {
        vim("one\ntwo\n").cursor(0, 0).keys("J");
        h.assertText("one two\n");
        at(0, 3);
    }

    @Test
    public void joinDropsTheIndentOfTheLinePulledUp()
    {
        vim("one\n    two\n").cursor(0, 0).keys("J");
        h.assertText("one two\n");
    }

    @Test
    public void joinDoesNotAddASpaceWhenThereIsOne()
    {
        // And it keeps the trailing whitespace already on the line.
        vim("  a  \n   b\n").cursor(0, 0).keys("J");
        h.assertText("  a  b\n");
        at(0, 5);
    }

    @Test
    public void joinWithACountJoinsThatManyLines()
    {
        vim("a\nb\nc\nd\n").cursor(0, 0).keys("3J");
        h.assertText("a b c\nd\n");
    }

    @Test
    public void gJJoinsWithoutTouchingWhitespace()
    {
        vim("one\n    two\n").cursor(0, 0).keys("gJ");
        h.assertText("one    two\n");
    }

    @Test
    public void joinAtTheLastLineDoesNothing()
    {
        vim("only\n").cursor(0, 0).keys("J");
        h.assertText("only\n");
    }

    // ------------------------------------------------------------- replace

    @Test
    public void rReplacesOneCharacter()
    {
        vim("abc\n").cursor(0, 0).keys("rX");
        h.assertText("Xbc\n");
        at(0, 0);
    }

    @Test
    public void rWithACountReplacesThatMany()
    {
        vim("abc\n").cursor(0, 0).keys("3rX");
        h.assertText("XXX\n");
        at(0, 2);
    }

    @Test
    public void rRefusesWhenThereAreNotEnoughCharacters()
    {
        // Vim does none of it rather than part of it.
        vim("abc\n").cursor(0, 0).keys("5rX");
        h.assertText("abc\n");
    }

    // ---------------------------------------------------------------- case

    @Test
    public void tildeSwapsCaseAndMovesOn()
    {
        vim("abc\n").cursor(0, 0).keys("~");
        h.assertText("Abc\n");
        at(0, 1);
    }

    @Test
    public void tildeTakesACount()
    {
        vim("abc\n").cursor(0, 0).keys("3~");
        h.assertText("ABC\n");
        at(0, 2);
    }

    @Test
    public void gUUpperCasesAMotionAndStaysPut()
    {
        vim("abc def\n").cursor(0, 0).keys("gUw");
        h.assertText("ABC def\n");
        at(0, 0);
    }

    @Test
    public void guLowerCases()
    {
        vim("ABC DEF\n").cursor(0, 0).keys("guw");
        h.assertText("abc DEF\n");
    }

    @Test
    public void gTildeToggles()
    {
        vim("aBc\n").cursor(0, 0).keys("g~$");
        h.assertText("AbC\n");
    }

    @Test
    public void caseOperatorsWorkInVisualMode()
    {
        vim("abcdef\n").cursor(0, 0).keys("vllgU");
        h.assertText("ABCdef\n");
    }

    // -------------------------------------------------------------- indent

    @Test
    public void shiftRightAndLeftMoveByOneShiftwidth()
    {
        vim("x\n").cursor(0, 0);
        final int width = h.buffer().getIndentSize();
        h.keys(">>");
        assertEquals(width, h.buffer().getIndentation(h.buffer().getFirstLine()));
        h.keys("<<");
        assertEquals(0, h.buffer().getIndentation(h.buffer().getFirstLine()));
    }

    @Test
    public void shiftLeftStopsAtColumnZero()
    {
        vim("x\n").cursor(0, 0).keys("<<");
        h.assertText("x\n");
    }

    @Test
    public void shiftAppliesToEveryLineAMotionCovers()
    {
        vim("a\nb\nc\n").cursor(0, 0).keys(">j");
        final int width = h.buffer().getIndentSize();
        assertEquals(width, h.buffer().getIndentation(h.buffer().getFirstLine()));
        assertEquals(width,
                     h.buffer().getIndentation(h.buffer().getFirstLine().next()));
        assertEquals("the third line is untouched", 0,
                     h.buffer().getIndentation(
                         h.buffer().getFirstLine().next().next()));
    }

    // ------------------------------------------------------------- % and HML

    @Test
    public void percentGoesToTheMatchingBracket()
    {
        vim("(a(b)c)\n").cursor(0, 0).keys("%");
        at(0, 6);
        h.keys("%");
        at(0, 0);
    }

    @Test
    public void percentFindsTheFirstBracketOnTheLine()
    {
        vim("x = (a + b)\n").cursor(0, 0).keys("%");
        at(0, 10);
    }

    @Test
    public void percentWithNoBracketDoesNothing()
    {
        vim("abc\n").cursor(0, 1).keys("%");
        at(0, 1);
    }

    @Test
    public void capitalHAndLGoToTheTopAndBottomOfTheWindow()
    {
        vim("1\n2\n3\n4\n5\n").cursor(2, 0).keys("H");
        at(0, 0);
        h.keys("L");
        assertEquals("the last line on screen", 4, h.lineNumber());
        h.keys("M");
        assertEquals(2, h.lineNumber());
    }

    @Test
    public void deleteCanUseAMatchingBracketAsItsMotion()
    {
        vim("(abc)d\n").cursor(0, 0).keys("d%");
        h.assertText("d\n");
    }
}
