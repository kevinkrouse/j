/*
 * CssMatchTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.CaretCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code %} on a CSS brace must land on the real matching brace, not
 * one inside a comment or string: the CSS syntax iterator blanks those
 * characters so the walk skips them.
 */
public class CssMatchTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void open(String text) {
        h = EditorHarness.create(text).mode(CSSMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        Editor.setCurrentEditor(h.editor());
    }

    private String jump(int lineNumber, int offset) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            l = l.next();
        h.editor().setDot(l, offset);
        h.editor().moveCaretToDotCol();
        CaretCommands.findMatchingPair(h.editor());
        return h.editor().getDotLineNumber() + ":" + h.editor().getDotOffset();
    }

    @Test
    public void braceInCommentIsNotTheMatch() {
        // a { /* } */ b }   -- % on the { at 2 must land on the real
        // closing } at 14 (bar caret one past: 15), not the } in the
        // comment at 7.
        open("a { /* } */ b }");
        assertEquals("0:15", jump(0, 2));
    }

    @Test
    public void braceInStringIsNotTheMatch() {
        // a { content: "}" ; }   -- the } in the string at 14 must not
        // match; the real closer is at 18, bar caret lands at 19.
        open("a { content: \"}\"; }");
        assertEquals("0:19", jump(0, 2));
    }

    @Test
    public void plainBracesStillMatch() {
        // No comment or string: closing } at 6, bar caret at 7.
        open("a { b }");
        assertEquals("0:7", jump(0, 2));
    }
}
