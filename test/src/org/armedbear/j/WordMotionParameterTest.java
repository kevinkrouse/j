/*
 * WordMotionParameterTest.java
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * j's word motions, and the argument that switches them to vim's rule.
 *
 * The two agree far more than they look like they should: j's nextWord
 * already classifies characters the way vim does, so both stop on the dot in
 * "foo.bar". Where they part is the empty line, which vim counts as a word of
 * its own and j steps over. Both expectations checked against nvim.
 */
public class WordMotionParameterTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void on(int line, int offset) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < line && l != null; i++)
            l = l.next();
        h.editor().setDot(l, offset);
        h.editor().moveCaretToDotCol();
        Editor.setCurrentEditor(h.editor());
    }

    private String where() {
        return h.editor().getDotLineNumber() + "," + h.editor().getDotOffset();
    }

    @Test
    public void vimCountsAnEmptyLineAsAWordAndJStepsOverIt() {
        h = EditorHarness.create("a\n\n\nb\n");
        on(0, 0);
        h.editor().wordRight("vim");
        assertEquals("1,0", where(), "vim stops on the first empty line");

        on(0, 0);
        h.editor().wordRight();
        assertEquals("3,0", where(), "j carries on to the next text");
    }

    @Test
    public void backwardsTheSameWay() {
        h = EditorHarness.create("a\n\n\nb\n");
        on(3, 0);
        h.editor().wordLeft("vim");
        assertEquals("2,0", where());

        on(3, 0);
        h.editor().wordLeft();
        assertEquals("0,0", where());
    }

    @Test
    public void onOrdinaryTextTheyAgree() {
        // Worth pinning: the argument is not a wholesale change of feel, and
        // anyone who turns it on should find punctuation behaving as before.
        h = EditorHarness.create("foo.bar baz\n");
        for (int from : new int[] { 0, 3, 4 }) {
            on(0, from);
            h.editor().wordRight("vim");
            final String vim = where();
            on(0, from);
            h.editor().wordRight();
            assertEquals(vim, where(), "from " + from);
        }
    }

    @Test
    public void theDefaultIsUnchanged() {
        h = EditorHarness.create("a\n\n\nb\n");
        on(0, 0);
        h.editor().wordRight();
        final String plain = where();
        on(0, 0);
        h.editor().wordRight(null);
        assertEquals(plain, where());
        on(0, 0);
        h.editor().wordRight("");
        assertEquals(plain, where(), "anything that is not \"vim\" is j's rule");
    }

    @Test
    public void theSelectingFormsTakeItAsWell() {
        h = EditorHarness.create("a\n\n\nb\n");
        on(0, 0);
        h.editor().selectWordRight("vim");
        assertEquals("1,0", where());
        assertEquals(0, h.editor().getMarkOffset(), "and they select");

        h.close();
        h = EditorHarness.create("a\n\n\nb\n");
        on(3, 0);
        h.editor().selectWordLeft("vim");
        assertEquals("2,0", where());
    }

    @Test
    public void theCommandTableReachesTheArgument() throws Exception {
        h = EditorHarness.create("a\n\n\nb\n");
        on(0, 0);
        h.editor().execute("wordRight", "vim");
        assertEquals("1,0", where());
        on(0, 0);
        h.editor().execute("wordRight", null);
        assertEquals("3,0", where());
    }
}
