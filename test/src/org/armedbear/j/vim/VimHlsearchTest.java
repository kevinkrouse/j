/*
 * VimHlsearchTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.armedbear.j.EditorHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Hlsearch, on by default as in nvim; :noh, and :set at the prompt.
 * What nvim highlights was read with screenattr() after redraw!.
 */
public class VimHlsearchTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text) {
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).cursor(0, 0);
        return h;
    }

    @Test
    public void nothingIsHighlightedBeforeASearch() {
        vim("abc abc");
        assertEquals("", h.searchMatches(0));
    }

    @Test
    public void everyMatchOfTheLastPatternOnEveryLine() {
        vim("abc abc\nxyz\nab").keys("/b<CR>");
        assertEquals("1-2 5-6", h.searchMatches(0));
        assertEquals("", h.searchMatches(1));
        assertEquals("1-2", h.searchMatches(2));
        vim("abc abc").keys("/bc a<CR>");
        assertEquals("1-5", h.searchMatches(0));
    }

    @Test
    public void anEmptyMatchIsReportedEmpty() {
        // Painted one character wide, as nvim does.
        vim("abc abc").keys("/\\<lt><CR>");
        assertEquals("0-0 4-4", h.searchMatches(0));
    }

    @Test
    public void theMatchesAreTheOnesNFinds() {
        vim("aaa aa").keys("/a\\+<CR>");
        assertEquals("0-3 4-6", h.searchMatches(0));
    }

    @Test
    public void starAndSubstituteSetThePatternToo() {
        vim("ab abc ab").keys("*");
        assertEquals("0-2 7-9", h.searchMatches(0));
        vim("ab abc ab").keys(":s/c/C/<CR>");
        assertEquals("", h.searchMatches(0));
        vim("ab abc ab").keys(":s/b/b/g<CR>");
        assertEquals("1-2 4-5 8-9", h.searchMatches(0));
    }

    @Test
    public void nohHidesThemUntilTheNextSearch() {
        vim("abc abc").keys("/b<CR>").keys(":noh<CR>");
        assertEquals("", h.searchMatches(0));
        // An edit does not bring them back; n does.
        h.keys("x");
        assertEquals("", h.searchMatches(0));
        h.keys("n");
        assertEquals("4-5", h.searchMatches(0));
        h.keys(":nohlsearch<CR>").keys("/c<CR>");
        assertEquals("1-2 5-6", h.searchMatches(0));
    }

    @Test
    public void settingHlsearchBringsThemBack() {
        vim("abc abc").keys("/b<CR>").keys(":noh<CR>").keys(":set hls<CR>");
        assertEquals("1-2 5-6", h.searchMatches(0));
    }

    @Test
    public void nohlsearchTurnsItOff() {
        vim("abc abc").keys(":set nohlsearch<CR>").keys("/b<CR>");
        assertEquals("", h.searchMatches(0));
        h.keys(":set hlsearch<CR>");
        assertEquals("1-2 5-6", h.searchMatches(0));
    }

    @Test
    public void optionsTheMatchingReadsAreFollowed() {
        vim("ab AB").keys("/ab<CR>");
        assertEquals("0-2", h.searchMatches(0));
        h.keys(":set ignorecase<CR>");
        assertEquals("0-2 3-5", h.searchMatches(0));
    }

    @Test
    public void aBadPatternHighlightsNothing() {
        vim("a(b").keys("/\\(<CR>");
        assertEquals("", h.searchMatches(0));
    }

    @Test
    public void theWholeWindowIsRepaintedWhenTheMatchesChange() {
        vim("abc abc\nabc");
        for (String keys : Arrays.asList(
            "/b<CR>",
            ":noh<CR>",
            "n",
            ":set nohls<CR>"
        )) {
            h.clearRepaintPending();
            h.keys(keys);
            assertTrue(h.repaintPending(), keys);
        }
    }
}
