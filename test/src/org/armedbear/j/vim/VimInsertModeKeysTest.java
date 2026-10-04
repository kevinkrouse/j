/*
 * VimInsertModeKeysTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Insert-mode CTRL-W, CTRL-U, CTRL-R and CTRL-O, and the marks an
 * arrow or CTRL-O leaves when it splits an insert. Every expectation is
 * nvim's.
 */
public class VimInsertModeKeysTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text, int line, int offset) {
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset);
        return h;
    }

    /** Runs the keys and checks the text and where the caret ends up. */
    private void check(
        String text,
        int line,
        int offset,
        String keys,
        String expected,
        int endLine,
        int endOffset
    ) {
        vim(text, line, offset).keys(keys);
        assertEquals(expected, h.value(), keys);
        assertEquals(
            endLine + "," + endOffset,
            h.lineNumber() + "," + h.offset(),
            keys + " caret"
        );
    }

    // ---------------------------------------- CTRL-W

    @Test
    public void ctrlWDeletesTheWordBefore() {
        check("foo bar", 0, 6, "a<C-w><Esc>", "foo ", 0, 3);
        check("foo bar   ", 0, 9, "a<C-w><Esc>", "foo ", 0, 3);
        check("foo bar..", 0, 8, "a<C-w><Esc>", "foo bar", 0, 6);
        check("foo.bar", 0, 6, "a<C-w><Esc>", "foo.", 0, 3);
    }

    @Test
    public void ctrlWStopsOnceWhereTypingBegan() {
        check("foo bar", 0, 6, "axy<C-w><Esc>", "foo bar", 0, 6);
        check("foo bar", 0, 6, "axy<C-w><C-w><Esc>", "foo ", 0, 3);
        check("foo bar", 0, 6, "a xy<C-w><Esc>", "foo bar ", 0, 7);
    }

    @Test
    public void ctrlWAtTheStartOfALineJoinsIt() {
        check("foo\nbar", 1, 0, "i<C-w><Esc>", "foobar", 0, 2);
        check("foo\nbar", 1, 0, "i<C-w><C-w><Esc>", "bar", 0, 0);
    }

    @Test
    public void ctrlWStopsAtTheStartOfItsLine() {
        check("x foo\n  bar", 1, 2, "i<C-w><Esc>", "x foo\nbar", 1, 0);
    }

    @Test
    public void ctrlWInAnIndent() {
        check("    foo", 0, 4, "i<C-w><Esc>", "foo", 0, 0);
        check("    foo", 0, 6, "a<C-w><Esc>", "    ", 0, 3);
        check("    foo", 0, 6, "a<C-w><C-w><Esc>", "", 0, 0);
    }

    // ---------------------------------------- CTRL-U

    @Test
    public void ctrlUDeletesTheLineBefore() {
        check("foo bar", 0, 6, "a<C-u><Esc>", "", 0, 0);
        check("foo bar", 0, 6, "axy<C-u><Esc>", "foo bar", 0, 6);
        check("foo bar", 0, 6, "axy<C-u><C-u><Esc>", "", 0, 0);
    }

    @Test
    public void ctrlUKeepsTheIndentOnce() {
        check("    foo bar", 0, 10, "a<C-u><Esc>", "    ", 0, 3);
        check("    foo bar", 0, 10, "a<C-u><C-u><Esc>", "", 0, 0);
        check("    foo", 0, 2, "i<C-u><Esc>", "  foo", 0, 0);
    }

    @Test
    public void ctrlUAtTheStartOfALineJoinsIt() {
        check("foo\nbar", 1, 0, "i<C-u><Esc>", "foobar", 0, 2);
        check("foobar", 0, 3, "ixy<CR>ab<C-u><Esc>", "fooxy\nbar", 1, 0);
        check("foobar", 0, 3, "ixy<CR>ab<C-u><C-u><Esc>", "fooxybar", 0, 4);
    }

    @Test
    public void inReplaceModeTheyPutBackWhatWasTypedOver() {
        check("foo bar baz", 0, 4, "Rxy z<C-w><Esc>", "foo xy  baz", 0, 6);
        check(
            "foo bar baz",
            0,
            4,
            "Rxy z<C-w><C-w><Esc>",
            "foo bar baz",
            0,
            3
        );
        check("foo bar baz", 0, 4, "Rxy z<C-u><Esc>", "foo bar baz", 0, 3);
        check(
            "foo bar baz",
            0,
            4,
            "Rxy z<C-u><C-u><Esc>",
            "foo bar baz",
            0,
            0
        );
    }

    @Test
    public void dotAndACountTypeThemAgain() {
        final String text = "one two\nthree four";
        check(
            text,
            0,
            6,
            "aX Y<C-w>Z<Esc>j$.",
            "one twoX Z\nthree fourX Z",
            1,
            12
        );
        check(text, 0, 6, "a<C-w>Z<Esc>j$.", "one Z\nthree Z", 1, 6);
        check(text, 0, 6, "a<C-u>Z<Esc>j$.", "Z\nZ", 1, 0);
        check(
            "one two",
            0,
            6,
            "3aab cd<C-w>x<Esc>",
            "one twoab xab xab x",
            0,
            18
        );
    }

    @Test
    public void backspaceMovesTheStopOnlyOverALineBreak() {
        // Still at 3, so reaching it stops, and starting there does not.
        check("foo bar", 0, 3, "i<BS>yz<C-w><Esc>", "foy bar", 0, 2);
        check("foo bar", 0, 3, "i<BS>y<C-w><Esc>", " bar", 0, 0);
        // At the join.
        check("foo\nbar", 1, 0, "i<C-w>xy<C-w><Esc>", "foobar", 0, 2);
        check("foo\nbar", 1, 0, "i<BS>xy<C-u><Esc>", "foobar", 0, 2);
        // And when . types it again.
        check(
            "ab cd\nef\ngh cd\nij",
            1,
            0,
            "i<BS>xy<C-w><Esc>jj0.",
            "ab cdef\ngh cdij",
            1,
            4
        );
    }

    // ---------------------------------------- CTRL-R

    @Test
    public void ctrlRTypesARegister() {
        check("abc def", 0, 0, "yiwA <C-r>\"<Esc>", "abc def abc", 0, 10);
        check(
            "abc def",
            0,
            0,
            "\"ayiwwA <C-r>a!<Esc>",
            "abc def abc!",
            0,
            11
        );
        check("abc def", 0, 0, "dwA <C-r>-<Esc>", "def abc ", 0, 7);
        check("abc def", 0, 0, "yiwdwA <C-r>0<Esc>", "def abc", 0, 6);
    }

    @Test
    public void ctrlRTypesLineBreaksAsEnter() {
        check(
            "  abc\nx",
            0,
            0,
            "yyjA<C-r>\"z<Esc>",
            "  abc\nx  abc\nz",
            2,
            0
        );
        // Exactly as typing it: nvim indents the b under the a, and j's
        // Enter does too wherever it indents.
        vim("  a\nb\n", 0, 0).keys("2jA  a<CR>b<Esc>");
        final String typed = h.value();
        check("  a\nb\n", 0, 0, "vjy2jA<C-r>\"<Esc>", typed, 3, 0);
    }

    @Test
    public void ctrlRIndentsAsEnterDoesInJavaMode() {
        vim("b;\nc;\n{\n  a;", 0, 0).mode(JavaMode.getMode());
        h.buffer().setIndentSize(2);
        h.keys("vjyGA<CR><C-r>\"<Esc>");
        assertEquals("b;\nc;\n{\n  a;\n  b;\n  c", h.value());
        h.assertCursorAt(5, 2);
    }

    @Test
    public void dotTypesTheTextNotTheRegister() {
        check(
            "abc def\nx\ny",
            0,
            0,
            "yiwjA<C-r>\"<Esc>gg0wyiwjj.",
            "abc def\nxabc\nyabc",
            2,
            3
        );
    }

    @Test
    public void anEmptyRegisterOrEscapeTypesNothing() {
        check("abc", 0, 0, "A<C-r>q!<Esc>", "abc!", 0, 3);
        check("abc", 0, 0, "A<C-r><Esc>!<Esc>", "abc!", 0, 3);
        check("abc", 0, 0, "A<C-r><C-r>\"<Esc>", "abc", 0, 2);
        check("abc", 0, 0, "A<C-r><CR>x<Esc>", "abcx", 0, 3);
    }

    @Test
    public void ctrlRIsPartOfTheInsertsUndo() {
        vim("abc def\nx", 0, 0).keys("yiwjA<C-r>\"<Esc>u");
        assertEquals("abc def\nx", h.value());
    }

    // ---------------------------------------- CTRL-O

    @Test
    public void ctrlORunsOneCommand() {
        check("foo bar baz", 0, 4, "ix<C-o>wy<Esc>", "foo xbar ybaz", 0, 9);
        check("a b c d e", 0, 0, "i<C-o>3wy<Esc>", "a b c yd e", 0, 6);
        check("foo bar baz qux", 0, 0, "ix<C-o>2dwy<Esc>", "xybaz qux", 0, 1);
        check("foo bar", 0, 0, "ix<C-o>ddy<Esc>", "y", 0, 0);
        check("\nfoo", 0, 0, "i<C-o>jy<Esc>", "\nyfoo", 1, 0);
    }

    @Test
    public void ctrlOAtTheEndOfALineComesBackThere() {
        check("foo bar", 0, 0, "Ax<C-o>hy<Esc>", "foo bayrx", 0, 6);
        check("foo bar", 0, 0, "Ax<C-o>:<CR>y<Esc>", "foo barxy", 0, 8);
        check("foo bar", 0, 0, "Ax<C-o>0y<Esc>", "yfoo barx", 0, 0);
        check("foo bar", 0, 0, "A<C-o>$y<Esc>", "foo bary", 0, 7);
        // Still on this line, even after a command that forgets the column.
        check("foo bar", 0, 0, "Ax<C-o>xy<Esc>", "foo bary", 0, 7);
    }

    @Test
    public void jAndKAimPastTheEndAsTheInsertDid() {
        check(
            "foo bar\nxy\nlonger line",
            0,
            0,
            "A<C-o>jy<Esc>",
            "foo bar\nxyy\nlonger line",
            1,
            2
        );
        check(
            "foo\nlonger line",
            0,
            0,
            "A<C-o>jy<Esc>",
            "foo\nlonyger line",
            1,
            3
        );
    }

    @Test
    public void escapeOrAnUnfinishedCommandComesBack() {
        check("foo bar", 0, 3, "ix<C-o><Esc>y<Esc>", "fooxy bar", 0, 4);
        check("foo bar", 0, 0, "ix<C-o>d<Esc>y<Esc>", "xyfoo bar", 0, 1);
    }

    @Test
    public void ctrlOWaitsForARegisterSearchOrExLine() {
        check(
            "foo bar",
            0,
            0,
            "ix<C-o>\"ayiwy<Esc>$\"ap",
            "yxfoo barxfoo",
            0,
            12
        );
        check("a b c", 0, 0, "ix<C-o>/c<CR>y<Esc>", "xa b yc", 0, 5);
        check(
            "foo bar",
            0,
            0,
            "yiwA<C-o>:s/o/0/<CR><C-r>\"<Esc>",
            "foof0o bar",
            0,
            2
        );
    }

    @Test
    public void anAbandonedExLineComesBack() {
        check("foo", 0, 0, "ix<C-o>:<Esc>y<Esc>", "xyfoo", 0, 1);
    }

    @Test
    public void theColumnIsTakenAfreshAtEachCtrlO() {
        check(
            "foo bar",
            0,
            0,
            "A<C-o>:<CR><Left><C-o>:<CR>X<Esc>",
            "foo baXr",
            0,
            6
        );
    }

    @Test
    public void dotRunsInsideCtrlO() {
        check("abcd", 0, 0, "xi<C-o>.y<Esc>", "ycd", 0, 0);
    }

    @Test
    public void theInsertBeforeCtrlOIsTheChangeOnlyAfterIt() {
        // Inside, . is still the x; after, the insert.
        check("abcdef", 0, 0, "xiXY<C-o>.<Esc>", "XYcdef", 0, 1);
        check("abcdef", 0, 0, "xiXY<C-o>:<CR><Esc>0.", "XYXYbcdef", 0, 1);
    }

    @Test
    public void normalEndingInCtrlOLeavesNormalMode() {
        vim("foo", 0, 0).exCommand("normal ix<C-o>");
        assertEquals("xfoo", h.value());
        assertEquals(null, h.vimModeIndicator());
    }

    @Test
    public void visualModeFromCtrlOComesBackAfterIt() {
        check("foo bar baz", 0, 0, "ix<C-o>vedy<Esc>", "xy bar baz", 0, 1);
        check("foo bar baz", 0, 0, "ix<C-o>vely<Esc>", "xfoo bar baz", 0, 0);
    }

    @Test
    public void aCommandThatInsertsStaysInIt() {
        check("foo bar", 0, 0, "ix<C-o>oy<Esc>", "xfoo bar\ny", 1, 0);
        // And . repeats it.
        check("foo bar", 0, 0, "ix<C-o>oy<Esc>.", "xfoo bar\ny\ny", 2, 0);
    }

    @Test
    public void ctrlOInReplaceModeComesBackToIt() {
        check("foo bar baz", 0, 0, "Rx<C-o>wy<Esc>", "xoo yar baz", 0, 4);
    }

    @Test
    public void ctrlOTakesAnIndentNothingWasTypedAfter() {
        // Java mode, where Enter indents.
        vim("{\n  x;", 1, 0).mode(JavaMode.getMode());
        h.buffer().setIndentSize(2);
        h.keys("A<CR><C-o>:<CR>y<Esc>");
        assertEquals("{\n  x;\ny", h.value());
        h.assertCursorAt(2, 0);
    }

    @Test
    public void ctrlOSplitsTheInsert() {
        // The count is dropped, and . repeats only what came after, as an i.
        check("foo bar", 0, 0, "3ix<C-o>wy<Esc>", "xfoo ybar", 0, 5);
        final String text = "foo bar\nbaz qux";
        check(text, 0, 0, "ix<C-o>wy<Esc>j0.", "xfoo ybar\nybaz qux", 1, 0);
        check(text, 0, 0, "ix<C-o>dwy<Esc>j0.", "xybar\nybaz qux", 1, 0);
        // Nothing typed after: . is the command.
        check(text, 0, 0, "ix<C-o>dw<Esc>j0.", "xbar\nqux", 1, 0);
        // u takes back only the part before.
        check("foo bar", 0, 0, "ix<C-o>uy<Esc>", "yfoo bar", 0, 0);
    }

    @Test
    public void eachPartUndoesOnItsOwn() {
        vim("foo bar", 0, 0).keys("ix<C-o>wy<Esc>u");
        assertEquals("xfoo bar", h.value());
        h.keys("u");
        assertEquals("foo bar", h.value());
    }

    @Test
    public void theModeShowsWhereCtrlOGoesBack() {
        vim("foo bar", 0, 0).keys("i<C-o>");
        assertEquals("(insert)", h.vimModeIndicator());
        h.keys("v");
        assertEquals("(insert) VISUAL", h.vimModeIndicator());
        h.keys("<Esc>");
        assertEquals("INSERT", h.vimModeIndicator());
        vim("foo bar", 0, 0).keys("R<C-o>");
        assertEquals("(replace)", h.vimModeIndicator());
    }

    @Test
    public void ctrlOInNormalIsStillCtrlO() {
        vim("a\nb\nc", 0, 0).keys("G<C-o>");
        h.assertCursorAt(0, 0);
    }

    // ---------------------------------------- marks after a split

    private static final String TEXT = "foo bar baz";

    /** Where `name lands after the keys, from the end of the buffer. */
    private String mark(String keys, char name) {
        vim(TEXT, 0, 0).keys(keys).keys("G$`" + name);
        return h.lineNumber() + "," + h.offset();
    }

    @Test
    public void aSplitMarksThePartBefore() {
        assertEquals("0,4", mark("wix<C-o>w<Esc>", '['));
        assertEquals("0,5", mark("wix<C-o>w<Esc>", ']'));
        assertEquals("0,4", mark("wix<C-o>w<Esc>", '.'));
        assertEquals("0,9", mark("wix<C-o>w<Esc>", '^'));
        assertEquals("0,4", mark("wi<C-o>w<Esc>", '['));
        assertEquals("0,9", mark("wix<C-o>wy<Esc>", '['));
        assertEquals("0,9", mark("wix<C-o>wy<Esc>", '.'));
    }

    @Test
    public void anArrowMarksThePartBefore() {
        assertEquals("0,4", mark("wix<Right><Esc>", '['));
        assertEquals("0,5", mark("wix<Right><Esc>", ']'));
        assertEquals("0,4", mark("wix<Right><Esc>", '.'));
        assertEquals("0,4", mark("wix<Right><Right><Esc>", '['));
        assertEquals("0,5", mark("wix<Right><Right><Esc>", ']'));
        assertEquals("0,5", mark("wi<Right>x<Esc>", '['));
        assertEquals("0,4", mark("wi<Right><Right><Esc>", '['));
    }

    @Test
    public void backspaceMovesTheStartBackOnlyOverALineBreak() {
        assertEquals("0,4", mark("wixy<BS><BS><BS>ab<Esc>", '['));
        vim("foo bar\nzz", 1, 0).keys("i<BS><Esc>gg0`[");
        h.assertCursorAt(0, 7);
        vim("foo bar\nzz", 1, 0).keys("i<C-w><Esc>gg0`[");
        h.assertCursorAt(0, 7);
    }
}
