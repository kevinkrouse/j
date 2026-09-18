/*
 * VimMarkTest.java
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

/** Marks, and the motions that go to them. */
public class VimMarkTest
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

    @Test
    public void backtickGoesToTheExactSpot()
    {
        vim("alpha\nbravo\ncharlie\n").cursor(1, 3).keys("ma");
        h.cursor(0, 0).keys("`a");
        at(1, 3);
    }

    @Test
    public void quoteGoesToTheFirstNonBlankOfTheLine()
    {
        vim("alpha\n    bravo\n").cursor(1, 7).keys("ma");
        h.cursor(0, 0).keys("'a");
        at(1, 4);
    }

    @Test
    public void marksAreSeparatePerName()
    {
        vim("one\ntwo\nthree\n").cursor(0, 1).keys("ma");
        h.cursor(2, 2).keys("mb");
        h.keys("`a");
        at(0, 1);
        h.keys("`b");
        at(2, 2);
    }

    @Test
    public void anUnsetMarkDoesNothing()
    {
        vim("alpha\nbravo\n").cursor(1, 2).keys("`z");
        at(1, 2);
    }

    @Test
    public void aMarkFollowsItsTextWhenLinesAboveGo()
    {
        vim("one\ntwo\nthree\n").cursor(2, 1).keys("ma");
        h.cursor(0, 0).keys("dd");
        h.keys("`a");
        assertEquals("the mark moved up with its line", 1, h.lineNumber());
        assertEquals(1, h.offset());
    }

    @Test
    public void bracketBacktickGoesToTheNearestMarkEitherWay()
    {
        vim("one\ntwo\nthree\nfour\n").cursor(0, 0).keys("ma");
        h.cursor(3, 0).keys("mb");
        h.cursor(1, 0).keys("]`");
        at(3, 0);
        h.cursor(1, 0).keys("[`");
        at(0, 0);
    }

    @Test
    public void bracketQuoteLandsOnTheFirstNonBlank()
    {
        vim("one\n   four\n").cursor(1, 5).keys("ma");
        h.cursor(0, 0).keys("]'");
        at(1, 3);
    }

    @Test
    public void jumpingWithNoMarkAheadDoesNothing()
    {
        vim("one\ntwo\n").cursor(0, 0).keys("ma");
        h.cursor(1, 0).keys("]`");
        at(1, 0);
    }

    @Test
    public void anOperatorCanUseAMarkAsItsMotion()
    {
        vim("alpha bravo\n").cursor(0, 6).keys("ma");
        h.cursor(0, 0).keys("d`a");
        h.assertText("bravo\n");
    }

    @Test
    public void theQuoteFormMakesAnOperatorLinewise()
    {
        vim("one\ntwo\nthree\n").cursor(1, 1).keys("ma");
        h.cursor(0, 0).keys("d'a");
        h.assertText("three\n");
    }
}
