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
 * {@code %} on a CSS brace must land on the real matching brace, not one
 * inside a comment. The match walk uses the mode's syntax iterator, and
 * the default iterator does not blank comments, so a {@code }} inside
 * {@code /* } *}{ was counted as the match. The CSS syntax iterator
 * blanks comment and string characters so the walk skips them.
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
        // a { /* } */ b }
        //   ^0123456789...
        // % on the first { (offset 2) must land on the real closing }
        // at the end (index 14), not the } inside the comment at 7.
        // The bar caret lands one past a closing match, so 15.
        open("a { /* } */ b }");
        assertEquals("0:15", jump(0, 2));
    }

    @Test
    public void braceInStringIsNotTheMatch() {
        // a { content: "}" ; }
        // The } inside the string (index 14) must not match the first
        // {; the real closing } is at 18, bar caret lands at 19.
        open("a { content: \"}\"; }");
        assertEquals("0:19", jump(0, 2));
    }

    @Test
    public void plainBracesStillMatch() {
        // No comment or string: the braces pair normally. The closing
        // } is at 6, bar caret lands at 7.
        open("a { b }");
        assertEquals("0:7", jump(0, 2));
    }
}
