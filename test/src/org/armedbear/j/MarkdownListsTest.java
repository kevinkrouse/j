/*
 * MarkdownListsTest.java
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

import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tab, Shift+Tab, Enter and Backspace in Markdown lists, through their keys. */
public class MarkdownListsTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text, int line, int offset) {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        h.buffer().setProperty(Property.INDENT_SIZE, 4);
        Editor.setCurrentEditor(h.editor());
        return h.cursor(line, offset);
    }

    private String line(int n) {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < n; i++)
            line = line.next();
        return line.getText();
    }

    @Test
    public void tabShiftsAnItem() {
        on("- a\n- b\n", 1, 3);
        h.keys("<Tab>");
        assertEquals("    - b", line(1));
        // The caret kept to the text.
        assertEquals(7, h.offset());
        h.keys("<S-Tab><S-Tab>");
        assertEquals("- b", line(1));
    }

    @Test
    public void tabInTextInsertsATab() {
        on("text\n", 0, 2);
        h.keys("<Tab>");
        assertEquals("te", line(0).substring(0, 2));
        assertEquals("xt", line(0).substring(line(0).length() - 2));
        assertEquals(true, line(0).length() > 4);
    }

    @Test
    public void tabOnAParagraphShifts() {
        on("text\nmore\n", 1, 0);
        h.keys("<Tab>");
        assertEquals("    more", line(1));
    }

    @Test
    public void tabOnASelectionShiftsEveryLine() {
        on("- a\n- b\n- c\n", 0, 0);
        h.editor().setMark(new Position(h.buffer().getFirstLine().next().next(), 1));
        h.keys("<Tab>");
        assertEquals("    - a\n    - b\n    - c\n", h.text());
        h.keys("<S-Tab>");
        assertEquals("- a\n- b\n- c\n", h.text());
    }

    @Test
    public void enterContinuesTheList() {
        on("  - a\n", 0, 5);
        h.keys("<CR>");
        assertEquals("  - ", line(1));
        h.assertCursorAt(1, 4);
    }

    @Test
    public void undoTakesAnEnterBackAtOnce() {
        on("- ab\n", 0, 3);
        h.keys("<CR>");
        EditCommands.undo(h.editor());
        assertEquals("- ab\n", h.text());
    }

    @Test
    public void enterInVimInsertMode() {
        on("- a\n", 0, 0);
        h.vim().keys("A<CR>b<Esc>");
        assertEquals("- a\n- b\n", h.text());
    }

    @Test
    public void enterNumbersOn() {
        on("9. a\n", 0, 4);
        h.keys("<CR>");
        assertEquals("10. ", line(1));
    }

    @Test
    public void enterSplitsAnItem() {
        on("- ab\n", 0, 3);
        h.keys("<CR>");
        assertEquals("- a\n- b\n", h.text());
        h.assertCursorAt(1, 2);
    }

    @Test
    public void enterAfterATaskMakesATask() {
        on("- [x] done\n", 0, 10);
        h.keys("<CR>");
        assertEquals("- [ ] ", line(1));
    }

    @Test
    public void enterOnAnEmptyItemTakesItsMarkerAway() {
        on("- a\n  - [ ] \n", 1, 8);
        h.keys("<CR>");
        assertEquals("- a\n  \n", h.text());
        h.assertCursorAt(1, 2);
    }

    @Test
    public void backspaceOnAnEmptyItemStepsBack() {
        on("- a\n  - b\n  - [ ] \n", 2, 8);
        h.keys("<BS>");
        assertEquals("    ", line(2));
        h.keys("<BS>");
        assertEquals("  ", line(2));
        h.keys("<BS>");
        assertEquals("", line(2));
        h.keys("<BS>");
        assertEquals("- a\n  - b\n", h.text());
    }

    @Test
    public void backspaceInTextIsBackspace() {
        on("- ab\n", 0, 4);
        h.keys("<BS>");
        assertEquals("- a", line(0));
    }
}
