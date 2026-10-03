/*
 * LinesTest.java
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
 * Joining, moving and copying whole lines.
 *
 * These are j's own commands, shared with vim's J, :join, :move and :copy
 * rather than reimplemented there. The join's expectations are vim's, checked
 * against nvim; the move and copy commands are the ones editors usually bind
 * to Alt-Up and Alt-Down, and have no vim spelling of their own.
 */
public class LinesTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void at(int line) {
        assertEquals(line, h.lineNumber(), "line");
    }

    private void on(int line) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < line && l != null; i++)
            l = l.next();
        h.editor().setDot(l, 0);
        h.editor().moveCaretToDotCol();
        Editor.setCurrentEditor(h.editor());
    }

    // ---------------------------------------------------------------- join

    @Test
    public void joinLinesPullsTheNextLineUp() {
        h = EditorHarness.create("one\n    two\nthree\n");
        on(0);
        Lines.joinLines();
        h.assertText("one two\nthree\n");
    }

    @Test
    public void joinLinesTakesACountOfLines() {
        h = EditorHarness.create("a\nb\nc\nd\n");
        on(0);
        Lines.joinLines("3");
        h.assertText("a b c\nd\n");
    }

    @Test
    public void joinLinesJoinsWhatTheSelectionCovers() {
        h = EditorHarness.create("a\nb\nc\nd\n");
        on(0);
        final Line third = h.buffer().getFirstLine().next().next();
        h.editor().setMark(new Position(h.buffer().getFirstLine(), 0));
        h.editor().setDot(third, 0);
        h.editor().moveCaretToDotCol();
        Editor.setCurrentEditor(h.editor());
        Lines.joinLines();
        h.assertText("a b c\nd\n");
    }

    @Test
    public void joinLinesAddsNoSecondSpace() {
        h = EditorHarness.create("a \n   b\n");
        on(0);
        Lines.joinLines();
        h.assertText("a b\n");
    }

    @Test
    public void aCountThatIsNotANumberIsReported() {
        h = EditorHarness.create("a\nb\n");
        on(0);
        Lines.joinLines("banana");
        h.assertText("a\nb\n");
    }

    // --------------------------------------------------------- move, copy

    @Test
    public void moveLinesDownSwapsWithTheLineBelow() {
        h = EditorHarness.create("a\nb\nc\n");
        on(0);
        Lines.moveLinesDown();
        h.assertText("b\na\nc\n");
        at(1);
    }

    @Test
    public void moveLinesUpSwapsWithTheLineAbove() {
        h = EditorHarness.create("a\nb\nc\n");
        on(2);
        Lines.moveLinesUp();
        h.assertText("a\nc\nb\n");
        at(1);
    }

    @Test
    public void movingOffEitherEndDoesNothing() {
        h = EditorHarness.create("a\nb\n");
        on(0);
        Lines.moveLinesUp();
        h.assertText("a\nb\n");
        on(1);
        Lines.moveLinesDown();
        h.assertText("a\nb\n");
    }

    @Test
    public void duplicateLinesCopiesBelow() {
        h = EditorHarness.create("a\nb\n");
        on(0);
        Lines.duplicateLines();
        h.assertText("a\na\nb\n");
        at(1);
    }

    @Test
    public void duplicateLinesTakesTheWholeSelection() {
        h = EditorHarness.create("a\nb\nc\n");
        on(0);
        final Line third = h.buffer().getFirstLine().next().next();
        h.editor().setMark(new Position(h.buffer().getFirstLine(), 0));
        h.editor().setDot(third, 0);
        h.editor().moveCaretToDotCol();
        Editor.setCurrentEditor(h.editor());
        Lines.duplicateLines();
        h.assertText("a\nb\na\nb\nc\n");
    }

    @Test
    public void moveLinesPutsARunWhereItIsAsked() {
        h = EditorHarness.create("a\nb\nc\nd\n");
        Editor.setCurrentEditor(h.editor());
        Lines.moveLines(h.editor(), 2, 3, 0);
        h.assertText("b\nc\na\nd\n");
        at(1);
    }

    @Test
    public void copyLinesLeavesTheOriginal() {
        h = EditorHarness.create("a\nb\nc\n");
        Editor.setCurrentEditor(h.editor());
        Lines.copyLines(h.editor(), 1, 1, 3);
        h.assertText("a\nb\nc\na\n");
        at(3);
    }

    // ------------------------------------------------------- case region

    @Test
    public void toggleCaseRegionSwapsTheCaseOfTheSelection() throws Exception {
        // New: j had upperCaseRegion and lowerCaseRegion and no toggle. It
        // is the same code vim's g~ runs.
        h = EditorHarness.create("aBc dEf\n");
        final Line first = h.buffer().getFirstLine();
        h.editor().setMark(new Position(first, 0));
        h.editor().setDot(first, 3);
        h.editor().moveCaretToDotCol();
        Editor.setCurrentEditor(h.editor());
        h.editor().execute("toggleCaseRegion", null);
        h.assertText("AbC dEf\n");
    }

    // ------------------------------------------------- the command table

    @Test
    public void theCommandTableReachesThem() throws Exception {
        h = EditorHarness.create("a\nb\nc\n");
        on(0);
        h.editor().execute("joinLines", null);
        h.assertText("a b\nc\n");
        h.close();

        h = EditorHarness.create("a\nb\nc\n");
        on(0);
        h.editor().execute("duplicateLines", null);
        h.assertText("a\na\nb\nc\n");
        h.close();

        h = EditorHarness.create("a\nb\nc\n");
        on(0);
        h.editor().execute("moveLinesDown", null);
        h.assertText("b\na\nc\n");
    }
}
