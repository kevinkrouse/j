/*
 * HintTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The dimmed line of a mode's keys: the ones of the edit mode in use. */
public class HintTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        h.editor().setBufferDirectly(h.buffer());
        h.close();
    }

    private String hint(boolean vim) {
        final DirectoryBuffer dir = new DirectoryBuffer(File.getInstance(System.getProperty("java.io.tmpdir")));
        if (vim)
            dir.setProperty(Property.EDIT_MODE, "vim");
        h.editor().setBufferDirectly(dir);
        return h.editor().hintText();
    }

    @Test
    public void aModeWithoutHintsHasNone() {
        h = EditorHarness.create("text");
        assertNull(h.editor().hintText());
    }

    @Test
    public void aDirectoryListsItsOwnKeys() {
        h = EditorHarness.create();
        final String hint = hint(false);
        assertTrue(hint.startsWith("Enter open · Backspace up · "), hint);
        assertTrue(hint.contains(" · c copy · "), hint);
        assertTrue(hint.contains(" · Ctrl S sort (name) · "), hint);
        assertTrue(hint.contains(" · Ctrl R rescan"), hint);
    }

    @Test
    public void inVimTheKeysAreTheOnesThatWinOverVim() {
        h = EditorHarness.create().vim();
        final String hint = hint(true);
        assertTrue(hint.startsWith("<CR> open · - up · "), hint);
        assertTrue(hint.contains(" · C copy · "), hint);
        assertTrue(hint.contains(" · D delete · "), hint);
        assertTrue(hint.contains(" · s sort (name) · "), hint);
        assertTrue(hint.contains(" · <C-r> rescan"), hint);
    }
}
