/*
 * VimReadOnlyTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertEquals;

import org.armedbear.j.EditorHarness;
import org.junit.Test;

/**
 * A read-only buffer does not change, whatever vim command is typed at it.
 * The vim layer edits through j's primitives, and several of them do not ask.
 */
public class VimReadOnlyTest
{
    private static final String[] EDITS = {
        "dd", "x", "rz", "J", ">>", "yyp", "~", "ccx<Esc>", "Rxy<Esc>",
        "vjrz", "yyjVp", "vJ", "vU", "i<C-t><Esc>", "ox<Esc>", "ix<Esc>",
        ":s/a/b/<CR>", ":sort!<CR>", ":j<CR>", ":d<CR>", ":t0<CR>",
        ":m$<CR>", ":g/a/d<CR>", ":normal x<CR>",
    };

    @Test
    public void noVimCommandChangesAReadOnlyBuffer()
    {
        for (String keys : EDITS) {
            final EditorHarness h = EditorHarness.create().vim();
            try {
                h.value("abc\ndef").cursor(0, 0);
                h.buffer().setForceReadOnly(true);
                h.keys(keys);
                assertEquals(keys, "abc\ndef", h.value());
            }
            finally {
                h.close();
            }
        }
    }

    @Test
    public void itSaysWhy()
    {
        final EditorHarness h = EditorHarness.create().vim();
        try {
            h.value("abc").cursor(0, 0);
            h.buffer().setForceReadOnly(true);
            h.keys("x");
            assertEquals("Buffer is read only", h.status());
        }
        finally {
            h.close();
        }
    }
}
