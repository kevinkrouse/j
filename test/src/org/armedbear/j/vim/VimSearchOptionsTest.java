/*
 * VimSearchOptionsTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Property;
import org.junit.After;
import org.junit.Test;

/**
 * The last search pattern shared between windows or not (the
 * shareSearch preference), incsearch, and smartcase on by default.
 */
public class VimSearchOptionsTest
{
    private EditorHarness h;
    private EditorHarness other;

    @After
    public void tearDown()
    {
        Editor.preferences().removeProperty(Property.SHARE_SEARCH.key());
        if (h != null)
            h.close();
        if (other != null)
            other.close();
        h = other = null;
    }

    private EditorHarness vim(String text)
    {
        final EditorHarness harness = EditorHarness.create().vim();
        harness.value(text).cursor(0, 0);
        return harness;
    }

    private static String caret(EditorHarness harness)
    {
        return harness.lineNumber() + "," + harness.offset();
    }

    // ------------------------------------------------------- shareSearch

    @Test
    public void thePatternIsSharedByDefault()
    {
        h = vim("abc abc");
        other = vim("xbx");
        h.keys("/b<CR>");
        assertEquals("0,1", caret(other.keys("n")));
        assertEquals("1-2", other.searchMatches(0));
    }

    @Test
    public void aSharedPatternRepaintsTheOtherWindows()
    {
        h = vim("abc abc");
        other = vim("xbx");
        other.clearRepaintPending();
        h.keys("/b<CR>");
        assertTrue(other.repaintPending());
        other.clearRepaintPending();
        h.keys(":noh<CR>");
        assertTrue(other.repaintPending());
        assertEquals("", other.searchMatches(0));
    }

    @Test
    public void eachWindowCanKeepItsOwn()
    {
        Editor.preferences().setProperty(Property.SHARE_SEARCH, "false");
        h = vim("abc abc");
        other = vim("xbx");
        h.keys("/b<CR>");
        assertNull(other.vimState().getLastSearch(other.editor()));
        assertEquals("", other.searchMatches(0));
        assertEquals("0,0", caret(other.keys("n")));
        other.keys("/x<CR>").keys(":noh<CR>");
        assertEquals("1-2 5-6", h.searchMatches(0));
    }

    // --------------------------------------------------------- incsearch

    @Test
    public void typingThePatternShowsWhereItGoes()
    {
        h = vim("abc abc\nxyz");
        h.keys("/").searchTyped("b");
        assertEquals("0,1", caret(h));
        h.searchTyped("y");
        assertEquals("1,1", caret(h));
    }

    @Test
    public void withHlsearchEveryMatchOfTheTypedPatternShows()
    {
        h = vim("abc abc");
        h.keys("/c<CR>").keys(":noh<CR>").keys("/").searchTyped("b");
        assertEquals("1-2 5-6", h.searchMatches(0));
    }

    @Test
    public void theMatchTheCaretIsOnHasItsOwnColour()
    {
        h = vim("abc abc");
        h.keys("w/").searchTyped("b");
        assertEquals("5-6", h.currentSearchMatch(0));
        assertEquals("1-2 5-6", h.searchMatches(0));
        h.searchPattern("b");
        assertEquals("", h.currentSearchMatch(0));
    }

    @Test
    public void withoutHlsearchOnlyTheMatchTheCaretIsOn()
    {
        h = vim("abc abc");
        h.keys(":set nohls<CR>").keys("/").searchTyped("b");
        assertEquals("", h.searchMatches(0));
        assertEquals("1-2", h.currentSearchMatch(0));
    }

    @Test
    public void aPatternNotFoundPutsTheCaretBack()
    {
        h = vim("abc abc");
        h.keys("w/").searchTyped("b");
        assertEquals("0,5", caret(h));
        h.searchTyped("bq");
        assertEquals("0,4", caret(h));
        assertEquals("", h.searchMatches(0));
        // Half typed, which is not an error yet.
        h.searchTyped("\\(");
        assertEquals("0,4", caret(h));
    }

    @Test
    public void enterSearchesFromWhereTheSearchWasTyped()
    {
        // From the preview, at 0,1, the next b would be 0,5.
        h = vim("abc abc");
        h.keys("/").searchTyped("b").searchPattern("b");
        assertEquals("0,1", caret(h));
        assertEquals("1-2 5-6", h.searchMatches(0));
    }

    @Test
    public void anOperatorTakesTheTextFromWhereItWasTyped()
    {
        h = vim("abc abc");
        h.keys("d/").searchTyped("c a").searchPattern("c a");
        assertEquals("c abc", h.value());
    }

    @Test
    public void escapePutsEverythingBack()
    {
        h = vim("abc abc");
        h.keys("/c<CR>").keys("gg/").searchTyped("b").keys("<Esc>");
        assertEquals("0,0", caret(h));
        assertEquals("2-3 6-7", h.searchMatches(0));
        assertEquals("c", h.vimState().getLastSearch(h.editor()).pattern);
    }

    @Test
    public void noincsearchLeavesTheCaretAlone()
    {
        h = vim("abc abc");
        h.keys(":set nois<CR>").keys("/").searchTyped("b");
        assertEquals("0,0", caret(h));
    }

    // ------------------------------------------ CTRL-G and CTRL-T at the /

    private String step(String text, int col, String keys)
    {
        h = vim(text);
        h.cursor(0, col).keys(keys);
        return h.value() + " @" + caret(h);
    }

    @Test
    public void ctrlGAndCtrlTStepThroughTheMatches()
    {
        final String text = "ab ab ab ab";
        assertEquals(text + " @0,6", step(text, 0, "/ab<C-g><CR>"));
        tearDown();
        assertEquals(text + " @0,9", step(text, 0, "/ab<C-g><C-g><CR>"));
        tearDown();
        assertEquals(text + " @0,3", step(text, 0, "/ab<C-g><C-t><CR>"));
        tearDown();
        // Round the end and back to the first.
        assertEquals(text + " @0,3",
                     step(text, 0, "/ab<C-g><C-g><C-g><C-g><CR>"));
    }

    @Test
    public void ctrlTBackToTheCaretIsWhereASearchCannotStop()
    {
        // The match at the caret is shown, and Enter goes on to the next,
        // as in nvim; a second CTRL-T wraps to the last.
        assertEquals("ab ab ab ab @0,3", step("ab ab ab ab", 0, "/ab<C-t><CR>"));
        tearDown();
        final String text = "ab ab ab ab ab ab";
        assertEquals(text + " @0,15", step(text, 0, "/ab<C-t><C-t><CR>"));
        tearDown();
        assertEquals(text + " @0,6", step(text, 7, "/ab<C-t><CR>"));
    }

    @Test
    public void ctrlGIsForwardInTheBufferEvenForAQuestionMark()
    {
        final String text = "ab ab ab ab";
        assertEquals(text + " @0,0", step(text, 10, "?ab<C-g><CR>"));
        tearDown();
        assertEquals(text + " @0,6", step(text, 10, "?ab<C-t><CR>"));
    }

    @Test
    public void afterAStepTheSearchGoesOnFromThere()
    {
        final String text = "ab ab ab ab";
        assertEquals(text + " @0,9", step(text, 0, "/ab<C-g><CR>n"));
        tearDown();
        assertEquals(text + " @0,6", step(text, 0, "/a<C-g>b<CR>"));
    }

    @Test
    public void anOperatorTakesTheTextToTheMatchSteppedTo()
    {
        assertEquals("ab ab @0,0", step("ab ab ab ab", 0, "d/ab<C-g><CR>"));
    }

    @Test
    public void escapeAfterAStepPutsTheCaretBack()
    {
        assertEquals("ab ab ab ab @0,0",
                     step("ab ab ab ab", 0, "/ab<C-g><Esc>"));
    }

    @Test
    public void theMatchSteppedToIsTheOneInItsOwnColour()
    {
        h = vim("ab ab ab ab");
        h.keys("/ab<C-g>");
        assertEquals("6-8", h.currentSearchMatch(0));
        // The prompt sends the pattern again as CTRL-G is let go.
        h.searchTyped("ab");
        assertEquals("0,6", caret(h));
    }

    @Test
    public void aCountIsSpentOnTheFirstMatchShown()
    {
        // nvim lands on 0,12 here, applying the count again from the match
        // it stepped to; j goes where CTRL-G showed. Documented.
        final String text = "ab ab ab ab ab ab";
        assertEquals(text + " @0,9", step(text, 0, "2/ab<C-g><CR>"));
    }

    @Test
    public void aVimrcCmapReachesTheCMap()
    {
        h = EditorHarness.create().vim("cmap <C-j> <C-g>\n");
        h.value("ab ab ab ab").cursor(0, 0).keys("/ab<C-j><CR>");
        assertEquals("0,6", caret(h));
    }

    // --------------------------------------------------------- smartcase

    @Test
    public void smartcaseIsOnByDefault()
    {
        h = vim("ab Ab");
        h.keys(":set ic<CR>").keys("/ab<CR>");
        assertEquals("0-2 3-5", h.searchMatches(0));
        h.keys("/Ab<CR>");
        assertEquals("3-5", h.searchMatches(0));
        h.keys(":set nosmartcase<CR>").keys("/Ab<CR>");
        assertEquals("0-2 3-5", h.searchMatches(0));
    }

    @Test
    public void aSwitchTogglesFromItsDefault()
    {
        h = vim("abc");
        h.keys(":set hls!<CR>").keys("/b<CR>");
        assertEquals("", h.searchMatches(0));
    }
}
