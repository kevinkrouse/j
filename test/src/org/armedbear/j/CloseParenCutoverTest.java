/*
 * CloseParenCutoverTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.lisp.LispShellMode;
import org.armedbear.j.mode.perl.PerlMode;
import org.armedbear.j.mode.shell.ShellMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The close-paren cutover. The {@code )} bindings are gone from the
 * ordinary modes, so a typed {@code )} falls through to the catch-all
 * and reaches the electric-pairs handler in {@code insertNormalChar}
 * rather than the old {@code closeParen} command. The Lisp shell has
 * no binding either: its {@code electricCloseParen}, which sends a
 * complete form at once, is opt-in through {@code map-key-for-mode}.
 */
public class CloseParenCutoverTest {

    private static String commandFor(Mode mode, char c) {
        final KeyMapping mapping = mode.getKeyMap().lookup(c, 0, 0);
        return mapping == null ? null : String.valueOf(mapping.getCommand());
    }

    @Test
    public void closeParenIsUnboundInTheOrdinaryModes() {
        // No direct binding: the typed ) reaches the catch-all and pairs.
        assertNull(commandFor(JavaMode.getMode(), ')'), "Java");
        assertNull(commandFor(LispMode.getMode(), ')'), "Lisp");
        assertNull(commandFor(PerlMode.getMode(), ')'), "Perl");
        assertNull(commandFor(ShellMode.getMode(), ')'), "shell script");
    }

    @Test
    public void theLispShellLeavesCloseParenUnbound() {
        // As on master: a typed ) does not send the form; the
        // electricCloseParen binding is the user's to add.
        assertNull(commandFor(LispShellMode.getMode(), ')'));
    }

    @Test
    public void theCloseParenWrapperInsertsOneParenAndFlashes() {
        Editor.preferences().setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, "false");
        Editor.preferences().setProperty(Property.HIGHLIGHT_BRACKETS, "false");
        Editor.parenFlashMillis = 60000;
        final EditorHarness h = EditorHarness.create("f(a\n").mode(JavaMode.getMode());
        try {
            h.cursor(0, 3);
            ElectricCommands.closeParen(h.editor());
            // Exactly one ) inserted, the caret after it, the match flashed.
            h.assertText("f(a)\n");
            h.assertCursorAt(0, 4);
            assertEquals(1, h.editor().getDisplay().getMatchingBracketPosition().getOffset());
            ElectricCommands.endParenFlash(h.editor());
            assertNull(h.editor().getDisplay().getMatchingBracketPosition());
        }
        finally {
            h.close();
            Editor.preferences().removeProperty(Property.HIGHLIGHT_MATCHING_BRACKET.key());
            Editor.preferences().removeProperty(Property.HIGHLIGHT_BRACKETS.key());
            Editor.parenFlashMillis = 300;
        }
    }
}
