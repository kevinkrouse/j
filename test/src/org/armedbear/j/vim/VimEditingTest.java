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

    // j's UndoInsertString works out how much to take back from where the
    // caret is when undo runs, not from where the insert left it. So any
    // command that repositions the caret after editing has to record that
    // move, or undo takes back the wrong lines. Each of these corrupted the
    // buffer before it did.

    /** Runs keys, undoes once, and requires the text to be back as it was. */
    private void undoRestores(String start, String keys)
    {
        h = EditorHarness.create().vim();
        h.value(start).cursor(0, 0).keys(keys);
        h.keys("u");
        assertEquals(start + " after " + keys + " then u", start, h.value());
        h.close();
        h = null;
    }

    @Test
    public void undoingAPutInTheMiddleOfTheBufferPutsItBack()
    {
        // Used to duplicate the line below instead of removing the pasted one.
        undoRestores("abcdef\nx", "yyp");
        undoRestores("abcdef\nx", "yyP");
        undoRestores("one\ntwo\nthree", "jyyp");
    }

    @Test
    public void undoingACharacterPutPutsItBack()
    {
        undoRestores("abc\ndef", "ylp");
    }

    @Test
    public void undoingACountedJoinPutsEveryLineBack()
    {
        // 3J then u used to lose a line outright.
        undoRestores("a\nb\nc\nd", "3J");
        undoRestores("one\ntwo\nthree\nfour", "4J");
    }

    /** Runs keys, undoes once, and checks where the caret came back to. */
    private void undoLeavesCaretAt(String start, int line, int offset,
                                   String keys, int wantLine, int wantOffset)
    {
        h = EditorHarness.create().vim();
        h.value(start).cursor(line, offset).keys(keys);
        h.keys("u");
        assertEquals(keys + ": text", start, h.value());
        assertEquals(keys + ": line", wantLine, h.lineNumber());
        assertEquals(keys + ": offset", wantOffset, h.offset());
        h.close();
        h = null;
    }

    @Test
    public void undoPutsTheCaretBackWhereTheCommandFoundIt()
    {
        // A command that edits moves the caret to what it is changing, and
        // that move belongs to the change: undo has to give back the caret
        // as well as the text. Every expectation checked against nvim.
        undoLeavesCaretAt("abcdef\nx", 0, 0, "yyp", 0, 0);
        undoLeavesCaretAt("abcdef\nx", 0, 0, "yyP", 0, 0);
        undoLeavesCaretAt("one\ntwo\nx", 1, 0, "yyp", 1, 0);
        undoLeavesCaretAt("abc\ndef", 0, 1, "ylp", 0, 1);
        undoLeavesCaretAt("one\ntwo\nx", 0, 1, "J", 0, 1);
        undoLeavesCaretAt("a\nb\nc\nd", 0, 0, "3J", 0, 0);
        undoLeavesCaretAt("one\ntwo\nx", 0, 1, "dd", 0, 1);
        undoLeavesCaretAt("one\ntwo\nthree", 1, 1, "dj", 1, 1);
        undoLeavesCaretAt("one\ntwo", 0, 1, "oZ<Esc>", 0, 1);
        undoLeavesCaretAt("one\ntwo", 1, 1, "OZ<Esc>", 1, 1);
        undoLeavesCaretAt("abc\nx", 0, 1, ">>", 0, 1);
    }

    @Test
    public void theWholeYankPutUndoSequenceFromAnEmptyBuffer()
    {
        h = EditorHarness.create().vim();
        h.value("").cursor(0, 0).keys("iabcdef<Esc>");
        assertEquals("abcdef", h.value());
        h.keys("yyp");
        assertEquals("abcdef\nabcdef", h.value());
        h.keys("u");
        assertEquals("the duplicate goes", "abcdef", h.value());
        h.keys("u");
        assertEquals("then the insert", "", h.value());
    }

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

    // R types over what is already there. Every expectation below was taken
    // from nvim; R was unbound before, so it fell through and the keys after
    // it ran as normal-mode commands.

    @Test
    public void capitalRTypesOverTheCharactersItPassesOver()
    {
        vim("abcdef\n").cursor(0, 0).keys("Rxy<Esc>");
        h.assertText("xycdef\n");
        at(0, 1);
    }

    @Test
    public void replaceModeAppendsOnceItRunsOffTheEndOfTheLine()
    {
        // R can lengthen a line, never shorten one.
        vim("ab\n").cursor(0, 0).keys("Rxyz<Esc>");
        h.assertText("xyz\n");
        at(0, 2);
    }

    @Test
    public void backspaceInReplaceModePutsBackWhatWasTypedOver()
    {
        // Not a delete: it walks the session backwards.
        vim("abcdef\n").cursor(0, 2).keys("Rxy<BS><BS><Esc>");
        h.assertText("abcdef\n");
        at(0, 1);
    }

    @Test
    public void backspacePastTheStartOfTheReplaceOnlyMovesTheCaret()
    {
        // The text to the left was never this session's to give back.
        vim("abcdef\n").cursor(0, 2).keys("Rxy<BS><BS><BS><BS><Esc>");
        h.assertText("abcdef\n");
        at(0, 0);

        h.close();
        vim("abcdef\n").cursor(0, 3).keys("R<BS><BS><Esc>");
        h.assertText("abcdef\n");
        at(0, 0);
    }

    @Test
    public void backspaceTakesAwayACharacterReplaceModeAppended()
    {
        // Past the old end of the line there was nothing to put back, so the
        // character goes instead.
        vim("ab\n").cursor(0, 0).keys("Rxyz<BS><Esc>");
        h.assertText("xy\n");
        at(0, 1);
    }

    @Test
    public void undoTakesBackTheWholeReplaceSession()
    {
        undoLeavesCaretAt("abcdef\nx", 0, 1, "Rxy<Esc>", 0, 1);
    }

    @Test
    public void dotRepeatsAReplace()
    {
        vim("abcdef\nabcdef\n").cursor(0, 0).keys("Rxy<Esc>");
        h.assertText("xycdef\nabcdef\n");
        h.keys("j0.");
        h.assertText("xycdef\nxycdef\n");
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
