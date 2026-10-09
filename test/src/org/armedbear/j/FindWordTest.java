/*
 * FindWordTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * findNextWord and findPrevWord, which are vim's * and #: the word under the
 * caret or next along its line, whole words, wrapping, and the direction
 * kept for the search after.
 */
public class FindWordTest {
    /** "foo" whole-word at 0,0 then 1,7 then 2,4; "foobar" at 1,0. */
    private static final String WORDS = "foo bar\nfoobar foo\nbar foo\n";

    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void on(int line, int offset) {
        if (h == null) {
            h = EditorHarness.create(WORDS);
            Editor.setCurrentEditor(h.editor());
        }
        h.cursor(line, offset);
    }

    private void at(int line, int offset) {
        assertEquals(line, h.editor().getDotLineNumber(), "line");
        assertEquals(offset, h.editor().getDotOffset(), "offset");
    }

    @Test
    public void theNextWholeWordAndThenAroundTheTop() {
        on(0, 1);
        SearchCommands.findNextWord(h.editor());
        at(1, 7);
        SearchCommands.findNextWord(h.editor());
        at(2, 4);
        SearchCommands.findNextWord(h.editor());
        at(0, 0);
    }

    @Test
    public void theWordAfterTheCaretOnItsLine() {
        on(0, 3);
        SearchCommands.findNextWord(h.editor());
        // " bar" from the space before it: bar at 2,0.
        at(2, 0);
    }

    @Test
    public void thePreviousWholeWordAndThenAroundTheBottom() {
        on(1, 8);
        SearchCommands.findPrevWord(h.editor());
        at(0, 0);
        SearchCommands.findPrevWord(h.editor());
        at(2, 4);
    }

    @Test
    public void partialMatchesInsideWords() {
        on(0, 0);
        SearchCommands.findNextWord(h.editor(), "partial");
        at(1, 0);
    }

    @Test
    public void theLastSearchKeepsItsDirection() {
        on(2, 4);
        SearchCommands.findPrevWord(h.editor());
        assertFalse(h.editor().getLastSearch().isForward());
    }
}
