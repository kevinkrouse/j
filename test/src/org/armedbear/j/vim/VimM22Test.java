/*
 * VimM22Test.java
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
import org.armedbear.j.Search;
import org.junit.After;
import org.junit.Test;

/**
 * M22: one last search for vim edit mode and j's find, and highlighting
 * that is j's.
 */
public class VimM22Test
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
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).cursor(0, 0);
        return h;
    }

    private String caret()
    {
        return h.lineNumber() + "," + h.offset();
    }

    private static Search literal(String pattern, boolean ignoreCase)
    {
        return new Search(pattern, ignoreCase, false);
    }

    private static Search regex(String pattern)
    {
        final Search search = new Search(pattern, false, false);
        search.setRegularExpression(true);
        search.setREFromPattern();
        return search;
    }

    @Test
    public void findNextGoesOnWithASlashSearch()
    {
        vim("ab ab ab").keys("/b<CR>");
        assertEquals("0,1", caret());
        h.editor().findNext();
        assertEquals("0,4", caret());
    }

    @Test
    public void nGoesOnWithAFindOfJs()
    {
        vim("x.b xyb x.b").editor().setLastSearch(literal("x.b", false));
        h.keys("n");
        assertEquals("0,8", caret());
        vim("x.b xyb x.b").editor().setLastSearch(regex("x.b"));
        h.keys("n");
        assertEquals("0,4", caret());
        vim("ab AB ab").editor().setLastSearch(literal("AB", true));
        h.keys("n");
        assertEquals("0,3", caret());
        h.keys("N");
        assertEquals("0,0", caret());
    }

    @Test
    public void aFindOfJsRunsAsItIsNotAsVimWouldReadIt()
    {
        // Java's lookbehind, which vim's pattern syntax spells otherwise.
        vim("cb ab").editor().setLastSearch(regex("(?<=a)b"));
        h.keys("n");
        assertEquals("0,4", caret());
    }

    @Test
    public void anEmptySubstitutePatternIsAFindOfJs()
    {
        vim("xyb x.b").editor().setLastSearch(literal("x.b", false));
        h.keys(":s//Z/<CR>");
        assertEquals("xyb Z", h.value());
    }

    @Test
    public void hlsearchShowsAFindOfJs()
    {
        vim("ab ab").editor().setLastSearch(literal("b", false));
        assertEquals("1-2 4-5", h.searchMatches(0));
    }

    @Test
    public void nohAndClearSearchHighlightAreOne()
    {
        vim("ab ab").keys("/b<CR>");
        h.editor().clearSearchHighlight();
        assertEquals("", h.searchMatches(0));
        h.editor().findNext();
        assertEquals("1-2 4-5", h.searchMatches(0));
    }

    @Test
    public void aBadPatternIsKeptAsVimKeepsIt()
    {
        vim("xb yb zb").keys("/b<CR>").keys("/\\(<CR>");
        assertEquals("\\(", h.vimState().getLastSearch(h.editor()).pattern);
        h.keys("n");
        assertEquals("0,1", caret());
        assertEquals("", h.searchMatches(0));
        h.editor().findNext();
        assertEquals("0,1", caret());
    }
}
