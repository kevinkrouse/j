/*
 * VimExTest.java
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
 * The {@code :} command line: ranges, {@code :d}, {@code :y} and {@code :s}.
 *
 * A frameless editor has no location bar, so exCommand() supplies what would
 * have been typed into it; everything after that is the production path.
 * Expectations were checked against nvim.
 */
public class VimExTest
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

    private void at(int line, int offset)
    {
        assertEquals("line", line, h.lineNumber());
        assertEquals("offset", offset, h.offset());
    }

    // ------------------------------------------------------------- ranges

    @Test
    public void aBareNumberGoesToThatLine()
    {
        vim("one\ntwo\nthree", 0, 0).keys(":").exCommand("3");
        at(2, 0);
        h.keys(":").exCommand("1");
        at(0, 0);
    }

    @Test
    public void goingToALineLandsOnItsFirstNonBlank()
    {
        vim("one\n    two", 0, 0).keys(":").exCommand("2");
        at(1, 4);
    }

    @Test
    public void dollarIsTheLastLineAndDotIsThisOne()
    {
        vim("one\ntwo\nthree", 0, 0).keys(":").exCommand("$");
        at(2, 0);
        h.keys(":").exCommand(".-1");
        at(1, 0);
    }

    @Test
    public void anOffsetWithNoAddressCountsFromHere()
    {
        vim("1\n2\n3\n4\n5", 0, 0).keys(":").exCommand("+3");
        at(3, 0);
        h.keys(":").exCommand("-2");
        at(1, 0);
    }

    @Test
    public void aRangeCanRunBackwardsAndIsPutRight()
    {
        vim("one\ntwo\nthree\nfour", 0, 0).keys(":").exCommand("3,2d");
        assertEquals("one\nfour", h.value());
    }

    @Test
    public void aMarkCanNameALineInARange()
    {
        vim("one\ntwo\nthree\nfour", 1, 0).keys("ma");
        h.keys("G").keys(":").exCommand("'a,$d");
        assertEquals("one", h.value());
    }

    @Test
    public void aSearchCanNameALineInARange()
    {
        vim("alpha\nbravo\ncharlie", 0, 0).keys(":").exCommand("/charlie/");
        at(2, 0);
    }

    // ------------------------------------------------------- delete, yank

    @Test
    public void deleteTakesTheRange()
    {
        vim("one\ntwo\nthree\nfour", 0, 0).keys(":").exCommand("2,3d");
        assertEquals("one\nfour", h.value());
        at(1, 0);
    }

    @Test
    public void deleteWithNoRangeTakesTheCurrentLine()
    {
        vim("one\ntwo\nthree", 1, 0).keys(":").exCommand("d");
        assertEquals("one\nthree", h.value());
    }

    @Test
    public void percentIsTheWholeBuffer()
    {
        vim("one\ntwo\nthree", 0, 0).keys(":").exCommand("%d");
        assertEquals("", h.value());
    }

    @Test
    public void theNameCanBeAbbreviatedOrSpeltOut()
    {
        vim("one\ntwo", 0, 0).keys(":").exCommand("1delete");
        assertEquals("two", h.value());
        h.close();
        vim("one\ntwo", 0, 0).keys(":").exCommand("1del");
        assertEquals("two", h.value());
    }

    @Test
    public void deleteCanNameARegisterThatPutThenReads()
    {
        vim("one\ntwo\nthree", 0, 0).keys(":").exCommand("1d a");
        assertEquals("two\nthree", h.value());
        h.keys("\"ap");
        assertEquals("two\none\nthree", h.value());
    }

    @Test
    public void yankLeavesTheTextAndFillsTheRegister()
    {
        vim("one\ntwo\nthree", 0, 0).keys(":").exCommand("2y");
        assertEquals("one\ntwo\nthree", h.value());
        h.keys("p");
        assertEquals("one\ntwo\ntwo\nthree", h.value());
    }

    // -------------------------------------------------------- substitute

    @Test
    public void substituteChangesTheFirstMatchOnTheLine()
    {
        vim("one two three", 0, 0).keys(":").exCommand("s/two/2/");
        assertEquals("one 2 three", h.value());
    }

    @Test
    public void theGFlagChangesEveryMatchOnTheLine()
    {
        vim("aaa bbb aaa", 0, 0).keys(":").exCommand("s/aaa/x/g");
        assertEquals("x bbb x", h.value());
    }

    @Test
    public void aRangeAppliesTheSubstituteToEveryLineInIt()
    {
        vim("a1\nb2\nc3", 0, 0).keys(":").exCommand("%s/[0-9]/N/");
        assertEquals("aN\nbN\ncN", h.value());
        at(2, 0);
    }

    @Test
    public void anyPunctuationCanBeTheSeparator()
    {
        vim("a/b", 0, 0).keys(":").exCommand("s#/#-#");
        assertEquals("a-b", h.value());
    }

    @Test
    public void anEscapedSeparatorIsPartOfTheField()
    {
        vim("a/b", 0, 0).keys(":").exCommand("s/\\//-/");
        assertEquals("a-b", h.value());
    }

    @Test
    public void theTrailingFieldsMayBeLeftOut()
    {
        // :s/a/b and :s/a are both legal; the second deletes the match.
        vim("one two", 0, 0).keys(":").exCommand("s/two/2");
        assertEquals("one 2", h.value());
        h.close();
        vim("one two", 0, 0).keys(":").exCommand("s/ two");
        assertEquals("one", h.value());
    }

    @Test
    public void ampersandStandsForTheWholeMatch()
    {
        vim("abc", 0, 0).keys(":").exCommand("s/b/[&]/");
        assertEquals("a[b]c", h.value());
    }

    @Test
    public void aGroupIsSpeltWithABackslash()
    {
        vim("john smith", 0, 0).keys(":").exCommand("s/(\\w+) (\\w+)/\\2 \\1/");
        assertEquals("smith john", h.value());
    }

    @Test
    public void aDollarInTheReplacementIsALiteral()
    {
        // Java would read it as a group reference.
        vim("abc", 0, 0).keys(":").exCommand("s/b/$/");
        assertEquals("a$c", h.value());
    }

    @Test
    public void anEmptyPatternMeansTheLastSearch()
    {
        vim("one two one", 0, 0).keys("/").searchPattern("one");
        h.keys(":").exCommand("s//X/g");
        assertEquals("X two X", h.value());
    }

    @Test
    public void aBareSubstituteRepeatsTheLastOne()
    {
        vim("aa\naa", 0, 0).keys(":").exCommand("s/a/X/");
        assertEquals("Xa\naa", h.value());
        h.keys("j").keys(":").exCommand("s");
        assertEquals("Xa\nXa", h.value());
    }

    @Test
    public void substituteSetsThePatternThatNThenRepeats()
    {
        vim("one two\nthree two", 0, 0).keys(":").exCommand("s/two/2/");
        h.keys("n");
        assertEquals("a search for two finds the second line", 1, h.lineNumber());
    }

    @Test
    public void aPatternRegexCannotParseIsReportedNotThrown()
    {
        vim("abc", 0, 0).keys(":").exCommand("s/a[b/x/");
        assertEquals("abc", h.value());
    }

    // ------------------------------------------------------ refusals

    @Test
    public void anAddressPastTheEndIsRefused()
    {
        // Vim errors rather than quietly using the last line.
        vim("l1\nl2\nl3", 0, 0).keys(":").exCommand("50s/l/L/");
        assertEquals("l1\nl2\nl3", h.value());
        h.keys(":").exCommand("50");
        at(0, 0);
    }

    @Test
    public void butACountPastTheEndClamps()
    {
        // Vim is asymmetric here: an address errors, a count takes what
        // there is. Both checked against nvim.
        vim("1\n2\n3\n4\n5", 0, 0).keys(":").exCommand("1,3d 100");
        assertEquals("1\n2", h.value());
    }

    @Test
    public void theRegisterAndCountNeedNoSpaceBetweenThem()
    {
        vim("1\n2\n3\n4\n5", 0, 0).keys(":").exCommand("1,3d a2");
        assertEquals("1\n2\n5", h.value());
    }

    @Test
    public void aBangTheCommandDoesNotTakeIsRefused()
    {
        vim("a\nb", 0, 0).keys(":").exCommand("d!");
        assertEquals("a\nb", h.value());
    }

    @Test
    public void butABangAfterSIsItsSeparator()
    {
        // :s takes any punctuation as its delimiter, ! included, so eating
        // that ! as a bang would leave a!b! to be split on 'a'.
        vim("aXb", 0, 0).keys(":").exCommand("s!X!-!");
        assertEquals("a-b", h.value());
    }

    @Test
    public void aFlagThatIsNotUnderstoodIsRefused()
    {
        // Saying nothing would be worse for c (confirm) than an error: the
        // user asked to be asked, and would get the lot replaced silently.
        vim("aba", 0, 0).keys(":").exCommand("s/a/X/z");
        assertEquals("aba", h.value());
        h.close();
        vim("aba", 0, 0).keys(":").exCommand("s/a/X/gc");
        assertEquals("aba", h.value());
    }

    @Test
    public void aTrailingBackslashInTheReplacementIsALiteral()
    {
        // It used to reach appendReplacement as a lone backslash, which
        // throws IllegalArgumentException -- past the BadCommand catch.
        vim("ab", 0, 0).keys(":").exCommand("s/b/x\\");
        assertEquals("ax\\", h.value());
    }

    @Test
    public void thereIsNoEmptyMatchAtTheEndOfTheLine()
    {
        vim("ab", 0, 0).keys(":").exCommand("s/x*/-/g");
        assertEquals("-a-b", h.value());
        h.close();
        vim("ab", 0, 0).keys(":").exCommand("s/b*/-/g");
        assertEquals("-a-", h.value());
    }

    @Test
    public void aJCommandStillRuns()
    {
        // The fall-through to j's own command table, which is what makes
        // :findTagAtDot work without being listed as an ex command.
        vim("one\ntwo", 0, 0).keys(":").exCommand("eol");
        at(0, 3);
    }

    @Test
    public void butARangeOnOneIsRefusedRatherThanDropped()
    {
        // j's commands know nothing of ranges, so one given here would be
        // dropped and the command would run somewhere else without saying
        // so. The same eol, which would otherwise move the caret.
        vim("one\ntwo", 0, 0).keys(":").exCommand("1,2eol");
        at(0, 0);
    }

    // ------------------------------------------------------ from visual

    @Test
    public void colonFromVisualModeFillsInTheSelectionsRange()
    {
        vim("one\ntwo\nthree\nfour", 1, 0).keys("Vj:");
        assertTrue(h.awaitingExCommand());
        h.exCommand("'<,'>d");
        assertEquals("one\nfour", h.value());
    }

    @Test
    public void andTheSeedIsThereWithoutTypingIt()
    {
        // The frameless prompt collects into the same buffer the location
        // bar would have been pre-filled with, so d alone acts on the
        // selection rather than on one line.
        vim("aa\naa\naa", 0, 0).keys("Vj:s/a/X/<CR>");
        assertEquals("Xa\nXa\naa", h.value());
    }

    @Test
    public void colonLeavesVisualModeTheWayVimDoes()
    {
        vim("one\ntwo", 0, 0).keys("v:");
        h.keys("<Esc>");
        h.keys("x");
        assertEquals("the selection is gone, so x takes one character",
                     "ne\ntwo", h.value());
    }

    // ------------------------------------------------ repeating an ex line

    @Test
    public void dotDoesNotRepeatAnExCommand()
    {
        // Vim's '.' repeats the last *change*, and an ex command is not one:
        // after :s the dot still replays whatever was changed before it.
        // Checked against nvim, which replays the C here, not the substitute.
        vim("a\nb\nc", 0, 0).keys("CZ<Esc>").keys("2G");
        h.exCommand("s/b/x");
        assertEquals("Z\nx\nc", h.value());
        h.keys(".");
        assertEquals("Z\nZ\nc", h.value());
    }

    @Test
    public void atColonRunsTheLastExLineAgain()
    {
        vim("aaaaa", 0, 0).keys(":").exCommand("s/a/b");
        assertEquals("baaaa", h.value());
        h.keys("@:");
        assertEquals("bbaaa", h.value());
    }

    @Test
    public void atColonTakesACount()
    {
        vim("aaaaa", 0, 0).keys(":").exCommand("s/a/b");
        h.keys("2@:");
        assertEquals("bbbaa", h.value());
        at(0, 0);
    }

    @Test
    public void atColonWithNothingToRepeatDoesNothing()
    {
        vim("abc", 0, 0).keys("@:");
        assertEquals("abc", h.value());
    }

    @Test
    public void aLineThatFailedIsNotWorthRepeating()
    {
        // The failing line must not become what @: repeats.
        vim("aa", 0, 0).keys(":").exCommand("s/a/X/");
        assertEquals("Xa", h.value());
        h.keys(":").exCommand("nosuchcommand");
        h.keys("@:");
        assertEquals("@: still repeats the substitute", "XX", h.value());
    }

    // ------------------------------------------------- j's own commands

    @Test
    public void anUnknownNameIsReportedRatherThanRun()
    {
        vim("abc", 0, 0).keys(":").exCommand("nosuchcommand");
        assertEquals("abc", h.value());
    }

    @Test
    public void aSubstituteContainingAnEqualsIsNotAPropertyAssignment()
    {
        // Editor.executeCommand reads anything with an = in it as
        // "set this property", which would silently eat the command.
        vim("a=b", 0, 0).keys(":").exCommand("s/a=b/ok/");
        assertEquals("ok", h.value());
    }

    // ------------------------------------------------------- the prompt

    @Test
    public void colonWaitsForALineAndEscapeAbandonsIt()
    {
        vim("abc", 0, 0).keys(":");
        assertTrue(h.awaitingExCommand());
        h.keys("<Esc>");
        assertFalse(h.awaitingExCommand());
        // The next key must be a command again, not more of the line.
        h.keys("x");
        assertEquals("bc", h.value());
    }

    @Test
    public void theWholeLineCanBeTypedAsOneSequence()
    {
        // Which is also how the conformance corpus spells an ex command.
        vim("one two three", 0, 0).keys(":s/two/2/<CR>");
        assertEquals("one 2 three", h.value());
    }
}
