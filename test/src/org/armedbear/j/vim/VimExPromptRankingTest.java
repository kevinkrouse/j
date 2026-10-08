/*
 * VimExPromptRankingTest.java
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

import org.armedbear.j.EditorHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The : prompt's list: an ex command's short form finds it ahead of j's commands. */
public class VimExPromptRankingTest {
    private EditorHarness h;

    @BeforeEach
    public void setUp() {
        h = EditorHarness.create().vim();
    }

    @AfterEach
    public void tearDown() {
        h.close();
    }

    private String first(String query) {
        return VimExPrompt.ranked(h.editor(), query).get(0);
    }

    @Test
    public void aShortFormListsItsCommandFirst() {
        assertEquals("cclose", first("ccl"));
        assertEquals("pclose", first("pcl"));
        assertEquals("helpclose", first("helpc"));
        assertEquals("vsplit", first("vs"));
    }

    @Test
    public void theWordsOfARunTogetherNameFindIt() {
        assertEquals("helpclose", first("hc"));
        assertEquals("cclose", first("cc"));
    }

    @Test
    public void jsCommandsStillMatchByName() {
        assertEquals("closeAll", first("closeall"));
    }
}
