/*
 * VimM18Test.java
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
 * M18: CTRL-A and CTRL-X, with nvim's default 'nrformats' of bin,hex. Every
 * expectation is nvim's.
 */
public class VimM18Test
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
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset);
        return h;
    }

    /** Runs the keys and checks the text and where the caret ends up. */
    private void check(String text, int line, int offset, String keys,
                       String expected, int endLine, int endOffset)
    {
        vim(text, line, offset).keys(keys);
        assertEquals(text + " " + keys, expected, h.value());
        assertEquals(text + " " + keys + " caret",
                     endLine + "," + endOffset,
                     h.lineNumber() + "," + h.offset());
    }

    private void check(String text, int offset, String keys, String expected,
                       int endOffset)
    {
        check(text, 0, offset, keys, expected, 0, endOffset);
    }

    @Test
    public void theNumberUnderOrAfterTheCaret()
    {
        check("x 5 y", 0, "<C-a>", "x 6 y", 2);
        check("abc123def", 0, "<C-a>", "abc124def", 5);
        check("12 34", 1, "<C-a>", "13 34", 1);
        check("12 34", 2, "<C-a>", "12 35", 4);
        check("1.5", 2, "<C-a>", "1.6", 2);
        check("99", 0, "<C-a>", "100", 2);
    }

    @Test
    public void noNumberAfterTheCaretIsNoChange()
    {
        check("x 5 y", 4, "<C-a>", "x 5 y", 4);
        check("abc123def", 7, "<C-a>", "abc123def", 7);
        check("ff", 0, "<C-a>", "ff", 0);
        check("a", 0, "<C-a>", "a", 0);
    }

    @Test
    public void aMinusBeforeTheDigitsIsTheSign()
    {
        check("-5", 0, "<C-a>", "-4", 1);
        check("x-5", 0, "<C-a>", "x-4", 2);
        check("a -5", 2, "<C-a>", "a -4", 3);
        check("--5", 0, "<C-a>", "--4", 2);
        check("0", 0, "<C-x>", "-1", 1);
        check("5", 0, "10<C-x>", "-5", 1);
        check("-1", 1, "2<C-a>", "1", 0);
        check("-1", 0, "<C-a>", "0", 0);
        check("-10", 0, "11<C-a>", "1", 0);
    }

    @Test
    public void leadingZerosKeepTheWidth()
    {
        check("007", 0, "<C-a>", "008", 2);
        check("010", 0, "<C-x>", "009", 2);
        check("007", 0, "10<C-x>", "-003", 3);
        check("-007", 0, "<C-a>", "-006", 3);
        check("-007", 0, "7<C-a>", "000", 2);
        check("-007", 0, "8<C-a>", "001", 2);
    }

    @Test
    public void hexadecimal()
    {
        check("0xff", 0, "<C-a>", "0x100", 4);
        check("0x0F", 0, "<C-a>", "0x10", 3);
        check("0x10", 3, "<C-x>", "0x0f", 3);
        check("0xFF", 3, "<C-a>", "0x100", 4);
        check("0x5", 1, "<C-a>", "0x6", 2);
        check("x0x5", 0, "<C-a>", "x0x6", 3);
        check("1 0x5", 3, "<C-a>", "1 0x6", 4);
        // Not negative: the minus is left alone.
        check("-0x5", 0, "<C-a>", "-0x6", 3);
        // Wraps around at 64 bits.
        check("0x00", 0, "<C-x>", "0xffffffffffffffff", 17);
    }

    @Test
    public void hexadecimalKeepsTheCaseOfItsLastLetter()
    {
        check("0xaB", 0, "<C-a>", "0xAC", 3);
        check("0xAb", 0, "<C-a>", "0xac", 3);
        check("0xFE", 0, "<C-a>", "0xFF", 3);
        check("0XfE", 0, "<C-a>", "0XFF", 3);
        check("0X10", 0, "<C-x>", "0X0F", 3);
        check("0Xf9", 0, "<C-a>", "0Xfa", 3);
    }

    @Test
    public void binary()
    {
        check("0b111", 0, "<C-a>", "0b1000", 5);
        check("0B01", 0, "<C-a>", "0B10", 3);
        check("0b100", 0, "<C-x>", "0b011", 4);
        check("0b101", 3, "<C-a>", "0b110", 4);
        check("-0b1", 0, "<C-a>", "-0b10", 4);
        check("0b0", 0, "<C-x>", "0b" + "1".repeat(64), 65);
    }

    @Test
    public void aPrefixWithoutDigitsIsNotOne()
    {
        check("0x", 0, "<C-a>", "1x", 0);
        check("0xg1", 0, "<C-a>", "1xg1", 0);
        check("0b2", 0, "<C-a>", "1b2", 0);
        check("10x5", 1, "<C-a>", "11x5", 1);
    }

    @Test
    public void decimalPastSixtyFourBits()
    {
        check("9223372036854775807", 0, "<C-a>", "9223372036854775808", 18);
        check("-9223372036854775808", 0, "<C-x>", "-9223372036854775809",
              19);
        check("18446744073709551615", 0, "<C-a>", "-18446744073709551615",
              20);
        check("99999999999999999999", 0, "<C-x>", "18446744073709551615",
              19);
    }

    @Test
    public void dotRepeatsWithItsCountOrANewOne()
    {
        check("5 5", 0, "3<C-a>w.", "8 8", 2);
        check("5 5", 0, "3<C-a>w2.", "8 7", 2);
    }

    @Test
    public void undoGivesTheCaretBack()
    {
        check("a 5", 0, "<C-a>u", "a 5", 0);
        check("a 99 b", 0, "<C-a>u", "a 99 b", 0);
        check("a 0x0f b", 0, "<C-a>u", "a 0x0f b", 0);
        check("a 99 b", 2, "<C-a>u", "a 99 b", 2);
        // Redo's caret is where the edit left it; nvim's is where it was
        // typed. Documented.
        vim("a 99 b", 0, 2).keys("<C-a>u<C-r>");
        assertEquals("a 100 b", h.value());
    }

    @Test
    public void theMarks()
    {
        check("a 5 b", 0, "<C-a>G$`[", "a 6 b", 2);
        check("a 99 b", 0, "<C-a>0`]", "a 100 b", 4);
        check("a 5 b", 0, "<C-a>G$`.", "a 6 b", 0);
    }

    // ---------------------------------------- visual

    @Test
    public void visualTakesTheFirstNumberOfEachLine()
    {
        check("1\n1\n1", 0, 0, "VG<C-a>", "2\n2\n2", 0, 0);
        check("a 1 2\nb 3", 0, 0, "Vj<C-a>", "a 2 2\nb 4", 0, 0);
        check("a1b2", 0, 0, "v$<C-a>", "a2b2", 0, 0);
        check("1 1 1\n1", 0, 2, "v<C-a>", "1 2 1\n1", 0, 2);
    }

    @Test
    public void visualReadsOnlyWhatIsSelected()
    {
        check("12345", 0, 1, "vl<C-a>", "12445", 0, 1);
        // The minus outside the selection is not the sign.
        check("x -5", 0, 3, "v<C-a>", "x -6", 0, 3);
        check("x -5 y", 0, 0, "v$<C-a>", "x -4 y", 0, 0);
        check("x -5 y", 0, 2, "v$<C-a>", "x -4 y", 0, 2);
    }

    @Test
    public void theJCommandsTakeAnAmount() throws Exception
    {
        vim("a 5", 0, 0);
        h.editor().execute("incrementNumber", "10");
        assertEquals("a 15", h.value());
        h.assertCursorAt(0, 3);
        h.editor().execute("decrementNumber", null);
        assertEquals("a 14", h.value());
        h.editor().execute("incrementNumber", "-20");
        assertEquals("a -6", h.value());
    }

    @Test
    public void visualMarksTheNumbersChanged()
    {
        final String text = "x\n5 6\ny 7";
        check(text, 0, 0, "VG<C-a>gg0`[", "x\n6 6\ny 8", 1, 0);
        check(text, 0, 0, "VG<C-a>gg0`]", "x\n6 6\ny 8", 2, 2);
        check(text, 0, 0, "VG<C-a>G`.", "x\n6 6\ny 8", 0, 0);
    }

    @Test
    public void gAddsACountMoreToEachNumber()
    {
        check("1\n1\n1", 0, 0, "VGg<C-a>", "2\n3\n4", 0, 0);
        check("1\n1\n1", 0, 0, "VG2g<C-a>", "3\n5\n7", 0, 0);
        check("1\nx\n1", 0, 0, "VGg<C-a>", "2\nx\n3", 0, 0);
    }
}
