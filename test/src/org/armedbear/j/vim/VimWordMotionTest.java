/*
 * VimWordMotionTest.java
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
 * Word motions, including the part everyone gets wrong: punctuation is a word
 * of its own, so w stops on it.
 */
public class VimWordMotionTest
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

    // ----------------------------------------------------------------- w

    @Test
    public void wGoesToTheNextWord()
    {
        vim("alpha bravo charlie\n").cursor(0, 0).keys("w");
        at(0, 6);
        h.keys("w");
        at(0, 12);
    }

    @Test
    public void wStopsOnPunctuation()
    {
        // foo.bar is three words to vim: foo, the dot, and bar.
        vim("foo.bar\n").cursor(0, 0).keys("w");
        at(0, 3);
        h.keys("w");
        at(0, 4);
    }

    @Test
    public void capitalWTreatsPunctuationAsPartOfTheWord()
    {
        vim("foo.bar baz\n").cursor(0, 0).keys("W");
        at(0, 8);
    }

    @Test
    public void wCrossesToTheNextLine()
    {
        vim("alpha\nbravo\n").cursor(0, 3).keys("w");
        at(1, 0);
    }

    @Test
    public void wStopsOnAnEmptyLine()
    {
        // An empty line is a word in its own right.
        vim("alpha\n\nbravo\n").cursor(0, 0).keys("w");
        at(1, 0);
    }

    @Test
    public void wTakesACount()
    {
        vim("one two three four\n").cursor(0, 0).keys("3w");
        at(0, 14);
    }

    // ----------------------------------------------------------------- b

    @Test
    public void bGoesBackToTheStartOfTheWord()
    {
        vim("alpha bravo\n").cursor(0, 9).keys("b");
        at(0, 6);
    }

    @Test
    public void bFromAWordStartGoesToThePreviousWord()
    {
        vim("alpha bravo\n").cursor(0, 6).keys("b");
        at(0, 0);
    }

    @Test
    public void bStopsOnPunctuation()
    {
        vim("foo.bar\n").cursor(0, 4).keys("b");
        at(0, 3);
    }

    @Test
    public void capitalBSkipsPunctuation()
    {
        vim("foo.bar baz\n").cursor(0, 8).keys("B");
        at(0, 0);
    }

    // ----------------------------------------------------------------- e

    @Test
    public void eGoesToTheEndOfTheWord()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("e");
        at(0, 4);
    }

    @Test
    public void eFromAWordEndGoesToTheNextWordEnd()
    {
        vim("alpha bravo\n").cursor(0, 4).keys("e");
        at(0, 10);
    }

    @Test
    public void capitalEIgnoresPunctuation()
    {
        vim("foo.bar baz\n").cursor(0, 0).keys("E");
        at(0, 6);
    }

    @Test
    public void geGoesBackToThePreviousWordEnd()
    {
        vim("alpha bravo\n").cursor(0, 6).keys("ge");
        at(0, 4);
    }

    // --------------------------------------------------------- f, t, ; and ,

    @Test
    public void fFindsTheCharacter()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("fb");
        at(0, 6);
    }

    @Test
    public void capitalFSearchesBackwards()
    {
        vim("alpha bravo\n").cursor(0, 10).keys("Fa");
        at(0, 8);
    }

    @Test
    public void tStopsBeforeTheCharacter()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("tb");
        at(0, 5);
    }

    @Test
    public void capitalTStopsAfterTheCharacter()
    {
        vim("alpha bravo\n").cursor(0, 10).keys("Ta");
        at(0, 9);
    }

    @Test
    public void fTakesACount()
    {
        vim("a-b-c-d\n").cursor(0, 0).keys("3f-");
        at(0, 5);
    }

    @Test
    public void fThatFindsNothingDoesNotMove()
    {
        vim("alpha\n").cursor(0, 0).keys("fz");
        at(0, 0);
    }

    @Test
    public void fDoesNotSearchPastTheEndOfTheLine()
    {
        vim("alpha\nbravo\n").cursor(0, 0).keys("fv");
        at(0, 0);
    }

    @Test
    public void semicolonRepeatsTheSearch()
    {
        vim("a-b-c-d\n").cursor(0, 0).keys("f-");
        at(0, 1);
        h.keys(";");
        at(0, 3);
        h.keys(";");
        at(0, 5);
    }

    @Test
    public void commaRepeatsTheSearchBackwards()
    {
        vim("a-b-c-d\n").cursor(0, 0).keys("f-;;");
        at(0, 5);
        h.keys(",");
        at(0, 3);
    }

    @Test
    public void semicolonAfterTillDoesNotGetStuck()
    {
        // Parked against the first dash, ';' must reach the next one.
        vim("a-b-c-d\n").cursor(0, 0).keys("t-");
        at(0, 0);
        h.keys(";");
        at(0, 2);
    }

    @Test
    public void wordMotionsChangeNoText()
    {
        vim("alpha bravo\ncharlie\n").cursor(0, 0).keys("wwbbeeWBEge");
        h.assertText("alpha bravo\ncharlie\n");
    }
}
