/*
 * VimM25Test.java
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

import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.KillRing;
import org.armedbear.j.Registers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * M25: vim's registers are j's -- "a to "z its register files, the unnamed
 * register and "1 to "9 its kill ring, "+ and "* the clipboard and the
 * primary selection.
 */
public class VimM25Test {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text) {
        h = EditorHarness.create().vim();
        h.value(text).cursor(0, 0);
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    // ------------------------------------------------------------ named

    @Test
    public void aNamedRegisterIsARegisterFileOfJs() {
        vim("one\ntwo").keys("\"ayy");
        assertEquals("one\n", Registers.getText("a"));
        h.keys("j\"Ayy");
        assertEquals("one\ntwo\n", Registers.getText("a"));
    }

    @Test
    public void aRegisterFileOfJsIsANamedRegister() {
        vim("x");
        Registers.setText("b", "line\n");
        h.keys("\"bp");
        assertEquals("x\nline", h.value());
        tearDown();
        vim("x");
        Registers.setText("c", "word");
        h.keys("\"cp");
        assertEquals("xword", h.value());
    }

    @Test
    public void aTextVimTookIsRememberedAsItWasTaken() {
        // yy on a last line takes no newline, and is still lines.
        vim("ab\ncd").keys("j\"dyyk\"dp");
        assertEquals("ab\ncd\ncd", h.value());
    }

    @Test
    public void afterARestartATrailingNewlineMeansLines() {
        vim("ab\ncd").keys("j\"dyyk");
        VimRegisters.getInstance().clear();
        h.keys("\"dp");
        assertEquals("acdb\ncd", h.value());
    }

    // ------------------------------------------------ unnamed, numbered

    @Test
    public void theUnnamedRegisterIsTheKillRing() {
        vim("one\ntwo").keys("yy");
        assertEquals("one\n", Editor.getKillRing().peek());
        // As j's copy does.
        Editor.getKillRing().appendNew("j copy");
        Editor.getKillRing().copyLastKillToSystemClipboard();
        h.keys("p");
        assertEquals("oj copyne\ntwo", h.value());
    }

    @Test
    public void jsPasteTakesAYankOfVims() {
        vim("one\ntwo\nthree").keys("jyyk");
        h.editor().paste();
        assertEquals("two\none\ntwo\nthree", h.value());
    }

    @Test
    public void theNumberedRegistersAreTheKillRingNewestFirst() {
        vim("one\ntwo\nthree").keys("ddyy");
        assertEquals("two\n", VimRegisters.getInstance().get('1').text);
        assertEquals("one\n", VimRegisters.getInstance().get('2').text);
    }

    // ------------------------------------------------ clipboard, selection

    @Test
    public void aYankIsOnTheSystemClipboardAsAJCopyIs() {
        vim("one\ntwo").keys("yy");
        assertEquals("one\n", KillRing.getText(KillRing.systemClipboard()));
    }

    @Test
    public void anotherProgramsCopyIsWhatPPuts() {
        vim("x");
        KillRing.setText(KillRing.systemClipboard(), "ext", null);
        h.keys("p");
        assertEquals("xext", h.value());
    }

    @Test
    public void plusIsTheClipboard() {
        vim("one\ntwo").keys("\"+yy");
        assertEquals("one\n", KillRing.getText(KillRing.systemClipboard()));
        KillRing.setText(KillRing.systemClipboard(), "clip", null);
        h.keys("\"+P");
        assertEquals("clipone\ntwo", h.value());
    }

    @Test
    public void starIsThePrimarySelection() {
        vim("one").keys("\"*yiw");
        assertEquals("one", KillRing.getText(KillRing.systemSelection()));
        KillRing.setText(KillRing.systemSelection(), "sel", null);
        h.keys("$\"*p");
        assertEquals("onesel", h.value());
    }
}
