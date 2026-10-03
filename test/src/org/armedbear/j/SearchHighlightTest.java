/*
 * SearchHighlightTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * Highlighting the matches of j's last search in simple edit mode: the
 * highlightSearchMatches preference and clearSearchHighlight.
 */
public class SearchHighlightTest {
    private EditorHarness h;

    @After
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text) {
        h = EditorHarness.create(text);
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    @Test
    public void offByDefaultInSimpleMode() {
        on("ab ab\n").editor().setLastSearch(new Search("b", false, false));
        assertEquals("", h.searchMatches(0));
    }

    @Test
    public void thePreferenceHighlightsTheLastSearch() {
        on("ab ab\n").buffer()
            .setProperty(
                Property.HIGHLIGHT_SEARCH_MATCHES,
                true
            );
        h.editor().setLastSearch(new Search("b", false, false));
        assertEquals("1-2 4-5", h.searchMatches(0));
    }

    @Test
    public void clearSearchHighlightHidesThemUntilTheNextFind() {
        on("ab ab\n").buffer()
            .setProperty(
                Property.HIGHLIGHT_SEARCH_MATCHES,
                true
            );
        h.editor().setLastSearch(new Search("b", false, false));
        h.editor().clearSearchHighlight();
        assertEquals("", h.searchMatches(0));
        h.editor().findNext();
        assertEquals("1-2 4-5", h.searchMatches(0));
        h.editor().clearSearchHighlight();
        h.editor().setLastSearch(new Search("a", false, false));
        assertEquals("0-1 3-4", h.searchMatches(0));
    }

    @Test
    public void aRegularExpressionHighlightsWhatItMatches() {
        on("ab abb\n").buffer()
            .setProperty(
                Property.HIGHLIGHT_SEARCH_MATCHES,
                true
            );
        final Search search = new Search("ab+", false, false);
        search.setRegularExpression(true);
        search.setREFromPattern();
        h.editor().setLastSearch(search);
        assertEquals("0-2 3-6", h.searchMatches(0));
    }

    @Test
    public void aNewSearchRepaintsTheWindow() {
        on("ab\n").clearRepaintPending();
        h.editor().setLastSearch(new Search("b", false, false));
        assertTrue(h.repaintPending());
        h.clearRepaintPending();
        h.editor().clearSearchHighlight();
        assertTrue(h.repaintPending());
    }
}
