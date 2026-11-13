/*
 * CssElectricTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.mode.css;

import static org.armedbear.j.ElectricPairs.Action.INSERT_PAIR;
import static org.armedbear.j.ElectricPairs.Action.PLAIN;
import static org.armedbear.j.ElectricPairs.Action.TYPE_OVER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.ElectricPairs;
import org.armedbear.j.ElectricPairs.Action;
import org.armedbear.j.ElectricPairs.Decision;
import org.armedbear.j.ElectricPairs.Operation;
import org.armedbear.j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Typing a bracket inside a CSS comment or string must not trigger
 * electric pairing: the decision is a plain insert, because the caret
 * is not in neutral context. Outside a comment or string the pair
 * inserts normally, and a closer typed before its opener steps over.
 */
public class CssElectricTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private Decision type(String textWithCaret, char c) {
        int caret = textWithCaret.indexOf('|');
        String text = textWithCaret.replace("|", "");
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(CSSMode.getMode());
        h.cursor(0, caret);
        return ElectricPairs.decide(
                CSSMode.getMode(),
                h.buffer(),
                new Position(h.buffer().getLine(0), caret),
                Operation.INSERT,
                c,
                false,
                false);
    }

    private void assertAction(Action expected, Decision actual) {
        assertEquals(expected, actual.action());
    }

    @Test
    public void openerInCommentIsPlain() {
        // Typing ( inside a block comment must not open a pair.
        assertAction(PLAIN, type("a { /* | */ }", '('));
    }

    @Test
    public void closerInCommentIsPlain() {
        // Typing ) inside a comment is a plain insert, not a step-over.
        assertAction(PLAIN, type("a { /* )| */ }", ')'));
    }

    @Test
    public void openerInStringIsPlain() {
        // Typing ( inside a quoted string must not open a pair.
        assertAction(PLAIN, type("a { content: \"|\"; }", '('));
    }

    @Test
    public void openerInNeutralContextPairs() {
        // Outside any comment or string, the pair inserts.
        assertAction(INSERT_PAIR, type("a { b: |; }", '('));
    }

    @Test
    public void closerBeforeOpenerStepsOver() {
        // A ) typed just before a ) in neutral context steps over.
        assertAction(TYPE_OVER, type("a { b: ()|); }", ')'));
    }
}
