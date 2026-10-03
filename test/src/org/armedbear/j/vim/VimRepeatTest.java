/*
 * VimRepeatTest.java
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
import org.junit.jupiter.api.Test;

/**
 * The dot command. Every expectation here was checked against real nvim with
 * tools/vim-oracle.sh.
 */
public class VimRepeatTest {
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
    public void repeatsADeleteOfACharacter() {
        vim("abcdef\n").cursor(0, 0).keys("x.");
        h.assertText("cdef\n");
    }

    @Test
    public void repeatsAnInsertThatCorrectedItself() {
        // The replay used to insert the backspace as a character rather than
        // taking one back, so the repeat and the original differed.
        vim("XY\n").cursor(0, 0).keys("iab<BS>c<Esc>");
        h.assertText("acXY\n");
        h.keys(".");
        h.assertText("aaccXY\n");
    }

    @Test
    public void repeatsAnOperatorWithItsMotion() {
        vim("one two three\n").cursor(0, 0).keys("dw.");
        h.assertText("three\n");
    }

    @Test
    public void repeatsALinewiseDelete() {
        vim("a\nb\nc\nd\n").cursor(0, 0).keys("dd.");
        h.assertText("c\nd\n");
    }

    @Test
    public void repeatsAChangeIncludingTheTextTyped() {
        vim("alpha bravo\n").cursor(0, 0).keys("cwX<Esc>w.");
        h.assertText("X X\n");
    }

    @Test
    public void repeatsAnInsert() {
        vim("ab\n").cursor(0, 0).keys("iX<Esc>.");
        h.assertText("XXab\n");
    }

    @Test
    public void repeatsAPut() {
        vim("abc\n").cursor(0, 0).keys("ylp.");
        h.assertText("aaabc\n");
    }

    @Test
    public void repeatsAJoin() {
        vim("a\nb\nc\n").cursor(0, 0).keys("J.");
        h.assertText("a b c\n");
    }

    @Test
    public void aCountOnTheDotReplacesTheOriginalOne() {
        // x deleted one; 2. deletes two more, not one more twice.
        vim("abc\n").cursor(0, 0).keys("x2.");
        h.assertText("\n");
    }

    @Test
    public void aYankIsNotAChangeSoDotSkipsIt() {
        // The dot still repeats the delete, not the yank in between.
        vim("abcdef\n").cursor(0, 0).keys("xyl.");
        h.assertText("cdef\n");
    }

    @Test
    public void aMotionIsNotAChangeEither() {
        // The dot repeats the delete, at wherever the caret has got to.
        vim("abcdef\n").cursor(0, 0).keys("xll.");
        h.assertText("bcef\n");
    }

    @Test
    public void dotWithNothingToRepeatDoesNothing() {
        vim("abc\n").cursor(0, 0).keys(".");
        h.assertText("abc\n");
    }

    @Test
    public void repeatingIsUndoableAsItsOwnStep() {
        vim("abcdef\n").cursor(0, 0).keys("x.");
        h.assertText("cdef\n");
        h.editor().undo();
        h.assertText("bcdef\n");
    }

    @Test
    public void aRepeatedChangeCanItselfBeRepeated() {
        vim("one two three four\n").cursor(0, 0).keys("dw..");
        h.assertText("four\n");
    }

    @Test
    public void repeatUsesTheCaretsNewPosition() {
        vim("aXbXc\n").cursor(0, 1).keys("x");
        assertEquals(1, h.offset());
        h.keys("l.");
        h.assertText("abc\n");
    }
}
