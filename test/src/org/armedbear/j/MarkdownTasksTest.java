/*
 * MarkdownTasksTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.markdown.MarkdownTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The task command, and the keys Markdown mode binds to it. */
public class MarkdownTasksTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    private void select(int fromLine, int fromOffset, int toLine, int toOffset) {
        h.cursor(fromLine, fromOffset);
        final Position mark = h.editor().getDotCopy();
        h.cursor(toLine, toOffset);
        h.editor().setMark(mark);
    }

    @Test
    public void cyclesTheCaretsTask() {
        on("- [ ] a\n");
        MarkdownTasks.task();
        h.assertText("- [/] a\n");
        MarkdownTasks.task();
        h.assertText("- [x] a\n");
        MarkdownTasks.task();
        h.assertText("- [ ] a\n");
    }

    @Test
    public void aCancelledOrCapitalXTaskStartsAgain() {
        on("- [-] a\n* [X] b\n");
        MarkdownTasks.task();
        h.assertText("- [ ] a\n* [X] b\n");
        h.cursor(1, 0);
        MarkdownTasks.task();
        h.assertText("- [ ] a\n* [ ] b\n");
    }

    @Test
    public void setsAState() {
        on("1. [ ] a\n");
        MarkdownTasks.task("done");
        h.assertText("1. [x] a\n");
        MarkdownTasks.task(" doing ");
        h.assertText("1. [/] a\n");
        MarkdownTasks.task("todo");
        h.assertText("1. [ ] a\n");
    }

    @Test
    public void cancelToggles() {
        on("- [/] a\n");
        MarkdownTasks.task("cancel");
        h.assertText("- [-] a\n");
        MarkdownTasks.task("cancel");
        h.assertText("- [ ] a\n");
    }

    @Test
    public void makesTasks() {
        on("- a\n-\n  2) b\n  text\n> quoted\n\n");
        for (int i = 0; i < 6; i++) {
            h.cursor(i, 0);
            MarkdownTasks.task();
        }
        h.assertText("- [ ] a\n- [ ]\n  2) [ ] b\n  - [ ] text\n> - [ ] quoted\n\n");
    }

    @Test
    public void aSelectionMovesOnTogetherAsItsFirstTaskSays() {
        on("intro\n- [ ] a\n\n  - [x] b\n- c\nafter\n");
        select(0, 2, 4, 1);
        MarkdownTasks.task();
        h.assertText("- [/] intro\n- [/] a\n\n  - [/] b\n- [/] c\nafter\n");
    }

    @Test
    public void wholeLinesEndAtTheStartOfTheNext() {
        on("- [ ] a\n- [ ] b\n- [ ] c\n");
        select(0, 0, 2, 0);
        MarkdownTasks.task("done");
        h.assertText("- [x] a\n- [x] b\n- [ ] c\n");
    }

    @Test
    public void cancelOverASelection() {
        on("- [-] a\n- [ ] b\n");
        select(0, 0, 1, 3);
        MarkdownTasks.task("cancel");
        h.assertText("- [-] a\n- [-] b\n");
        MarkdownTasks.task("cancel");
        h.assertText("- [ ] a\n- [ ] b\n");
    }

    @Test
    public void oneUndoUndoesEveryLine() {
        on("- a\n- b\n");
        select(0, 0, 1, 3);
        MarkdownTasks.task();
        h.assertText("- [ ] a\n- [ ] b\n");
        h.editor().undo();
        h.assertText("- a\n- b\n");
    }

    @Test
    public void theCaretStaysOnItsText() {
        on("- a\nplain\n- [ ] c\n");
        h.cursor(0, 2);
        MarkdownTasks.task();
        h.assertCursorAt(0, 6);
        h.cursor(1, 3);
        MarkdownTasks.task();
        h.assertCursorAt(1, 9);
        h.cursor(2, 3);
        MarkdownTasks.task();
        h.assertCursorAt(2, 3);
    }

    @Test
    public void nothingToDoChangesNothing() {
        on("\n- [x] a\n");
        MarkdownTasks.task();
        h.cursor(1, 0);
        MarkdownTasks.task("done");
        h.assertText("\n- [x] a\n");
        assertFalse(h.buffer().isModified());
    }

    @Test
    public void aBadStateSaysSo() {
        on("- [ ] a\n");
        MarkdownTasks.task("finished");
        h.assertText("- [ ] a\n");
        assertTrue(h.status().contains("todo, doing, done or cancel"), h.status());
    }

    @Test
    public void keysInSimpleEditing() {
        on("- [ ] a\n");
        h.keys("<C-CR>");
        h.assertText("- [/] a\n");
        h.keys("<C-S-CR>");
        h.assertText("- [-] a\n");
    }

    @Test
    public void keysInVimNormalMode() {
        on("- [ ] a\n").vim();
        h.keys("<C-CR>");
        h.assertText("- [/] a\n");
        h.keys("<C-S-CR>");
        h.assertText("- [-] a\n");
    }

    @Test
    public void keysInVimVisualMode() {
        on("- [ ] a\n- [ ] b\n- [ ] c\n").vim();
        h.keys("Vj<C-CR>");
        h.assertText("- [/] a\n- [/] b\n- [ ] c\n");
    }

    @Test
    public void vimCharacterwiseVisualTakesInTheCaretsLine() {
        on("- [ ] a\n- [ ] b\n- [ ] c\n").vim();
        h.keys("vj<C-CR>");
        h.assertText("- [/] a\n- [/] b\n- [ ] c\n");
    }

    @Test
    public void undoPutsTheCaretBack() {
        on("- a\n- b\n");
        select(0, 0, 1, 3);
        MarkdownTasks.task();
        h.editor().undo();
        h.assertCursorAt(1, 3);
    }

    @Test
    public void notInCode() {
        on("```yaml\n  - name: foo\n```\n\n    some(code);\n").cursor(1, 4);
        MarkdownTasks.task();
        MarkdownTasks.followLinkOrTask();
        h.cursor(4, 6);
        MarkdownTasks.task();
        h.assertText("```yaml\n  - name: foo\n```\n\n    some(code);\n");
    }
}
