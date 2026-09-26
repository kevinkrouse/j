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
import org.armedbear.j.mode.java.JavaMode;
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
    // ------------------------------------------- Enter and autoindent
    //
    // Enter is whatever j binds it to in the mode: newlineAndIndent in Java
    // mode, plain newline in plain text.

    private EditorHarness java(String text, int line, int offset)
    {
        vim(text, line, offset).mode(JavaMode.getMode());
        // So that j's indent inside a brace is the two vim copies.
        h.buffer().setIndentSize(2);
        return h;
    }

    @Test
    public void enterThenEscapeLeavesAnEmptyLine()
    {
        java("{\n  x;", 1, 0).keys("A<CR><Esc>");
        assertEquals("{\n  x;\n", h.value());
        h.assertCursorAt(2, 0);
    }

    @Test
    public void typingAfterEnterKeepsTheIndent()
    {
        java("{\n  x;", 1, 0).keys("A<CR>y<Esc>");
        assertEquals("{\n  x;\n  y", h.value());
        h.assertCursorAt(2, 2);
    }

    @Test
    public void enterOnAnUntouchedIndentEmptiesThatLine()
    {
        java("{\n  x;", 1, 0).keys("o<CR>y<Esc>");
        assertEquals("{\n  x;\n\n  y", h.value());
        h.assertCursorAt(3, 2);
    }

    @Test
    public void twoEntersThenEscapeLeaveTwoEmptyLines()
    {
        java("{\n  x;", 1, 0).keys("A<CR><CR><Esc>");
        assertEquals("{\n  x;\n\n", h.value());
        h.assertCursorAt(3, 0);
    }

    @Test
    public void undoTakesTheEnterBack()
    {
        java("{\n  x;", 1, 0).keys("A<CR><Esc>").keys("u");
        assertEquals("{\n  x;", h.value());
    }

    @Test
    public void dotRepeatsEnterTheSameWay()
    {
        java("{\n  x;\n  z;", 1, 0).keys("A<CR><Esc>").keys("j.");
        assertEquals("{\n  x;\n\n  z;\n", h.value());
    }
    @Test
    public void dotRepeatsEnterThroughJsBinding()
    {
        java("{\n  x;\n  z;", 1, 0).keys("A<CR>y<Esc>").keys("j.");
        assertEquals("{\n  x;\n  y\n  z;\n  y", h.value());
    }

    @Test
    public void inPlainTextEnterIsJsNewline()
    {
        // j binds plain newline there, which does not indent, where vim's
        // autoindent would copy the indent.
        vim("  x", 0, 0).keys("A<CR>y<Esc>");
        assertEquals("  x\ny", h.value());
    }
    // ------------------------------------------------- o and O indent
    //
    // Both indent by the language where j's mode knows it, as nvim does with
    // filetype indent, and copy the line's indent where it does not.

    @Test
    public void openAboveAClosingBraceIndentsTheBody()
    {
        java("class A {\n  x();\n}", 2, 0).keys("Oy<Esc>");
        assertEquals("class A {\n  x();\n  y\n}", h.value());
        h.assertCursorAt(2, 2);
    }

    @Test
    public void openAboveAStatementIndentsLikeIt()
    {
        java("class A {\n  x();\n}", 1, 0).keys("Oy<Esc>");
        assertEquals("class A {\n  y\n  x();\n}", h.value());
        h.assertCursorAt(1, 2);
    }

    @Test
    public void openAboveInPlainTextCopiesTheLine()
    {
        vim("a\n  b", 1, 0).keys("Oy<Esc>");
        assertEquals("a\n  y\n  b", h.value());
        h.assertCursorAt(1, 2);
    }
}
