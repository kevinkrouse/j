/*
 * ParenFlashTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** closeParen highlights the matching paren and leaves the caret where it was. */
public class ParenFlashTest {
    private EditorHarness h;

    @BeforeEach
    public void setUp() {
        Editor.preferences().setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, "false");
        Editor.preferences().setProperty(Property.HIGHLIGHT_BRACKETS, "false");
        // Long enough that the timer cannot end the flash mid-test.
        Editor.parenFlashMillis = 60000;
        h = EditorHarness.create("f(a\n").mode(JavaMode.getMode());
    }

    @AfterEach
    public void tearDown() {
        Editor.preferences().removeProperty(Property.HIGHLIGHT_MATCHING_BRACKET.key());
        Editor.preferences().removeProperty(Property.HIGHLIGHT_BRACKETS.key());
        Editor.parenFlashMillis = 300;
        h.close();
    }

    @Test
    public void theMatchIsHighlightedAndTheCaretStays() {
        h.cursor(0, 3).keys(")");
        h.assertCursorAt(0, 4);
        assertEquals(1, h.editor().getDisplay().getMatchingBracketPosition().getOffset());
        ElectricCommands.endParenFlash(h.editor());
        assertNull(h.editor().getDisplay().getMatchingBracketPosition());
    }

    @Test
    public void aKeyDuringTheFlashGoesAfterTheParen() {
        h.cursor(0, 3).keys(");");
        h.assertText("f(a);\n");
        h.assertCursorAt(0, 5);
    }
}
