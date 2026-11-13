/*
 * PropertiesMatchTest.java
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

package org.armedbear.j.mode.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.CaretCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code %} on a Properties bracket must land on the real matching
 * bracket, not one inside a comment. The match walk uses the mode's
 * syntax iterator, and the default iterator does not blank comments, so
 * a bracket inside a comment was counted as a match.
 */
public class PropertiesMatchTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void open(String text) {
        h = EditorHarness.create(text).mode(PropertiesMode.getMode());
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
    public void bracketInCommentIsNotTheMatch() {
        // key = (a)  # ) is in a comment
        // % on the ( at offset 6 must land on the ) at offset 8, not
        // the ) inside the comment.
        open("key = (a) # )");
        assertEquals("0:9", jump(0, 6));
    }

    @Test
    public void plainBracketsStillMatch() {
        open("key = (a)");
        assertEquals("0:9", jump(0, 6));
    }

    @Test
    public void bracketInContinuationValueMatches() {
        // A continuation is value text, not a comment, so its brackets
        // still match.
        open("key = (a \\\n  b)");
        assertEquals("1:4", jump(0, 6));
    }
}
