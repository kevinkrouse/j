/*
 * SchemeElectricTest.java
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

package org.armedbear.j.mode.scheme;

import static org.armedbear.j.ElectricPairs.Action.INSERT_PAIR;
import static org.armedbear.j.ElectricPairs.Action.PLAIN;
import static org.armedbear.j.ElectricPairs.Action.TYPE_OVER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.ElectricPairs;
import org.armedbear.j.ElectricPairs.Action;
import org.armedbear.j.ElectricPairs.Decision;
import org.armedbear.j.ElectricPairs.Operation;
import org.armedbear.j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Typing a bracket inside a Scheme comment or string must not trigger
 * electric pairing: the decision is a plain insert, because the caret
 * is not in neutral context. Outside a comment or string the pair
 * inserts normally, and a closer typed before its opener steps over.
 */
public class SchemeElectricTest {
    private EditorHarness h;

    @BeforeAll
    public static void registerModes() {
        Editor.getModeList().register(new SchemeModes());
    }

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private Decision type(String text, int caret, char c) {
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(SchemeMode.getMode());
        h.cursor(0, caret);
        return ElectricPairs.decide(
                SchemeMode.getMode(),
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
    public void openerInBlockCommentIsPlain() {
        // Typing ( inside a block comment must not open a pair. The (
        // is at offset 3, inside #| ... |#.
        assertAction(PLAIN, type("#| ( |#", 3, '('));
    }

    @Test
    public void closerInBlockCommentIsPlain() {
        // Typing ) inside a block comment is a plain insert, not a
        // suppression. The caret sits at offset 2 (inside the comment,
        // not on the ) itself, so the type-over rule does not fire);
        // the lexical context is COMMENT, so the closer is not balanced.
        assertAction(PLAIN, type("#| ) |#", 2, ')'));
    }

    @Test
    public void openerInLineCommentIsPlain() {
        // Typing ( after a ; is a plain insert. The ( is at offset 2.
        assertAction(PLAIN, type("; (", 2, '('));
    }

    @Test
    public void openerInStringIsPlain() {
        // Typing ( inside a quoted string must not open a pair. The (
        // is at offset 10, inside the string.
        assertAction(PLAIN, type("(display \"(\")", 10, '('));
    }

    @Test
    public void openerInNeutralContextPairs() {
        // Outside any comment or string, with no closer ahead, the pair
        // inserts. The caret is at the end of the line.
        assertAction(INSERT_PAIR, type("(define x ", 10, '('));
    }

    @Test
    public void closerBeforeOpenerStepsOver() {
        // A ) typed just before a ) in neutral context steps over. The
        // caret sits on the final ) at offset 6.
        assertAction(TYPE_OVER, type("(a b())", 6, ')'));
    }
}
