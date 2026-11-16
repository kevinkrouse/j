/*
 * SchemeMatchTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.CaretCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code %} on a Scheme paren must land on the real match, not one inside
 * a comment or string. The default syntax iterator doesn't blank those, so
 * the Scheme iterator blanks comment/string chars for the match walk.
 */
public class SchemeMatchTest {
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

    private void open(String text) {
        h = EditorHarness.create(text).mode(SchemeMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        Editor.setCurrentEditor(h.editor());
    }

    private void openVim(String text) {
        h = EditorHarness.create(text).mode(SchemeMode.getMode()).vim();
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

    private String jumpVim(int offset) {
        h.cursor(0, offset);
        h.keys("%");
        return h.editor().getDotLineNumber() + ":" + h.editor().getDotOffset();
    }

    @Test
    public void parenInBlockCommentIsNotTheMatch() {
        // (a #| ) |# b)
        // % on the ( at 0 lands on the real ) at 12, not the commented
        // one at 6; bar caret sits one past a closing match, so 13.
        open("(a #| ) |# b)");
        assertEquals("0:13", jump(0, 0));
    }

    @Test
    public void parenInStringIsNotTheMatch() {
        // (display ")")
        // The ) inside the string must not match; real closer at 12,
        // bar caret at 13.
        open("(display \")\")");
        assertEquals("0:13", jump(0, 0));
    }

    @Test
    public void parenInLineCommentIsNotTheMatch() {
        // (a ; )
        // b)
        // % skips the ) in the line comment, lands on the next line's )
        // at 1; bar caret at 2.
        open("(a ; )\nb)");
        assertEquals("1:2", jump(0, 0));
    }

    @Test
    public void plainParensStillMatch() {
        // No comment or string: closer at 4, bar caret at 5.
        open("(a b)");
        assertEquals("0:5", jump(0, 0));
    }

    // Vim's % matches relative to the caret, counting quotes from there;
    // masking the string would hide the bracket and break the match.

    @Test
    public void vimPercentFromInsideStringMatches() {
        // "(a)" caret inside the string at 1: % finds the ) at 3.
        openVim("\"(a)\"");
        assertEquals("0:3", jumpVim(1));
    }

    @Test
    public void vimPercentFromInsideStringSkipsCrossedQuote() {
        // (") ") caret on the ) inside the string at 2: crossing the
        // closing quote leaves the ( behind, so % stays put.
        openVim("(\")\")");
        assertEquals("0:2", jumpVim(2));
    }

    @Test
    public void vimPercentStillSkipsBracketInComment() {
        // Comments are masked regardless of start: real closer at 12.
        openVim("(a #| ) |# b)");
        assertEquals("0:12", jumpVim(0));
    }
}
