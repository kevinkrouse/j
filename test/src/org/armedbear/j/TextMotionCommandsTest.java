/*
 * TextMotionCommandsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/**
 * The paragraph, section, sentence and unmatched-bracket commands, which
 * vim's { } ]] [[ ][ [] ( ) [( ]) are, run as j commands.
 */
public class TextMotionCommandsTest {
    private EditorHarness h;

    @After
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void on(String text, int line, int offset) {
        h = EditorHarness.create(text);
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < line && l != null; i++)
            l = l.next();
        h.editor().setDot(l, offset);
        h.editor().moveCaretToDotCol();
        Editor.setCurrentEditor(h.editor());
    }

    private String at() {
        return h.editor().getDotLineNumber() + "," + h.editor().getDotOffset();
    }

    @Test
    public void paragraphs() {
        on("a\nb\n\nc\n", 0, 0);
        Paragraphs.forwardParagraph();
        assertEquals("2,0", at());
        Paragraphs.backwardParagraph();
        assertEquals("0,0", at());
    }

    @Test
    public void sections() {
        on("a\n{\nb\n}\nc\n", 0, 0);
        Paragraphs.forwardSection();
        assertEquals("1,0", at());
        Paragraphs.forwardSection("}");
        assertEquals("3,0", at());
        Paragraphs.backwardSection();
        assertEquals("1,0", at());
    }

    @Test
    public void sentences() {
        on("One two.  Three four.\n", 0, 0);
        Sentences.forwardSentence();
        assertEquals("0,10", at());
        Sentences.backwardSentence();
        assertEquals("0,0", at());
    }

    @Test
    public void unmatchedBrackets() {
        on("f(a, [b], c)\n", 0, 3);
        CaretCommands.findUnmatchedBracket("(");
        assertEquals("0,1", at());
        on("f(a, [b], c)\n", 0, 3);
        CaretCommands.findUnmatchedBracket(")");
        assertEquals("0,11", at());
    }

    @Test
    public void anUnmatchedBracketNeedsABracket() {
        on("f(a)\n", 0, 2);
        CaretCommands.findUnmatchedBracket("x");
        assertEquals("0,2", at());
        assertEquals("a bracket is required", h.editor().getLastStatus());
    }
}
