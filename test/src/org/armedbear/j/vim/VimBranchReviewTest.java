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
import static org.junit.Assert.assertFalse;

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
    // F2 -- with g, :s stops once the next search would start at the end of
    // the line. An empty match there is taken when nothing came before it.

    @Test
    public void aLoneEmptyMatchAtTheEndIsTaken()
    {
        vim("xy", 0, 0).exCommand("s/$/;/g");
        assertEquals("xy;", h.value());
    }

    @Test
    public void anEmptyLineTakesItsOnlyMatch()
    {
        vim("a\n\nb", 0, 0).exCommand("%s/^/#/g");
        assertEquals("#a\n#\n#b", h.value());
    }

    @Test
    public void anEmptyMatchAfterANonEmptyOneIsTaken()
    {
        vim("ab", 0, 0).exCommand("s/a\\|$/-/g");
        assertEquals("-b-", h.value());
    }

    @Test
    public void noEmptyMatchOnceTheSearchReachesTheEnd()
    {
        vim("ab", 0, 0).exCommand("s/x*/-/g");
        assertEquals("-a-b", h.value());
    }

    @Test
    public void aNonEmptyMatchCanReachTheEnd()
    {
        vim("ab", 0, 0).exCommand("s/b*/-/g");
        assertEquals("-a-", h.value());
    }
    // F3 -- :sort u drops equal lines, not lines with equal keys.

    @Test
    public void sortUniqueComparesWholeLinesNotKeys()
    {
        vim("x:1\ny:1", 0, 0).exCommand("sort u /:/");
        assertEquals("x:1\ny:1", h.value());
    }

    @Test
    public void sortUniqueByNumberStillComparesLines()
    {
        vim("a1\nb1\n1", 0, 0).exCommand("sort nu");
        assertEquals("a1\nb1\n1", h.value());
    }

    @Test
    public void sortUniqueIgnoringCaseKeepsTheFirst()
    {
        vim("A\na\nb", 0, 0).exCommand("sort iu");
        assertEquals("A\nb", h.value());
    }
    // F4 -- the :sort pattern is vim's only, never compiled as Java's first.

    @Test
    public void sortTakesAPatternOnlyVimUnderstands()
    {
        vim("xAc\nyBa\nzCb", 0, 0).exCommand("sort /\\u/");
        assertEquals("yBa\nzCb\nxAc", h.value());
        assertEquals("", h.status());
    }
    // F5 -- a group the pattern does not have is empty, not an exception.

    @Test
    public void aMissingGroupIsEmpty()
    {
        vim("abc", 0, 0).exCommand("s/b/[\\1]/");
        assertEquals("a[]c", h.value());
    }

    @Test
    public void aGroupPastTheLastIsEmpty()
    {
        vim("abc", 0, 0).exCommand("s/\\(b\\)/[\\1\\2]/");
        assertEquals("a[b]c", h.value());
    }
    // F6 -- an abandoned d/ is not left in the recording for . to replay.

    @Test
    public void anEmptyPatternLeavesNothingForDotToReplay()
    {
        vim("abcdef", 0, 0).keys("d/").searchPattern("");
        h.keys("x").keys(".");
        assertEquals("cdef", h.value());
        assertFalse(h.awaitingSearchPattern());
    }

    @Test
    public void anEscapedPromptLeavesNothingForDotToReplay()
    {
        vim("abcdef", 0, 0).keys("d/");
        ((VimInputHandler) h.editor().getInputHandler()).searchCancelled();
        h.keys("x").keys(".");
        assertEquals("cdef", h.value());
        assertFalse(h.awaitingSearchPattern());
    }

    // F7 -- :normal abandons a command its keys leave unfinished.

    @Test
    public void normalAbandonsAnUnfinishedSearch()
    {
        vim("abc", 0, 0).exCommand("normal /b");
        assertFalse(h.awaitingSearchPattern());
        h.keys("x");
        assertEquals("bc", h.value());
    }
    // F8 -- p steps over a whole character outside the Basic Multilingual
    // Plane, on the way in and on the way back to the last one put.

    private static final String EMOJI = "😀";

    @Test
    public void putAfterAnEmojiGoesAfterAllOfIt()
    {
        vim("X" + EMOJI, 0, 0).keys("yllp");
        assertEquals("X" + EMOJI + "X", h.value());
        h.assertCursorAt(0, 3);
    }

    @Test
    public void putAnEmojiLeavesTheCaretOnItsStart()
    {
        vim(EMOJI + "a", 0, 0).keys("ylp");
        assertEquals(EMOJI + EMOJI + "a", h.value());
        h.assertCursorAt(0, 2);
    }
    // Found fixing F8: Backspace in replace mode stepped one UTF-16 unit.

    @Test
    public void replaceBackspaceStepsOverAWholeEmoji()
    {
        vim(EMOJI + "a", 0, 2).keys("R<BS>");
        assertEquals(EMOJI + "a", h.value());
        h.assertCursorAt(0, 0);
    }
}
