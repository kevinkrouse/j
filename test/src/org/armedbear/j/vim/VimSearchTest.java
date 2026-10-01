/*
 * VimSearchTest.java
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
import static org.junit.Assert.assertTrue;

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/**
 * Search: / ? n N * # g* g#.
 *
 * A frameless editor has no location bar, so searchPattern() supplies what
 * would have been typed into it; everything after that is the production
 * path. Expectations were checked against nvim.
 */
public class VimSearchTest
{
    /** Three lines with "bravo" at 0,6 then 1,8 then 2,0. */
    private static final String THREE =
        "alpha bravo\ncharlie bravo\nbravo delta";
    /** "foo" whole-word at 0,0 then 1,7 then 2,4; "foobar" at 1,0. */
    private static final String WORDS = "foo bar\nfoobar foo\nbar foo";

    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text, int line, int offset)
    {
        if (h != null)
            h.close();
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset);
        return h;
    }

    private void at(int line, int offset)
    {
        assertEquals("line", line, h.lineNumber());
        assertEquals("offset", offset, h.offset());
    }

    // ------------------------------------------------------------ / and ?

    @Test
    public void slashMovesToTheStartOfTheMatch()
    {
        vim(THREE, 0, 0).keys("/").searchPattern("bravo");
        at(0, 6);
    }

    @Test
    public void questionMarkSearchesBackwardAndWraps()
    {
        vim(THREE, 0, 0).keys("?").searchPattern("bravo");
        at(2, 0);
    }

    @Test
    public void searchWrapsPastTheEndOfTheBuffer()
    {
        vim(THREE, 2, 0).keys("/").searchPattern("bravo");
        at(0, 6);
    }

    @Test
    public void aPatternThatIsNowhereLeavesTheCaretAlone()
    {
        vim(THREE, 0, 0).keys("/").searchPattern("zzz");
        at(0, 0);
    }

    @Test
    public void searchIsCaseSensitiveByDefault()
    {
        vim("Foo\nfoo", 0, 0).keys("/").searchPattern("foo");
        at(1, 0);
    }

    // ------------------------------------------------------------ n and N

    @Test
    public void nRepeatsAndNCapitalReverses()
    {
        vim(THREE, 0, 0).keys("/").searchPattern("bravo");
        h.keys("n");
        at(1, 8);
        h.keys("N");
        at(0, 6);
    }

    @Test
    public void nKeepsTheDirectionTheSearchWasMadeIn()
    {
        // After ?, n carries on backwards rather than forwards.
        vim(THREE, 0, 0).keys("?").searchPattern("bravo");
        at(2, 0);
        h.keys("n");
        at(1, 8);
    }

    @Test
    public void nWithNoPreviousSearchDoesNothing()
    {
        vim(THREE, 0, 0).keys("n");
        at(0, 0);
    }

    // -------------------------------------------------------- * and # --

    @Test
    public void starSearchesForTheWholeWordUnderTheCaret()
    {
        // Skips "foobar", which contains foo but is not the word foo.
        vim(WORDS, 0, 0).keys("*");
        at(1, 7);
    }

    @Test
    public void hashSearchesBackwardAndWraps()
    {
        vim(WORDS, 0, 0).keys("#");
        at(2, 4);
    }

    @Test
    public void gStarMatchesPartOfAWordToo()
    {
        vim(WORDS, 0, 0).keys("g*");
        at(1, 0);
        h.close();
        vim(WORDS, 0, 0).keys("g#");
        at(2, 4);
    }

    @Test
    public void starSetsThePatternThatNThenRepeats()
    {
        vim(WORDS, 0, 0).keys("*n");
        at(2, 4);
    }

    @Test
    public void starWorksFromAnywhereInTheWord()
    {
        // The caret is usually in the middle of a word, not on its first
        // character, and getIdentifier reads back to the start of it.
        for (int caret = 0; caret <= 2; caret++) {
            vim(WORDS, 0, caret).keys("*");
            assertEquals("caret " + caret, 1, h.lineNumber());
            assertEquals("caret " + caret, 7, h.offset());
            h.close();
        }
        h = null;
    }

    @Test
    public void deleteToAStarFromMidWordStartsAtTheCaret()
    {
        // The search starts at the word, but the operator starts where the
        // caret is, so the first character survives.
        vim(WORDS, 0, 1).keys("d*");
        assertEquals("ffoo\nbar foo", h.value());
    }

    @Test
    public void starOnPunctuationTakesTheNextWordOnTheLine()
    {
        vim("a + b", 0, 2).keys("*");
        at(0, 4);
    }

    @Test
    public void starWithNoWordOnTheLineDoesNothing()
    {
        vim(" \n match \n", 0, 0).keys("*");
        at(0, 0);
    }

    @Test
    public void starUsesIgnorecaseButNotSmartcase()
    {
        // :help * -- "'ignorecase' is used, 'smartcase' is not". nvim with
        // both set finds the lower case foo from Foo. A typed / keeps
        // smartcase, so /Foo stays case sensitive.
        // The options are set after the harness exists: vim() installs a
        // fresh set, so setting them first sets them on one thrown away.
        try {
            vim("Foo x foo", 0, 0);
            ignoreCaseAndSmartcase();
            h.keys("*");
            at(0, 6);
            h.close();
            vim("Foo x foo\nFoo", 0, 0);
            ignoreCaseAndSmartcase();
            h.keys("/").searchPattern("Foo");
            at(1, 0);
        }
        finally {
            VimKeyMap.reset();
        }
    }

    private static void ignoreCaseAndSmartcase()
    {
        final VimOptions options = VimKeyMap.getSharedOptions();
        options.set("ignorecase", "true");
        options.set("smartcase", "true");
    }

    // ------------------------------------------------- with an operator

    // A search is an exclusive motion, so an operator reaches up to the
    // start of the match and no further.

    @Test
    public void deleteToASearchStopsAtTheMatch()
    {
        vim(THREE, 0, 0).keys("d/").searchPattern("bravo");
        assertEquals("bravo\ncharlie bravo\nbravo delta", h.value());
        at(0, 0);
    }

    @Test
    public void deleteToARepeatedSearchWorksTheSameWay()
    {
        vim(THREE, 0, 0).keys("/").searchPattern("bravo");
        h.keys("ggdn");
        assertEquals("bravo\ncharlie bravo\nbravo delta", h.value());
    }

    @Test
    public void deleteToAStarSearch()
    {
        vim(WORDS, 0, 0).keys("d*");
        assertEquals("foo\nbar foo", h.value());
    }

    @Test
    public void deleteToASearchAcrossLines()
    {
        vim(THREE, 0, 2).keys("d/").searchPattern("delta");
        assertEquals("aldelta", h.value());
    }

    // A search is exclusive, so it gets the :help exclusive adjustments: a
    // match in column 1 pulls the range back to the end of the line before,
    // and a range that also started at the first non-blank goes linewise.

    @Test
    public void aMatchInColumnOnePullsTheRangeBack()
    {
        vim("aaa\nbbb\nccc", 0, 1).keys("d/").searchPattern("ccc");
        assertEquals("a\nccc", h.value());
    }

    @Test
    public void andGoesLinewiseFromTheFirstNonBlank()
    {
        vim("  aaa\nbbb\nccc", 0, 2).keys("d/").searchPattern("ccc");
        assertEquals("ccc", h.value());
    }

    @Test
    public void anOperatorIsDroppedWhenTheSearchIsAbandoned()
    {
        vim(THREE, 0, 0).keys("d/");
        assertTrue("the delete is parked", h.awaitingSearchPattern());
        h.keys("<Esc>");
        assertFalse(h.awaitingSearchPattern());
        // The next motion must move rather than complete the abandoned delete.
        h.keys("l");
        assertEquals(THREE, h.value());
        at(0, 1);
    }

    // ------------------------------------------------------- the pattern

    @Test
    public void patternsAreVimRegularExpressions()
    {
        // \+ is "one or more"; a bare + is the character.
        vim("aaa bbb", 0, 0).keys("/").searchPattern("b\\+");
        at(0, 4);
    }

    @Test
    public void aBarePlusIsTheCharacter()
    {
        vim("aaa bbb b+", 0, 0).keys("/").searchPattern("b+");
        at(0, 8);
    }

    @Test
    public void vimWordBoundariesMatchWholeWords()
    {
        vim(WORDS, 0, 0).keys("/").searchPattern("\\<foo\\>");
        at(1, 7);
    }

    @Test
    public void aPatternRegexCannotParseIsReported()
    {
        vim(THREE, 0, 0).keys("/").searchPattern("a[b");
        at(0, 0);
    }

    // Where the matches on a line are is fixed by scanning it from the
    // start, not from the caret. It only shows with a pattern that can
    // overlap itself, and then it shows badly: searching a\+ in "aaa aa"
    // from column 0 must find column 4, not column 1.

    @Test
    public void overlappingMatchesAreCountedFromTheStartOfTheLine()
    {
        vim("aaa aa \n a aa", 0, 0).keys("/").searchPattern("a\\+");
        at(0, 4);
        h.keys("n");
        at(1, 1);
        h.keys("n");
        at(1, 3);
        h.keys("n");
        at(0, 0);
    }

    @Test
    public void andBackwardsTheSameWay()
    {
        vim("aaa aa \n a aa", 0, 0).keys("?").searchPattern("a\\+");
        at(1, 3);
        h.keys("n");
        at(1, 1);
        h.keys("n");
        at(0, 4);
    }

    @Test
    public void aPatternThatCanMatchNothingDoesNotFallOffTheBuffer()
    {
        // $ matches empty at the end of every line, so stepping past a match
        // runs out of buffer -- which used to be a null position handed
        // straight to the search.
        vim("aaa", 0, 0).keys("2/").searchPattern("$");
        vim("aaa\nbbb", 0, 0).keys("3/").searchPattern("$");
        h.assertCursorAt(0, 2);
    }

    // ------------------------------------------------- repeating with '.'

    @Test
    public void dotRepeatsAChangeMadeWithASearch()
    {
        vim("one END two\nthree END four", 0, 0).keys("d/")
            .searchPattern("END");
        assertEquals("END two\nthree END four", h.value());
        h.keys("j0.");
        assertEquals("END two\nEND four", h.value());
    }

    @Test
    public void aSearchChangeDoesNotClobberAnUnrelatedRepeat()
    {
        // The search finishes outside the usual dispatch, so the "something
        // was edited" flag used to leak into whatever was typed next.
        vim("one two three\nalpha bravo charlie", 0, 0).keys("dw");
        h.keys("d/").searchPattern("three");
        h.keys("j0j");
        assertEquals("a motion is not a change", "three\nalpha bravo charlie",
                     h.value());
    }

    @Test
    public void aCountTakesThatManyMatches()
    {
        vim(THREE, 0, 0).keys("2/").searchPattern("bravo");
        at(1, 8);
        h.close();
        // foo is at 0,0 then 1,7 then 2,4; two steps from the first.
        vim(WORDS, 0, 0).keys("2*");
        at(2, 4);
    }
}
