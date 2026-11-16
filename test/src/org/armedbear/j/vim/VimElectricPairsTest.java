/*
 * VimElectricPairsTest.java
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

import org.armedbear.j.EditCommands;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Electric pairs under vim's dot command: the recording stores the typed
 * key only, so {@code insertNormalChar} re-derives the pair on replay.
 * Pins that the closer is never recorded and a no-buffer-change type-over
 * is not truncated from the recording.
 */
public class VimElectricPairsTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text) {
        h = EditorHarness.create(text).vim();
        return h;
    }

    @Test
    public void repeatsAnInsertThatPaired() {
        vim("\n\n").cursor(0, 0).keys("i(");
        h.assertText("()\n\n");
        h.assertCursorAt(0, 1);
        h.keys("<Esc>");
        h.cursor(1, 0);
        h.keys(".");
        h.assertText("()\n()\n");
        h.assertCursorAt(1, 0);
    }

    @Test
    public void repeatsPairAfterJavaIdentifier() {
        vim("foo\nbar\n").mode(JavaMode.getMode()).cursor(0, 3).keys("i(<Esc>");
        h.assertText("foo()\nbar\n");
        h.cursor(1, 3).keys(".");
        h.assertText("foo()\nbar()\n");
        h.assertCursorAt(1, 3);
        EditCommands.undo(h.editor());
        h.assertText("foo()\nbar\n");
    }

    @Test
    public void repeatsATypeOver() {
        // The type-over itself changes no buffer; the recording must still
        // carry the ) so the repeat steps over instead of making ((|.
        // The trailing x makes the change visible and pins the caret.
        vim("()\n()\n").cursor(0, 1).keys("i)x");
        h.assertText("()x\n()\n");
        h.assertCursorAt(0, 3);
        h.keys("<Esc>");
        h.cursor(1, 1);
        h.keys(".");
        h.assertText("()x\n()x\n");
        h.assertCursorAt(1, 2);
    }

    @Test
    public void repeatsAPairAndTypeOverTogether() {
        // The recording is i()x<Esc>: the pair and the type-over both
        // re-derive on a fresh line.
        vim("\n\n").cursor(0, 0).keys("i()x<Esc>");
        h.assertText("()x\n\n");
        h.assertCursorAt(0, 2);
        h.cursor(1, 0);
        h.keys(".");
        h.assertText("()x\n()x\n");
        h.assertCursorAt(1, 2);
    }

    @Test
    public void repeatOfPairIsUndoableAsItsOwnStep() {
        vim("\n\n").cursor(0, 0).keys("i(<Esc>");
        h.assertText("()\n\n");
        h.cursor(1, 0);
        h.keys(".");
        h.assertText("()\n()\n");
        EditCommands.undo(h.editor());
        h.assertText("()\n\n");
    }

    @Test
    public void quotePairsAndRepeats() {
        vim("\n\n").cursor(0, 0).keys("i\"\"<Esc>");
        h.assertText("\"\"\n\n");
        h.assertCursorAt(0, 1);
        h.cursor(1, 0);
        h.keys(".");
        h.assertText("\"\"\n\"\"\n");
    }

    @Test
    public void backspaceDeletesPairThroughVimPath() {
        // i( then BS: the pair collapses to empty, and the repeat
        // reproduces the collapse.
        vim("\n\n").cursor(0, 0).keys("i(");
        h.assertText("()\n\n");
        h.keys("<BS>");
        h.assertText("\n\n");
        h.assertCursorAt(0, 0);
        h.keys("<Esc>");
        h.cursor(1, 0);
        h.keys(".");
        h.assertText("\n\n");
    }

    @Test
    public void ctrlWAfterPairRespectsTheStop() {
        // Ctrl-W deletes abc; the stop at the insert start keeps the
        // delete-both rule from taking the opener.
        vim("\n\n").cursor(0, 0).keys("i(abc");
        h.assertText("(abc)\n\n");
        h.keys("<C-w>");
        h.assertText("()\n\n");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void ctrlWAfterEmptyPairTakesTheOpenerAsVimDoes() {
        // The opener is a non-word char before the caret, so Ctrl-W
        // deletes it as vim does; delete-both never fires because
        // Ctrl-W deletes a region, leaving the closer behind.
        vim("\n\n").cursor(0, 0).keys("i(");
        h.assertText("()\n\n");
        h.assertCursorAt(0, 1);
        h.keys("<C-w>");
        h.assertText(")\n\n");
        h.assertCursorAt(0, 0);
    }
}
