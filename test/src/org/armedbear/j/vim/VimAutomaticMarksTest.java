/*
 * VimAutomaticMarksTest.java
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The marks vim keeps for itself -- '. '[ '] '^ -- and the jump list.
 * Every expectation is nvim's.
 */
public class VimAutomaticMarksTest {
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

    // ---------------------------------------- '. '[ '] '^

    private static final String TEXT = "one two three\nfour five\nsix";
    private static final String LINES = "a1\nb1\nc1\nd1\ne1";

    /**
     * Runs keys from 0,offset of the text, then goes to each mark from the
     * end of the buffer -- so a mark not set leaves the caret there, at "G$".
     *
     * @param expected "line,offset" for ` . [ ] and ^, in that order; null
     *                 for one not checked
     */
    private void marks(String text, int offset, String keys, String... expected) {
        final String[] names = { ".", "[", "]", "^" };
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] == null)
                continue;
            vim(text, 0, offset).keys(keys);
            h.keys("G$`" + names[i]);
            assertEquals(
                expected[i],
                h.lineNumber() + "," + h.offset(),
                keys + " `" + names[i]
            );
        }
    }

    private static final String UNSET = "G$";

    /** Where G$ lands after the keys, standing for a mark not set. */
    private String end(String text, int offset, String keys) {
        vim(text, 0, offset).keys(keys).keys("G$");
        return h.lineNumber() + "," + h.offset();
    }

    private String[] resolve(
        String text,
        int offset,
        String keys,
        String... expected
    ) {
        final String[] out = expected.clone();
        for (int i = 0; i < out.length; i++)
            if (UNSET.equals(out[i]))
                out[i] = end(text, offset, keys);
        return out;
    }

    private void check(String keys, String... expected) {
        marks(TEXT, 4, keys, resolve(TEXT, 4, keys, expected));
    }

    private void checkLines(String keys, String... expected) {
        marks(LINES, 1, keys, resolve(LINES, 1, keys, expected));
    }

    @Test
    public void deleteMarksWhereTheTextWas() {
        check("dw", "0,4", "0,4", "0,4", UNSET);
        check("x", "0,4", "0,4", "0,4", UNSET);
        check("d}", "0,3", "0,3", "0,3", "0,3");
    }

    @Test
    public void aLinewiseDeleteKeepsTheCaretColumnInTheMarks() {
        check("dd", "0,0", "0,4", "0,4");
        check("Vjd", "0,0", "0,0", "0,0");
    }

    @Test
    public void yankSetsTheBracketsButNotTheChange() {
        check("yw", UNSET, "0,4", "0,7", UNSET);
        check("yiw", UNSET, "0,4", "0,6");
        check("vey", UNSET, "0,4", "0,6");
        check("yj", UNSET, "0,0", "1,8");
    }

    @Test
    public void insertMarksWhereTypingBeganAndStopped() {
        check("iab<Esc>", "0,4", "0,4", "0,6", "0,6");
        check("aab<Esc>", "0,5", "0,5", "0,7", "0,7");
        check("cwXY<Esc>", "0,4", "0,4", "0,6", "0,6");
        check("oab<Esc>", "1,0", "1,0", "1,1", "1,1");
    }

    @Test
    public void anInsertOverSeveralLinesMarksItsLastLine() {
        check("ia<CR>b<Esc>", "1,0", "0,4", "1,1", "1,1");
    }

    @Test
    public void anInsertThatTypedNothingIsNoChange() {
        check("i<Esc>", UNSET, "0,4", "0,4", "0,4");
    }

    @Test
    public void putMarksWhatItPut() {
        check("ywP", "0,4", "0,4", "0,7");
        check("ywp", "0,5", "0,5", "0,8");
        check("yyp", "1,0", "1,0", "1,12");
        check("yyP", "0,0", "0,0", "0,12");
    }

    @Test
    public void singleCharacterChanges() {
        check("rX", "0,4", "0,4", "0,4", UNSET);
        check("~", "0,4", "0,4", "0,4", UNSET);
    }

    @Test
    public void joinMarksTheJoin() {
        check("J", null, "0,13", "0,22");
    }

    @Test
    public void shiftMarksTheLines() {
        // '] on the last character: 0,13 in nvim, which shifts by a tab;
        // j shifts by its indent size of four spaces.
        check(">>", "0,0", "0,4", "0,16");
    }

    @Test
    public void aCaseChangeThatChangesNothingIsNoChange() {
        check("guw", UNSET, "0,4", "0,7");
    }

    @Test
    public void exCommandsMarkLines() {
        check(":s/two/2/<CR>", "0,0", "0,0", "0,0");
        checkLines(":%s/1/2/<CR>", "0,0", "0,0", "4,0");
        checkLines(":2,4s/c/X/<CR>", "2,0", "1,0", "3,0");
        checkLines(":2,3d<CR>", "1,0", "1,0", "1,0");
        checkLines(":1t3<CR>", "3,0", "3,0", "3,0");
        checkLines(":2,3y<CR>", UNSET, "1,0", "2,1");
        checkLines(":1,2j<CR>", null, "0,2", "0,4");
    }

    @Test
    public void visualChangesMarkTheSelection() {
        check("vlrX", "0,4", "0,4", "0,5");
        check("yiwwviwp", "0,8", "0,8", "0,10");
        check("yyjVp", "1,0", "1,0", "1,12");
    }

    @Test
    public void moveMarksTheLinesMoved() {
        // '. is where they came from in nvim, 0,0; here it is '[.
        checkLines(":1m3<CR>", null, "2,0", "2,0");
        checkLines(":1,2m4<CR>", null, "2,0", "3,0");
    }

    @Test
    public void bracketMarksWorkAsAnExRange() {
        vim(LINES, 0, 0).keys("yj");
        h.exCommand("'[,']d");
        assertEquals("c1\nd1\ne1", h.value());
    }

    // ---------------------------------------- the jump list

    private static final String TEN = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10";

    /** Each step's keys, then where the caret is: "line,offset ...". */
    private String jumps(String text, String... steps) {
        // From column 0: G keeps the column in nvim (nostartofline) and
        // goes to the first non-blank here, which agree only there.
        vim(text, 0, 0);
        final StringBuilder sb = new StringBuilder();
        for (String keys : steps) {
            h.keys(keys);
            sb.append(sb.length() == 0 ? "" : " ")
                .append(h.lineNumber())
                .append(',')
                .append(h.offset());
        }
        return sb.toString();
    }

    @Test
    public void ctrlOAndCtrlIWalkTheJumps() {
        assertEquals(
            "4,0 9,0 4,0 0,0 4,0 9,0 9,0",
            jumps(
                TEN,
                "5G",
                "10G",
                "<C-o>",
                "<C-o>",
                "<C-i>",
                "<C-i>",
                "<C-i>"
            )
        );
    }

    @Test
    public void tabIsCtrlI() {
        assertEquals("4,0 0,0 4,0", jumps(TEN, "5G", "<C-o>", "<Tab>"));
    }

    @Test
    public void aLineIsListedOnceAtItsLatest() {
        assertEquals(
            "2,0 4,0 2,0 4,0 0,0 0,0",
            jumps(TEN, "3G", "5G", "3G", "<C-o>", "<C-o>", "<C-o>")
        );
    }

    @Test
    public void aJumpAfterCtrlOGoesOnTheEnd() {
        assertEquals(
            "2,0 5,0 2,0 8,0 2,0 5,0 0,0",
            jumps(
                TEN,
                "3G",
                "6G",
                "<C-o>",
                "9G",
                "<C-o>",
                "<C-o>",
                "<C-o>"
            )
        );
    }

    @Test
    public void ctrlOTakesACount() {
        assertEquals("2,0 5,0 8,0 2,0", jumps(TEN, "3G", "6G", "9G", "2<C-o>"));
    }

    @Test
    public void motionsThatAreNotJumpsAreNotListed() {
        assertEquals("4,0 6,0 0,0", jumps(TEN, "5G", "jj", "<C-o>"));
    }

    @Test
    public void quoteQuoteGoesBackAndForth() {
        assertEquals("4,0 0,0 4,0", jumps(TEN, "5G", "''", "''"));
        assertEquals("3,1 0,0 3,1", jumps(TEN, "4G$", "gg0", "``"));
    }

    @Test
    public void theJumps() {
        final String text = TEN.replace("l10", "l10 (x) y");
        assertEquals("4,0 0,0", jumps(text, "/l5<CR>", "''"));
        assertEquals("9,6 9,0", jumps(text, "G%", "''"));
        assertEquals("9,8 0,0", jumps(text, "}", "''"));
        assertEquals("4,0 0,0 4,0 0,0", jumps(text, "5Gma", "gg", "'a", "''"));
        assertEquals("5,0 0,0", jumps(text, ":6<CR>", "''"));
        assertEquals("3,0 6,0 3,0", jumps(TEN, "4G", ":7s/l/L/<CR>", "''"));
    }

    @Test
    public void aJumpUnderAnOperatorIsNotListed() {
        assertEquals("3,0 1,0 1,0 3,0", jumps(TEN, "4G", "2G", "y6G", "''"));
    }

    @Test
    public void globalIsOneJump() {
        // Where :g leaves the caret differs (it runs bottom up here), so the
        // test is only that '' goes back to before it and CTRL-O returns.
        vim("a1\nb1\na2\nb2\na3", 1, 1).keys(":g/a/s/a/A/<CR>");
        final int line = h.lineNumber();
        final int offset = h.offset();
        h.keys("''");
        h.assertCursorAt(1, 0);
        h.keys("<C-o>");
        h.assertCursorAt(line, offset);
    }

    @Test
    public void mQuoteSetsThePreviousContext() {
        assertEquals("4,0 4,0 6,0 4,0", jumps(TEN, "5G", "m'", "jj", "''"));
    }

    @Test
    public void leavingTheEndOfTheListIsAJump() {
        assertEquals(
            "4,0 9,0 4,0 9,0",
            jumps(TEN, "5G", "10G", "<C-o>", "''")
        );
    }

    @Test
    public void exDeleteIsAJump() {
        assertEquals("4,0 6,0 4,0", jumps(TEN, "5G", ":7d<CR>", "''"));
    }

    @Test
    public void aJumpFromADeletedLineMovesOn() {
        // A jump from a deleted line moves to the line after the delete, as
        // in nvim: the jump list is j's markers, which follow their text.
        assertEquals(
            "7,0 4,0 0,0",
            jumps(TEN, "5G10Gk:5d<CR>jj<C-o>", "<C-o>", "<C-o>")
        );
        assertEquals(
            "6,0 3,0 0,0",
            jumps(TEN, "5G10Gk:4,5d<CR>jj<C-o>", "<C-o>", "<C-o>")
        );
    }

    @Test
    public void anInsertBeganWhereItsFirstKeyWasTyped() {
        // Enter first: '[ is where it was pressed, not the next line.
        vim("one two", 0, 4).keys("i<CR>x<Esc>G$`[");
        h.assertCursorAt(0, 3);
    }

    @Test
    public void aMarkOnADeletedLineIsGone() {
        vim(TEN, 0, 0).keys("5Gmagg4Gd2d");
        h.keys("gg'a");
        h.assertCursorAt(0, 0);
    }

    @Test
    public void backtickBracketSkipsTheChangeMarks() {
        // ]` goes by lowercase marks only.
        vim(TEXT, 1, 0).keys("xgg]`");
        h.assertCursorAt(0, 0);
    }
}
