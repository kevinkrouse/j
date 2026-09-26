/*
 * VimBranchReviewTest.java
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
 * What the review of the whole branch after M14 found, one group per
 * finding, each expectation taken from nvim. The finding numbers are the
 * review's.
 */
public class VimBranchReviewTest
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

    // F1 -- a linewise range on an empty last line. Its end is the line at
    // offset 0 either way, so the range has to say which line is its last.

    @Test
    public void ccInAnEmptyBuffer()
    {
        vim("", 0, 0).keys("ccX<Esc>");
        assertEquals("X", h.value());
    }

    @Test
    public void ccOnAnEmptyLastLineChangesThatLine()
    {
        vim("foo\n", 1, 0).keys("ccX<Esc>");
        assertEquals("foo\nX", h.value());
        h.assertCursorAt(1, 0);
    }

    @Test
    public void twoCcEndingOnAnEmptyLastLine()
    {
        vim("a\nb\n", 1, 0).keys("2ccX<Esc>");
        assertEquals("a\nX", h.value());
    }

    @Test
    public void ccAboveAnEmptyLastLineLeavesIt()
    {
        vim("a\nb\n", 1, 0).keys("ccX<Esc>");
        assertEquals("a\nX\n", h.value());
    }

    @Test
    public void ddOnAnEmptyLastLine()
    {
        vim("a\n", 1, 0).keys("dd");
        assertEquals("a", h.value());
        h.assertCursorAt(0, 0);
    }

    @Test
    public void twoDdEndingOnAnEmptyLastLine()
    {
        vim("a\nb\n", 1, 0).keys("2dd");
        assertEquals("a", h.value());
    }

    @Test
    public void dipOnTrailingBlankLines()
    {
        vim("a\n\n\n", 2, 0).keys("dip");
        assertEquals("a", h.value());
    }

    @Test
    public void vipdOnATrailingBlankLine()
    {
        vim("a\n\n", 1, 0).keys("vipd");
        assertEquals("a", h.value());
    }

    @Test
    public void vipSelectsEveryTrailingBlankLine()
    {
        vim("a\n\n\n", 1, 0).keys("vipd");
        assertEquals("a", h.value());
    }
}
