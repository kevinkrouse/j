/*
 * VimM26Test.java
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
 * M26: visual block mode, CTRL-V, on j's Block. Every expectation is nvim's.
 */
public class VimM26Test {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /** The text and caret the keys leave: "text @line,offset". */
    private String run(String text, int line, int offset, String keys) {
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset).keys(keys);
        return h.value() + " @" + h.lineNumber() + "," + h.offset();
    }

    private static final String DIGITS = "1234\n5678\nabcdefg";

    @Test
    public void deleteABlock() {
        assertEquals("134\n578\nacdefg @0,1", run(DIGITS, 0, 1, "<C-v>2jd"));
        // The first line is left with nothing past the caret, so it steps back.
        assertEquals("1\n5\nafg @0,0", run(DIGITS, 0, 1, "<C-v>2jllld"));
        assertEquals(
            "ad\neh\nijkl @0,1",
            run("abcd\nefgh\nijkl", 0, 1, "<C-v>jlx")
        );
    }

    @Test
    public void dollarMakesItRagged() {
        assertEquals("a\nc\ng @0,0", run("ab\ncdef\ngh", 0, 1, "<C-v>2j$d"));
        assertEquals("1\n5\na @0,0", run(DIGITS, 0, 1, "<C-v>2jlD"));
    }

    @Test
    public void xAndYTakeTheBlockItself() {
        assertEquals(
            "ad\neh\nijkl @0,1",
            run("abcd\nefgh\nijkl", 0, 1, "<C-v>jlX")
        );
        run("abcd\nefgh\nijkl", 0, 1, "<C-v>jlY");
        assertEquals("bc\nfg", VimRegisters.getInstance().get('"').text);
        assertEquals(
            VimRegisters.Type.BLOCKWISE,
            VimRegisters.getInstance().get('"').type
        );
    }

    @Test
    public void aTabTheEdgeCutsThroughIsSplit() {
        assertEquals(
            "a  c\ncdel @0,3",
            run("a\tbc\ncdefghijkl", 1, 3, "<C-v>kld")
        );
        // And on the right.
        assertEquals(
            "efghij\n    b @0,0",
            run("abcdefghij\na\tb", 1, 0, "<C-v>k3ld")
        );
    }

    @Test
    public void changeABlock() {
        assertEquals(
            "1hello\n5hello\nahellofg @0,5",
            run(DIGITS, 0, 1, "<C-v>2jlllchello<Esc>")
        );
        assertEquals(
            "1{\n5{\na{fg @0,1",
            run(DIGITS, 0, 1, "<C-v>2jllls{<Esc>")
        );
    }

    @Test
    public void insertAndAppend() {
        assertEquals(
            "hellotest\nhellome\nhelloplease @0,0",
            run("test\nme\nplease", 0, 0, "<C-v>2jllIhello<Esc>")
        );
        // A short line is padded out to the block's right edge.
        assertEquals(
            "testhello\nme  hello\npleahellose @0,1",
            run("test\nme\nplease", 0, 1, "<C-v>2jllAhello<Esc>")
        );
        // The first line too, before the insert.
        assertEquals(
            "me  X\ntestX @0,2",
            run("me\ntest", 1, 3, "<C-v>kAX<Esc>")
        );
        assertEquals(
            "abX\ncdefX\nghX @0,1",
            run("ab\ncdef\ngh", 0, 1, "<C-v>2j$AX<Esc>")
        );
        // A line that ends before the block is left alone by I.
        assertEquals(
            "aXb\n\ncXdef @0,1",
            run("ab\n\ncdef", 0, 1, "<C-v>2jIX<Esc>")
        );
        // One that ends right at its left edge is not.
        assertEquals(
            "aXb\ncX\ndXef @0,1",
            run("ab\nc\ndef", 0, 1, "<C-v>2jIX<Esc>")
        );
    }

    @Test
    public void dotRepeatsOverAsMuchAgain() {
        assertEquals(
            "foo4\nfoo8\nfoodefg @0,1",
            run(DIGITS, 0, 1, "<C-v>2jlcfo<Esc>0.")
        );
        assertEquals(
            "teshellothello\nme hello hello\nplehelloahellose @0,0",
            run(
                "test\nme\nplease",
                0,
                1,
                "<C-v>2jllAhello<Esc>0."
            )
        );
        assertEquals("gh @0,0", run("abcdefgh", 0, 0, "vlld."));
    }

    @Test
    public void replaceAndCase() {
        assertEquals("aXXd\neXXh @0,1", run("abcd\nefgh", 0, 1, "<C-v>jlrX"));
        assertEquals("aBCd\neFGh @0,1", run("abcd\nefgh", 0, 1, "<C-v>jlU"));
    }

    @Test
    public void shiftLeftTakesBlanksAtTheLeftEdge() {
        assertEquals(
            "    word1\n    word2 @0,4",
            run("      word1\n      word2", 0, 4, "<C-v>j<")
        );
    }

    @Test
    public void shiftRightTakesACount() {
        tearDown();
        h = EditorHarness.create().vim("set et sw=2");
        h.value("abc\ndef").cursor(0, 1).keys("<C-v>j3>");
        assertEquals("a      bc\nd      ef", h.value());
    }

    /** The text and caret keys leave, with vim's shiftwidth set to four. */
    private String sw4(String text, int line, int offset, String keys) {
        tearDown();
        h = EditorHarness.create().vim("set et sw=4");
        h.value(text).cursor(line, offset).keys(keys);
        return h.value() + " @" + h.lineNumber() + "," + h.offset();
    }

    @Test
    public void shiftRightLeavesAnEmptyLineAlone() {
        assertEquals("    ab\n\n    cd @0,0", sw4("ab\n\ncd", 0, 0, "<C-v>jj>"));
    }

    @Test
    public void shiftRightWidensTheBlanksAlreadyThere() {
        // The tab and the new columns become one run of spaces, so x moves.
        assertEquals(
            "ab\n            x = beta;\n    end @1,0",
            sw4("ab\n\tx = beta;\nend", 2, 2, "<C-v>k>")
        );
        assertEquals(
            "      ab\n\n     cd @0,0",
            sw4("  ab\n\n cd", 0, 0, "<C-v>jj>")
        );
    }

    @Test
    public void shiftLeftNarrowsTheBlanksAtTheLeftEdge() {
        assertEquals(
            "    a\n    b @0,0",
            sw4("        a\n        b", 0, 0, "<C-v>j<")
        );
        // A tab counts for its eight columns, and four are left.
        assertEquals("    a\n    b @0,0", sw4("\ta\n\tb", 0, 0, "<C-v>j<"));
        // From the first non-blank there are no blanks to take, in nvim too.
        assertEquals(
            "        a\n        b @0,8",
            sw4("        a\n        b", 0, 8, "<C-v>j<")
        );
    }

    @Test
    public void shiftingACharwiseSelectionShiftsTheLinesItTouches() {
        // Only those: it once shifted every line to the end of the buffer.
        assertEquals(
            "a\nb\nc\n    d @0,0",
            sw4("    a\n    b\n    c\n    d", 0, 4, "vjj<")
        );
        assertEquals(
            "a\nb\n    c\n    d @0,0",
            sw4("    a\n    b\n    c\n    d", 0, 4, "vj$<")
        );
        assertEquals("a\nb @0,0", sw4("    a\n    b", 0, 4, "vjj<<<"));
    }

    @Test
    public void undoPutsTheCaretAtTheTopLeft() {
        assertEquals(
            "abcd\nefgh\nijkl @0,1",
            run("abcd\nefgh\nijkl", 2, 2, "<C-v>kkhdu")
        );
        assertEquals(
            "abcd\nefgh\nijkl @0,1",
            run("abcd\nefgh\nijkl", 2, 2, "<C-v>kkhrXu")
        );
        // Where . left the caret at the bottom corner.
        assertEquals(
            "    ab\n    cd\n    ef @0,0",
            sw4("ab\ncd\nef", 0, 0, "<C-v>jj>.u")
        );
    }

    @Test
    public void oSwapsCornersAndOTheSides() {
        assertEquals(
            "abcd\nefgh\nijkl @1,1",
            run("abcd\nefgh\nijkl", 0, 1, "<C-v>jlO")
        );
        assertEquals(
            "ad\neh\nijkl @0,1",
            run("abcd\nefgh\nijkl", 0, 1, "<C-v>jlOd")
        );
    }

    @Test
    public void aColumnInsideATabIsOnTheTab() {
        // j, k and | land on the character a column falls in, as the block's
        // corners do.
        assertEquals(
            "a\tbc\ncdefghijkl @0,1",
            run("a\tbc\ncdefghijkl", 1, 3, "k")
        );
        assertEquals(
            "abcdefghij\n\tx @1,1",
            run("abcdefghij\n\tx", 0, 9, "j")
        );
        assertEquals("a\tbc @0,1", run("a\tbc", 0, 0, "5|"));
    }

    @Test
    public void visualJAndKStopOnTheEndOfAShortLine() {
        // So a selection takes in the line break, and a block the columns
        // past the line's end.
        assertEquals("me @0,1", run("me\ntest", 1, 3, "vkd"));
        assertEquals("ef @0,0", run("ab\ncd\nef", 0, 0, "v$jd"));
        assertEquals("me\nte @0,1", run("me\ntest", 1, 3, "<C-v>kd"));
        assertEquals(
            "meX\nteXst @0,2",
            run("me\ntest", 1, 3, "<C-v>kIX<Esc>")
        );
        // Not in normal mode.
        assertEquals("me\ntest @0,1", run("me\ntest", 1, 3, "k"));
    }

    @Test
    public void joinJoinsTheLines() {
        assertEquals("abcd efgh @0,4", run("abcd\nefgh", 0, 1, "<C-v>jlJ"));
    }

    @Test
    public void putABlock() {
        assertEquals(
            "helhelo\nworwold\nfoofo\nbarba @0,3",
            run("hello\nworld\nfoo\nbar", 0, 0, "<C-v>3jly0llp")
        );
        assertEquals(
            "hehello\nwoworld\nfofoo\nbabar @0,2",
            run("hello\nworld\nfoo\nbar", 0, 0, "<C-v>3jly0llP")
        );
        // Short lines padded, and new lines made at the end of the buffer.
        assertEquals(
            "hellho\nfoo f\nbar b @0,4",
            run("hello\nfoo\nbar", 0, 0, "<C-v>jjy0lllp")
        );
        assertEquals(
            "cut\nand\npaste\nmcue\n an\n pa @3,1",
            run("cut\nand\npaste\nme", 0, 0, "<C-v>2jlyGp")
        );
    }

    @Test
    public void putOverABlock() {
        // Characters go on each of its lines.
        assertEquals("ab\nad\naf @1,0", run("ab\ncd\nef", 0, 0, "ylj<C-v>jp"));
        // A block over a block.
        assertEquals(
            "abab\nefef @0,2",
            run("abcd\nefgh", 0, 0, "<C-v>jly0ll<C-v>jlp")
        );
        // Lines go after its last.
        assertEquals(
            "ab\n\n\nab @3,0",
            run("ab\ncd\nef", 0, 0, "yyj<C-v>jlp")
        );
        // And after an emptied line, when the selection was a whole one.
        assertEquals("ab\n\nab\n @2,0", run("ab\ncd", 0, 0, "yyjvlp"));
    }
}
