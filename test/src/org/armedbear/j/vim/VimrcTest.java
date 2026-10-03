/*
 * VimrcTest.java
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.armedbear.j.EditorHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The user's half of the configuration: a vimrc that overrides the built-in
 * key map.
 */
public class VimrcTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
        VimKeyMap.reset();
    }

    private EditorHarness vim(String text, String vimrc) {
        h = EditorHarness.create(text).vim(vimrc);
        return h;
    }

    private static VimOptions optionsFrom(String vimrc) throws Exception {
        final VimOptions options = new VimOptions();
        new VimrcParser(new VimKeyMap(), options).load(new StringReader(vimrc));
        return options;
    }

    // ----------------------------------------------------------- mappings

    @Test
    public void nnoremapBindsANormalModeKey() {
        vim("alpha bravo\n", "nnoremap Y y$\n").cursor(0, 6);
        h.keys("Y");
        h.cursor(0, 0).keys("P");
        h.assertText("bravoalpha bravo\n");
    }

    @Test
    public void aMappingOverridesTheBuiltInOne() {
        // x normally deletes a character; here it does nothing of the sort.
        vim("abc\n", "nnoremap x l\n").cursor(0, 0).keys("x");
        h.assertText("abc\n");
        assertEquals(1, h.offset());
    }

    @Test
    public void aNoremapsKeysMeanWhatTheyDoBuiltIn() {
        // < in the right-hand side is the shift, not this mapping again.
        vim("        a\n        b\nc\n", "set sw=4\nvnoremap < <gv\n")
            .cursor(0, 8)
            .keys("vj<<");
        h.assertText("a\nb\nc\n");
        assertEquals("VISUAL", h.vimModeIndicator());
        tearDown();
        // Z is the built-in x, which deletes a character, not the dd x is
        // mapped to.
        vim("abc\ndef\n", "nnoremap x dd\nnnoremap Z x\n").cursor(0, 0)
            .keys("Z");
        h.assertText("bc\ndef\n");
    }

    @Test
    public void aMapsKeysAreMappingsToo() {
        vim("abc\ndef\n", "nnoremap x dd\nnmap Z x\n").cursor(0, 0)
            .keys("Z");
        h.assertText("def\n");
    }

    @Test
    public void mapLeaderIsExpanded() {
        vim("abc\n", "let mapleader = \",\"\nnnoremap <leader>d dl\n")
            .cursor(0, 0)
            .keys(",d");
        h.assertText("bc\n");
    }

    @Test
    public void plainMapAppliesToNormalVisualAndOperatorPending() {
        vim("abcdef\n", "map L 2l\n").cursor(0, 0).keys("L");
        assertEquals(2, h.offset());
        h.cursor(0, 0).keys("dL");
        h.assertText("cdef\n");
    }

    @Test
    public void vnoremapOnlyAppliesInVisualMode() {
        vim("abcdef\n", "vnoremap q l\n").cursor(0, 0).keys("q");
        assertEquals(0, h.offset(), "nothing happens in normal mode");
        h.keys("vq");
        assertEquals(1, h.offset());
    }

    @Test
    public void unmapRemovesABinding() {
        vim("abc\n", "nunmap x\n").cursor(0, 0).keys("x");
        h.assertText("abc\n");
    }

    @Test
    public void aMappingCanRunOneOfJsOwnCommands() {
        // selectAll is an ordinary j command with no modal equivalent.
        vim("abc\ndef\n", "nnoremap <C-a> :selectAll<CR>\n").cursor(0, 0);
        h.keys("<C-a>");
        assertTrue(h.editor().getMark() != null, "j's own command ran");
    }

    // ------------------------------------------- a command that is a prefix

    @Test
    public void aLongerMappingWinsOverTheShorterCommandItStartsWith() {
        // ',' is repeat-character-search; ',d' is now a mapping. Typing ',d'
        // must get the mapping, not a search followed by a delete.
        vim("abc\n", "nnoremap ,d dl\n").cursor(0, 0).keys(",d");
        h.assertText("bc\n");
    }

    @Test
    public void theShorterCommandStillRunsWhenTheLongerOneDoesNotArrive() {
        // ',' on its own is still the search repeat: held back until the next
        // key shows it was not the start of ',d'.
        vim("a-b-c\n", "nnoremap ,d dl\n").cursor(0, 0).keys("f-");
        assertEquals(1, h.offset());
        h.keys(";");
        assertEquals(3, h.offset());
        h.keys(",");
        assertEquals(3, h.offset(), "the held-back ',' ran once a non-'d' key arrived");
        h.keys("l");
        assertEquals(2, h.offset());
    }

    @Test
    public void escapeDropsAHeldBackCommand() {
        vim("a-b-c\n", "nnoremap ,d dl\n").cursor(0, 0).keys("f-,<Esc>");
        h.assertText("a-b-c\n");
    }

    // ------------------------------------------------------------ options

    @Test
    public void setTakesValuesAndFlags() throws Exception {
        final VimOptions options =
            optionsFrom("set shiftwidth=4 ignorecase\nset noexpandtab\n");
        assertEquals(4, options.getInt("shiftwidth", 0));
        assertTrue(options.getBoolean("ignorecase", false));
        assertEquals(false, options.getBoolean("expandtab", true));
    }

    @Test
    public void setAcceptsVimsShortNames() throws Exception {
        final VimOptions options = optionsFrom("set sw=8 ic\n");
        assertEquals(8, options.getInt("shiftwidth", 0), "sw is shiftwidth");
        assertTrue(options.getBoolean("ignorecase", false), "ic is ignorecase");
    }

    @Test
    public void commentsAndBlankLinesAreIgnored() throws Exception {
        final VimOptions options =
            optionsFrom("\" a comment\n\n  \" another\nset sw=2\n");
        assertEquals(2, options.getInt("shiftwidth", 0));
    }

    @Test
    public void aLineThatIsNotUnderstoodIsSkipped() throws Exception {
        // A real vimrc has plenty j will never read; it must not stop the
        // lines that do make sense.
        final VimOptions options = optionsFrom(
            "call plug#begin()\nset sw=3\nautocmd BufRead * echo 'hi'\n"
        );
        assertEquals(3, options.getInt("shiftwidth", 0));
    }

    // -------------------------------------------------------- key map rows

    @Test
    public void theBuiltInTableStillLoadsUnderneath() {
        vim("abc\n", "nnoremap Q x\n").cursor(0, 0).keys("l");
        assertSame(1, h.offset(), "a binding the vimrc did not touch still works");
    }

    @Test
    public void aMappingCanRunAVimExCommandJHasNoCommandFor() {
        vim("abc\n", "nnoremap <C-a> :s/b/X/<CR>\n").cursor(0, 0);
        h.keys("<C-a>");
        h.assertText("aXc\n");
    }
}
