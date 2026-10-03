/*
 * VimM23Test.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.JumpList;
import org.armedbear.j.Marker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * M23: CTRL-O and CTRL-I on j's jump list, and vim's file marks, A to Z,
 * as j's bookmarks.
 */
public class VimM23Test {
    private EditorHarness h;
    private EditorHarness other;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
        if (other != null)
            other.close();
    }

    private EditorHarness vim(String text, int line) {
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, 0);
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    private String caret() {
        return h.lineNumber() + "," + h.offset();
    }

    // ------------------------------------------------------------- jumps

    @Test
    public void ctrlOGoesBackOverJsOwnJumps() {
        vim("a\nb\nc\nd", 1);
        h.editor().jumpToLine(3, 0);
        h.keys("<C-o>");
        assertEquals("1,0", caret());
        h.keys("<C-i>");
        assertEquals("3,0", caret());
    }

    @Test
    public void jumpBackGoesBackOverVimsJumps() {
        vim("a\nb\nc\nd", 1).keys("G");
        JumpList.jumpBack();
        assertEquals("1,0", caret());
    }

    // -------------------------------------------------------- file marks

    @Test
    public void aFileMarkIsABookmark() {
        vim("a\nb\nc", 1).keys("mA");
        final Marker bookmark = Editor.getBookmark('A');
        assertNotNull(bookmark);
        assertSame(h.buffer(), bookmark.getBuffer());
        assertEquals(1, bookmark.getPosition().lineNumber());
    }

    @Test
    public void aBookmarkIsAFileMark() {
        vim("a\nb\nc", 2);
        h.editor().dropBookmark("B");
        h.keys("gg`B");
        assertEquals("2,0", caret());
        h.keys("gg");
        h.editor().gotoBookmark("B");
        assertEquals("2,0", caret());
    }

    @Test
    public void delmarksForgetsTheBookmark() {
        vim("a\nb", 1).keys("mA").keys(":delmarks A<CR>");
        assertNull(Editor.getBookmark('A'));
    }

    @Test
    public void aFileMarkMovesWithEdits() {
        vim("a\nb\nc", 2).keys("mAggdd");
        h.keys("`A");
        assertEquals("1,0", caret());
    }

    @Test
    public void anOperatorDoesNotReachAMarkInAnotherFile() {
        other = EditorHarness.create("x\ny\n");
        vim("a\nb\nc", 0);
        Editor.setBookmark(
            'A',
            new Marker(
                other.buffer(),
                other.editor().getDot()
            )
        );
        h.keys("d`A");
        assertEquals("a\nb\nc", h.value());
        assertSame(h.buffer(), h.editor().getBuffer());
    }
}
