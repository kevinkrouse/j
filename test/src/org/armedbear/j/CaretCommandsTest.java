/*
 * CaretCommandsTest.java
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
 * The commands vim has and j did not: jumping to a character on the line,
 * jumping to the top, middle or bottom of the window, and overwriting one
 * character. Vim's f, t, H, M, L and r are these.
 */
public class CaretCommandsTest {
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

    private void at(int line, int offset) {
        assertEquals(line, h.editor().getDotLineNumber(), "line");
        assertEquals(offset, h.editor().getDotOffset(), "offset");
    }

    // ------------------------------------------------- find a character

    @Test
    public void findCharInLineJumpsForward() {
        h = EditorHarness.create("alpha bravo\n");
        on(0, 0);
        CaretCommands.findCharInLine("b");
        at(0, 6);
    }

    @Test
    public void tillCharInLineStopsOneShort() {
        h = EditorHarness.create("alpha bravo\n");
        on(0, 0);
        CaretCommands.tillCharInLine("b");
        at(0, 5);
    }

    @Test
    public void theBackwardFormsLookTheOtherWay() {
        h = EditorHarness.create("alpha bravo\n");
        on(0, 10);
        CaretCommands.findCharInLineBackward("a");
        at(0, 8);
        on(0, 10);
        CaretCommands.tillCharInLineBackward("a");
        at(0, 9);
    }

    @Test
    public void itNeverLeavesTheLine() {
        h = EditorHarness.create("alpha\nbravo\n");
        on(0, 0);
        CaretCommands.findCharInLine("v");
        at(0, 0);
    }

    @Test
    public void aMissingArgumentIsReportedRatherThanGuessed() {
        h = EditorHarness.create("abc\n");
        on(0, 1);
        CaretCommands.findCharInLine("");
        at(0, 1);
    }

    // --------------------------------------------- top, middle, bottom

    @Test
    public void theWindowCommandsMoveTheCaretWithoutScrolling() {
        h = EditorHarness.create("1\n2\n3\n4\n5\n6\n7\n8\n9\n");
        on(4, 0);
        final Line wasTop = h.editor().getDisplay().getTopLine();
        CaretCommands.moveToWindowTop();
        at(0, 0);
        assertEquals(
            wasTop,
            h.editor().getDisplay().getTopLine(),
            "the view did not move"
        );
        CaretCommands.moveToWindowBottom();
        final int bottom = h.editor().getDotLineNumber();
        CaretCommands.moveToWindowMiddle();
        final int middle = h.editor().getDotLineNumber();
        assertEquals(
            true,
            middle > 0 && middle < bottom,
            "middle is between top and bottom"
        );
    }

    @Test
    public void theyLandOnTheFirstNonBlank() {
        h = EditorHarness.create("    indented\n2\n3\n");
        on(2, 0);
        CaretCommands.moveToWindowTop();
        at(0, 4);
    }

    // ------------------------------------------- overwrite a character

    @Test
    public void replaceCharPutsOneCharacterInPlace() {
        h = EditorHarness.create("abc\n");
        on(0, 1);
        CaretCommands.replaceChar("X");
        h.assertText("aXc\n");
        at(0, 1);
    }

    @Test
    public void replaceCharDoesNothingPastTheEndOfTheLine() {
        // There is no character there to replace, and it will not lengthen
        // the line to make one.
        h = EditorHarness.create("abc\n");
        on(0, 3);
        CaretCommands.replaceChar("X");
        h.assertText("abc\n");
    }

    @Test
    public void replaceCharsIsAllOrNothing() {
        h = EditorHarness.create("abc\n");
        on(0, 0);
        assertEquals(
            false,
            CaretCommands.replaceChars(
                h.editor(),
                h.buffer().getFirstLine(),
                0,
                'X',
                5
            )
        );
        h.assertText("abc\n");
        assertEquals(
            true,
            CaretCommands.replaceChars(
                h.editor(),
                h.buffer().getFirstLine(),
                0,
                'X',
                3
            )
        );
        h.assertText("XXX\n");
    }

    // ------------------------------------------------- the command table

    @Test
    public void theCommandTableReachesThem() throws Exception {
        h = EditorHarness.create("alpha bravo\n");
        on(0, 0);
        h.editor().execute("findCharInLine", "b");
        at(0, 6);
        h.editor().execute("replaceChar", "B");
        h.assertText("alpha Bravo\n");
        h.editor().execute("moveToWindowTop", null);
        at(0, 0);
    }
}
