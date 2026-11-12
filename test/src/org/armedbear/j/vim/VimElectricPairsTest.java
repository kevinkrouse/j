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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Electric pairs under vim's dot command. The recording stores the
 * typed key only, so a pairing handler inside {@code insertNormalChar}
 * re-derives the pair on replay. These tests pin that contract: the
 * closer is never recorded, and a type-over that changes no buffer is
 * not truncated from the recording.
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
        // Escape, go to a fresh line, repeat: the ( pairs again.
        h.keys("<Esc>");
        h.cursor(1, 0);
        h.keys(".");
        h.assertText("()\n()\n");
        h.assertCursorAt(1, 0);
    }

    @Test
    public void repeatsATypeOver() {
        // The type-over changes no buffer; the recording must still
        // carry the ) so the repeat steps over again rather than
        // producing ((|). The x after the ) makes the change visible
        // and pins the caret.
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
        // i( then ) then x: the recording is i()x<Esc>. Replaying it
        // on a fresh line gives ()x again.
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
}
