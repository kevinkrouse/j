/*
 * KeyNotationTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;

import org.armedbear.j.Constants;
import org.junit.Test;

public class KeyNotationTest
{
    private static KeyNotation.Stroke one(String keys)
    {
        return KeyNotation.parseOne(keys);
    }

    @Test
    public void plainCharactersStandForThemselves()
    {
        final List<KeyNotation.Stroke> strokes = KeyNotation.parse("3dw");
        assertEquals(3, strokes.size());
        assertEquals('3', strokes.get(0).keyChar);
        assertEquals('d', strokes.get(1).keyChar);
        assertEquals('w', strokes.get(2).keyChar);
        for (KeyNotation.Stroke s : strokes)
            assertEquals("a plain character has no key code", 0, s.keyCode);
    }

    @Test
    public void namedKeys()
    {
        assertEquals(KeyEvent.VK_ESCAPE, one("<Esc>").keyCode);
        assertEquals(KeyEvent.VK_ENTER, one("<CR>").keyCode);
        assertEquals(KeyEvent.VK_ENTER, one("<Enter>").keyCode);
        assertEquals(KeyEvent.VK_BACK_SPACE, one("<BS>").keyCode);
        assertEquals(KeyEvent.VK_TAB, one("<Tab>").keyCode);
        assertEquals(KeyEvent.VK_LEFT, one("<Left>").keyCode);
        assertEquals(KeyEvent.VK_PAGE_DOWN, one("<PageDown>").keyCode);
        assertEquals(KeyEvent.VK_F7, one("<F7>").keyCode);
    }

    @Test
    public void namedKeysAreCaseInsensitive()
    {
        assertEquals(KeyEvent.VK_ESCAPE, one("<esc>").keyCode);
        assertEquals(KeyEvent.VK_ESCAPE, one("<ESC>").keyCode);
    }

    @Test
    public void modifiers()
    {
        final KeyNotation.Stroke ctrlW = one("<C-w>");
        assertEquals(Constants.CTRL_MASK, ctrlW.modifiers);
        assertEquals('w', ctrlW.keyChar);

        assertEquals(Constants.ALT_MASK, one("<A-x>").modifiers);
        assertEquals("M- is another spelling of A-",
                     Constants.ALT_MASK, one("<M-x>").modifiers);
        assertEquals(Constants.META_MASK, one("<D-x>").modifiers);

        final KeyNotation.Stroke ctrlShiftRight = one("<C-S-Right>");
        assertEquals(Constants.CTRL_MASK | Constants.SHIFT_MASK,
                     ctrlShiftRight.modifiers);
        assertEquals(KeyEvent.VK_RIGHT, ctrlShiftRight.keyCode);
    }

    @Test
    public void shiftOnALetterBecomesTheCapital()
    {
        // <S-a> is how you would write A; the key map matches on the
        // character, so the shift is folded in rather than carried along.
        final KeyNotation.Stroke s = one("<S-a>");
        assertEquals('A', s.keyChar);
        assertEquals(0, s.modifiers);
    }

    @Test
    public void escapedPunctuation()
    {
        assertEquals('<', one("<lt>").keyChar);
        assertEquals('|', one("<Bar>").keyChar);
        assertEquals('\\', one("<Bslash>").keyChar);
    }

    @Test
    public void aSequenceMixesBothForms()
    {
        final List<KeyNotation.Stroke> strokes = KeyNotation.parse("cwhello<Esc>");
        assertEquals(8, strokes.size());
        assertEquals('c', strokes.get(0).keyChar);
        assertEquals('o', strokes.get(6).keyChar);
        assertEquals(KeyEvent.VK_ESCAPE, strokes.get(7).keyCode);
    }

    @Test
    public void onlyCharacterProducingStrokesGetAKeyTyped()
    {
        assertTrue(one("d").producesChar());
        assertTrue(one("<CR>").producesChar());
        assertTrue(one("<Tab>").producesChar());
        assertFalse("an arrow key produces no character",
                    one("<Left>").producesChar());
        assertFalse("a control chord is handled at key-pressed",
                    one("<C-w>").producesChar());
    }

    @Test
    public void namingIsTheInverseOfParsing()
    {
        final String[] keys = {
            "d", "$", "3", "<Esc>", "<CR>", "<BS>", "<Tab>", "<Space>",
            "<Left>", "<Right>", "<Up>", "<Down>", "<Home>", "<End>",
            "<PageUp>", "<PageDown>", "<Del>", "<Ins>", "<F7>",
            "<C-w>", "<A-x>", "<C-S-Right>", "<lt>",
        };
        for (String key : keys) {
            final KeyNotation.Stroke s = one(key);
            assertEquals(key, KeyNotation.name(s.keyCode, s.keyChar, s.modifiers));
        }
    }

    @Test
    public void aControlKeyIsNamedFromItsKeyCode()
    {
        // AWT does not hand a control key press the letter: Ctrl-R arrives as
        // keyCode VK_R with keyChar 0x12. Naming it after the character spelt
        // it as something no key map matched, so <C-r> fell through to j's
        // own binding and opened the replace dialog instead of redoing.
        assertEquals("<C-r>", KeyNotation.name(KeyEvent.VK_R, '\u0012',
                                               Constants.CTRL_MASK));
        assertEquals("<C-w>", KeyNotation.name(KeyEvent.VK_W, '\u0017',
                                               Constants.CTRL_MASK));
        // Held with Shift the character is the same; the key code still says
        // which key it was.
        assertEquals("<C-S-r>",
                     KeyNotation.name(KeyEvent.VK_R, '\u0012',
                                      Constants.CTRL_MASK | Constants.SHIFT_MASK));
        // Some layouts report no character at all for a control key.
        assertEquals("<C-r>", KeyNotation.name(KeyEvent.VK_R,
                                               KeyEvent.CHAR_UNDEFINED,
                                               Constants.CTRL_MASK));
        // And with no key code, the control character alone says enough.
        assertEquals("<C-r>", KeyNotation.name(0, '\u0012', Constants.CTRL_MASK));
    }

    @Test
    public void ctrlShiftSixIsCtrlCaret()
    {
        // What AWT really sends, from a key probe under Xvfb: for Ctrl-Shift-6
        // keyCode VK_6, the character '^' -- not a control character, unlike
        // Ctrl with a letter -- and Shift still held. It was named <C-S-^>
        // and matched nothing; found on screen.
        assertEquals("<C-^>", KeyNotation.name(KeyEvent.VK_6, '^',
            Constants.CTRL_MASK | Constants.SHIFT_MASK));
        // Ctrl-6 arrives as '6', and is its own name; the key map binds both.
        assertEquals("<C-6>", KeyNotation.name(KeyEvent.VK_6, '6',
                                               Constants.CTRL_MASK));
        assertEquals("a letter keeps its Shift", "<C-S-r>",
                     KeyNotation.name(KeyEvent.VK_R, '\u0012',
                         Constants.CTRL_MASK | Constants.SHIFT_MASK));
    }

    @Test
    public void theControlCharactersThatAreNotLettersAreNamedToo()
    {
        assertEquals("<C-[>", KeyNotation.name(0, '\u001b', Constants.CTRL_MASK));
        assertEquals("<C-]>", KeyNotation.name(0, '\u001d', Constants.CTRL_MASK));
        assertEquals("<C-\\>", KeyNotation.name(0, '\u001c', Constants.CTRL_MASK));
    }

    @Test
    public void awtModifiersRoundTrip()
    {
        assertEquals(java.awt.event.InputEvent.CTRL_DOWN_MASK,
                     KeyNotation.awtModifiers(Constants.CTRL_MASK));
        assertEquals(java.awt.event.InputEvent.SHIFT_DOWN_MASK
                     | java.awt.event.InputEvent.ALT_DOWN_MASK,
                     KeyNotation.awtModifiers(Constants.SHIFT_MASK
                                              | Constants.ALT_MASK));
    }

    @Test
    public void aTypoIsReportedRatherThanTakenLiterally()
    {
        try {
            KeyNotation.parse("<Excape>");
            fail("expected an exception for an unknown key name");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Excape"));
        }
    }

    @Test
    public void aBareAngleBracketIsTheKeyItself()
    {
        // << is the shift-left operator typed twice, not a malformed name.
        final List<KeyNotation.Stroke> strokes = KeyNotation.parse("<lt><lt>");
        assertEquals(2, strokes.size());
        assertEquals('<', strokes.get(0).keyChar);

        assertEquals("a bare < is read as the key",
                     Arrays.asList("<lt>", "<lt>"), KeyNotation.tokenize("<<"));
        assertEquals("and normalised so a binding and a keystroke agree",
                     Arrays.asList("<lt>"), KeyNotation.tokenize("<"));
        assertEquals(Arrays.asList("d", "<lt>"), KeyNotation.tokenize("d<"));
    }

    @Test
    public void namingAndTokenizingAgreeOnTheAngleBracket()
    {
        // The dispatcher spells a typed '<' with name(); a key map spells it
        // with tokenize(). If those differ, the binding never matches.
        assertEquals(KeyNotation.tokenize("<").get(0),
                     KeyNotation.name(0, '<', 0));
    }
}
