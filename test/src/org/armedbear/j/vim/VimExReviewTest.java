/*
 * VimExReviewTest.java
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
 * What the review of M12b to M12.5 found, one test per finding, each
 * expectation taken from nvim. The finding numbers are the review's.
 */
public class VimExReviewTest
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

    // F1 -- a number too big for an int is not an exception.

    @Test
    public void aHugeCountClampsToTheEnd()
    {
        vim("a\nb\nc", 0, 0).exCommand("d 99999999999999999999");
        assertEquals("", h.value());
    }

    @Test
    public void aCodePointTooBigIsAnErrorNotAnException()
    {
        vim("abc", 0, 0).exCommand("s/\\%d99999999999/x/");
        h.exCommand("s/\\%UFFFFFFFF/x/");
        assertEquals("abc", h.value());
    }

    // F2 and F5 -- a substitute that matches nothing says so, unless e.

    @Test
    public void aSubstituteThatMatchesNothingIsNotSilent()
    {
        vim("aaa", 0, 1).exCommand("s/zzz/qq/");
        assertEquals("E486: Pattern not found: zzz", h.status());
        h.editor().status("");
        h.exCommand("s/zzz/qq/e");
        assertEquals("e accepts, and silences it", "", h.status());
    }

    // F3 -- the last of i and I wins.

    @Test
    public void theLastCaseFlagWins()
    {
        vim("aBc", 0, 0).exCommand("s/b/X/iI");
        assertEquals("I last: case sensitive, no match", "aBc", h.value());
        h.exCommand("s/b/X/Ii");
        assertEquals("i last: matches B", "aXc", h.value());
    }

    // F4 -- \r breaks the line in a replacement; \n puts in a NUL.

    @Test
    public void carriageReturnBreaksTheLineAndNewlineIsANul()
    {
        vim("aaa", 0, 0).exCommand("s/a/\\rb/");
        assertEquals("\nbaa", h.value());
        h.close();
        vim("aaa", 0, 0).exCommand("s/a/X\\nY/");
        assertEquals("X\u0000Yaa", h.value());
    }

    // F6 is in VimExTest, with the address tests.

    // F7 -- :normal! runs.

    @Test
    public void normalTakesABang()
    {
        vim("abc", 0, 0).exCommand("normal!x");
        assertEquals("bc", h.value());
    }

    // F13, F15 -- what goes wrong is refused, not ignored.

    @Test
    public void moveWithNoDestinationAndNormalWithNoKeysAreErrors()
    {
        vim("a\nb", 0, 0).exCommand("m");
        assertEquals("E16: Invalid range", h.status());
        h.exCommand("normal");
        assertEquals("E471: Argument required: normal", h.status());
        assertEquals("a\nb", h.value());
    }

    // F16 -- :g that finds nothing is a message, not a failure.

    @Test
    public void globalThatMatchesNothingSaysSoWithoutFailing()
    {
        // A message in nvim, not an E-number.
        vim("a\nb", 0, 0).exCommand("g/zzz/d");
        assertEquals("Pattern not found: zzz", h.status());
        assertEquals("a\nb", h.value());
    }

    @Test
    public void anInnerSubstituteThatMissesALineDoesNotStopGlobal()
    {
        // nvim makes Xne of :g/e/s/o/X/ over "one three five": three and
        // five have an e but no o, and :g carries on past them. With :s
        // reporting E486 on its own, this aborted the whole :g at first.
        vim("one\nthree\nfive", 0, 0).exCommand("g/e/s/o/X/");
        assertEquals("Xne\nthree\nfive", h.value());
    }

    // F17 -- ~ before any :s is E33.

    @Test
    public void tildeWithNoPreviousSubstituteIsAnError()
    {
        // ~ remembers across editors, as vim's does; clear what an earlier
        // test left so that there really is no previous substitute.
        VimExSubstitute.forgetForTest();
        vim("ab", 0, 0).exCommand("s/a~b/x/");
        assertEquals("E33: No previous substitute regular expression",
                     h.status());
    }

    // F10 -- \P is printable minus digits, space included.

    @Test
    public void capitalPExcludesDigitsButNotTheSpace()
    {
        vim("9 a", 0, 0).exCommand("s/\\P/[&]/g");
        assertEquals("9[ ][a]", h.value());
    }

    // F18 -- the caret after a substitute that breaks lines.

    @Test
    public void theCaretLandsOnTheLastLineTheLastSubstituteMade()
    {
        // It was line -1: the dot read after a split is no guide. nvim puts
        // it on Y2, the tail of the last split -- not back on the unchanged
        // b, which is what the review's notes said.
        vim("a1\nb\na2\na3", 0, 0).exCommand("1,3s/a/X\\rY/");
        assertEquals("X\nY1\nb\nX\nY2\na3", h.value());
        assertEquals(4, h.lineNumber());
        assertEquals(0, h.offset());
        h.close();
        vim("a1\nq", 0, 0).exCommand("s/a/X\\rY\\rZ/");
        assertEquals(2, h.lineNumber());
    }

    // F22 -- the register and the count, with a space between.

    @Test
    public void aSpaceBetweenRegisterAndCountChangesNothing()
    {
        vim("a\nb\nc\nd", 0, 0).exCommand("1,3d a 2");
        assertEquals("a\nb", h.value());
        h.keys("\"ap");
        assertEquals("a\nb\nc\nd", h.value());
    }

    // F25 -- a named register is also what a bare p pastes.

    @Test
    public void aBarePPastesWhatANamedDeleteTook()
    {
        // Not only from :d -- "add in normal mode had the same bug.
        vim("a\nb\nc\nd", 2, 0).keys("\"add").keys("p");
        assertEquals("a\nb\nd\nc", h.value());
        h.close();
        vim("a\nb\nc\nd", 0, 0).exCommand("1,3d a2");
        h.keys("p");
        assertEquals("a\nb\nc\nd", h.value());
    }

    @Test
    public void andWhatANamedYankTook()
    {
        vim("a\nb", 0, 0).keys("\"ayy").keys("p");
        assertEquals("a\na\nb", h.value());
    }

    // F8, F23, F24 -- :sort's flags.

    @Test
    public void sortFReadsAFloatAndCountsNoneAsZero()
    {
        // Unlike n, which puts a line with no number first.
        vim("0.5\n0.1\n-2.5e1\nx", 0, 0).exCommand("sort f");
        assertEquals("-2.5e1\nx\n0.1\n0.5", h.value());
    }

    @Test
    public void sortLIsAcceptedAndTheNumberFlagsExcludeEachOther()
    {
        vim("b\na", 0, 0).exCommand("sort l");
        assertEquals("a\nb", h.value());
        h.close();
        vim("b\na", 0, 0).exCommand("sort nx");
        assertEquals("E474, so nothing moves", "b\na", h.value());
    }
}
