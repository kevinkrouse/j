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
 * {@code %} on a Scheme paren must land on the real matching paren, not
 * one inside a comment or string. The match walk uses the mode's syntax
 * iterator, and the default iterator does not blank comments or strings,
 * so a {@code )} inside {@code #| ) |#} or a quoted string was counted
 * as the match. The Scheme syntax iterator blanks comment and string
 * characters so the walk skips them.
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
        //  ^0123456789...
        // % on the first ( (offset 0) must land on the real closing )
        // at the end (index 12), not the ) inside the comment at 6.
        // The bar caret lands one past a closing match, so 13.
        open("(a #| ) |# b)");
        assertEquals("0:13", jump(0, 0));
    }

    @Test
    public void parenInStringIsNotTheMatch() {
        // (display ")")
        // The ) inside the string must not match the first (; the real
        // closing ) is at index 12, bar caret lands at 13.
        open("(display \")\")");
        assertEquals("0:13", jump(0, 0));
    }

    @Test
    public void parenInLineCommentIsNotTheMatch() {
        // (a ; )
        // b)
        // % on the first ( must skip the ) in the line comment and land
        // on the ) on the next line (index 1), bar caret at 2.
        open("(a ; )\nb)");
        assertEquals("1:2", jump(0, 0));
    }

    @Test
    public void plainParensStillMatch() {
        // No comment or string: the parens pair normally. The closing
        // ) is at index 4, bar caret lands at 5.
        open("(a b)");
        assertEquals("0:5", jump(0, 0));
    }

    // Vim's % matches relative to where it starts: from inside a string
    // it still finds the bracket there, counting quotes from the caret.
    // Masking the string would hide that bracket and break the match.

    @Test
    public void vimPercentFromInsideStringMatches() {
        // "(a)" with the caret inside the string at offset 1: % finds
        // the ) at offset 3. Mirrors the core plain-mode contract.
        openVim("\"(a)\"");
        assertEquals("0:3", jumpVim(1));
    }

    @Test
    public void vimPercentFromInsideStringSkipsCrossedQuote() {
        // (") ") with the caret on the ) inside the string at offset 2:
        // crossing the closing quote leaves the only ( behind one, so %
        // stays put. Mirrors the core (" ) ") contract exactly.
        openVim("(\")\")");
        assertEquals("0:2", jumpVim(2));
    }

    @Test
    public void vimPercentStillSkipsBracketInComment() {
        // Comments are masked regardless of where vim starts: a ) in a
        // block comment is never a match. The real closing ) is at 12.
        openVim("(a #| ) |# b)");
        assertEquals("0:12", jumpVim(0));
    }
}
