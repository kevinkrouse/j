/*
 * SortTest.java
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
 * j's own sortLines command, which vim's :sort now shares.
 *
 * The no-argument form is the command as it has always been: a whole-line
 * comparison over the selection. The rest is what :sort added to it.
 */
public class SortTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /**
     * Selects from one line to the start of another.
     *
     * sortLines sorts up to but not including the line the selection ends
     * on, so every buffer here carries a sentinel last line that stays put.
     * That is the command's existing contract, not something this refactor
     * introduced.
     */
    private void select(int fromLine, int boundaryLine) {
        Line a = h.buffer().getFirstLine();
        for (int i = 0; i < fromLine && a != null; i++)
            a = a.next();
        Line b = h.buffer().getFirstLine();
        for (int i = 0; i < boundaryLine && b != null; i++)
            b = b.next();
        h.editor().setMark(new Position(a, 0));
        h.editor().setDot(b, 0);
        h.editor().moveCaretToDotCol();
    }

    private void sort(String parameters) {
        Editor.setCurrentEditor(h.editor());
        Sort.sortLines(parameters);
    }

    @Test
    public void sortLinesOrdersTheSelectedLines() {
        h = EditorHarness.create("b\nd\nc\na\nz\n");
        select(0, 4);
        sort("");
        h.assertText("a\nb\nc\nd\nz\n");
    }

    @Test
    public void sortLinesDoesNothingWithoutASelection() {
        h = EditorHarness.create("b\na\n");
        Editor.setCurrentEditor(h.editor());
        Sort.sortLines();
        h.assertText("b\na\n");
    }

    @Test
    public void sortLinesTakesTheSameFlagsAsTheExCommand() {
        h = EditorHarness.create("b\nZ\nd\nc\na\nz\n");
        select(0, 5);
        sort("i");
        h.assertText("a\nb\nc\nd\nZ\nz\n");
    }

    @Test
    public void andTheNumericOnes() {
        h = EditorHarness.create("6\nd3\n s5\n.9\nz\n");
        select(0, 4);
        sort("n");
        h.assertText("d3\n s5\n6\n.9\nz\n");
    }

    @Test
    public void aFlagThatIsNotVimsIsReportedRatherThanIgnored() {
        h = EditorHarness.create("b\na\nz\n");
        select(0, 2);
        sort("d");
        h.assertText("b\na\nz\n");
    }

    @Test
    public void theCommandTableReachesBothForms() throws Exception {
        // Which is what makes "sortLines n" work from a key map, from a
        // vimrc mapping and from executeCommand, not only from :sort.
        h = EditorHarness.create("6\nd3\n s5\n.9\nz\n");
        select(0, 4);
        Editor.setCurrentEditor(h.editor());
        h.editor().execute("sortLines", "n");
        h.assertText("d3\n s5\n6\n.9\nz\n");
        h.close();

        h = EditorHarness.create("b\nd\nc\na\nz\n");
        select(0, 4);
        Editor.setCurrentEditor(h.editor());
        h.editor().execute("sortLines", null);
        h.assertText("a\nb\nc\nd\nz\n");
    }

    @Test
    public void uniqueRemovesTheLinesItDropped() {
        h = EditorHarness.create("b\na\na\nc\na\nz\n");
        select(0, 5);
        sort("u");
        h.assertText("a\nb\nc\nz\n");
    }
}
