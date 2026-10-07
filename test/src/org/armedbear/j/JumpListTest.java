/*
 * JumpListTest.java
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
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * j's jump list: what records into it, jumpBack and jumpForward, and
 * pushPosition and popPosition on top of them.
 */
public class JumpListTest {
    private EditorHarness h;
    private EditorHarness other;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
        if (other != null)
            other.close();
    }

    private Editor on(String text, int line) {
        h = EditorHarness.create(text);
        Editor.setCurrentEditor(h.editor());
        h.cursor(line, 0);
        return h.editor();
    }

    private String at() {
        return h.editor().getDotLineNumber() + "," + h.editor().getDotOffset();
    }

    @Test
    public void backAndForward() {
        final Editor editor = on("a\nb\nc\nd\n", 1);
        MotionCommands.eob(editor);
        MotionCommands.bob(editor);
        // eob is the end of the last line of text.
        JumpList.jumpBack();
        assertEquals("3,1", at());
        JumpList.jumpBack();
        assertEquals("1,0", at());
        JumpList.jumpForward();
        assertEquals("3,1", at());
        JumpList.jumpForward();
        assertEquals("0,0", at());
    }

    @Test
    public void nothingToGoBackTo() {
        on("a\nb\n", 1);
        JumpList.jumpBack();
        assertEquals("1,0", at());
        assertEquals("No earlier position", h.status());
    }

    @Test
    public void jumpsOfJsOwnRecord() {
        final Editor editor = on("x\na\nb\nx\n", 1);
        editor.jumpToLine(2, 0);
        JumpList.jumpBack();
        assertEquals("1,0", at());
        editor.setLastSearch(new Search("x", false, false));
        SearchCommands.findNext(editor);
        assertEquals("3,0", at());
        JumpList.jumpBack();
        assertEquals("1,0", at());
    }

    @Test
    public void pushPositionAndPopPosition() {
        final Editor editor = on("a\nb\nc\n", 1);
        editor.pushPosition();
        h.cursor(2, 0);
        editor.popPosition();
        assertEquals("1,0", at());
    }

    @Test
    public void oneEntryALine() {
        final Editor editor = on("a\nb\nc\n", 1);
        MotionCommands.eob(editor);
        h.cursor(1, 0);
        MotionCommands.eob(editor);
        JumpList.jumpBack();
        assertEquals("1,0", at());
        // The second jump from line 1 replaced the first.
        JumpList.jumpBack();
        assertEquals("No earlier position", h.status());
    }

    @Test
    public void backIntoAnotherBuffer() {
        // First: a new harness clears the jump list.
        other = EditorHarness.create("x\ny\n");
        MotionCommands.eob(on("a\nb\nc\n", 1));
        // Where going back from the other buffer goes. The switch itself
        // is Marker.gotoMarker, which needs a frame: see the screenshots.
        final Marker to = JumpList.travel(other.buffer(), other.editor().getDot(), -1);
        assertSame(h.buffer(), to.getBuffer());
        assertEquals(1, to.getPosition().lineNumber());
    }

    @Test
    public void goToFindsItsEntryWhenRecordingTheCaretDropsOne() {
        final Editor editor = on("a\nb\nc\nd\ne\n", 0);
        JumpList.clear();
        for (int line : new int[] { 0, 2, 4 }) {
            h.cursor(line, 0);
            editor.recordJump();
        }
        // The caret on entry 1's line: leaving the end records it, dropping that entry.
        h.cursor(2, 0);
        JumpList.goTo(editor, 0);
        assertEquals("0,0", at());
        h.cursor(2, 0);
        JumpList.clear();
        for (int line : new int[] { 0, 2, 4 }) {
            h.cursor(line, 0);
            editor.recordJump();
        }
        h.cursor(4, 0);
        JumpList.goTo(editor, 2);
        assertEquals("4,0", at());
    }
}
