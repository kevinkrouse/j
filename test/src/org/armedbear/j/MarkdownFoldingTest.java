/*
 * MarkdownFoldingTest.java
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
import static org.junit.Assert.assertTrue;

import org.armedbear.j.mode.markdown.MarkdownFolding;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.python.PythonMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.After;
import org.junit.Test;

/** What folding hides in Markdown, and the commands and keys that fold. */
public class MarkdownFoldingTest {
    private EditorHarness h;

    @After
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        Editor.setCurrentEditor(h.editor());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    // The lines to be seen, "|" between, "+" after one with a fold below.
    private String visible() {
        final StringBuilder sb = new StringBuilder();
        for (Line line = h.buffer().getFirstLine(); line != null; line = line.next()) {
            if (line.isHidden())
                continue;
            if (sb.length() > 0)
                sb.append('|');
            sb.append(line.getText());
            if (line.next() != null && line.next().isHidden())
                sb.append('+');
        }
        return sb.toString();
    }

    private static final String SECTIONS =
        "# A\ntext\n\n## B\nb\n\n# C\nc";

    @Test
    public void aHeadingFoldsItsSection() {
        on(SECTIONS).cursor(0, 0);
        h.editor().fold();
        // The blank line before the next heading stays, to keep them apart.
        assertEquals("# A+||# C|c", visible());
    }

    @Test
    public void foldingAgainClosesOutward() {
        on(SECTIONS).cursor(4, 0);
        h.editor().fold();
        assertEquals("# A|text||## B+||# C|c", visible());
        h.assertCursorAt(3, 0);
        h.editor().fold();
        assertEquals("# A+||# C|c", visible());
        // Opening the outer fold leaves the inner one closed.
        h.cursor(0, 0);
        h.editor().unfold();
        assertEquals("# A|text||## B+||# C|c", visible());
    }

    @Test
    public void aListItemFoldsItsChildren() {
        on("- a\n  - b\n\n    more\n  - c\n- d").cursor(0, 0);
        h.editor().fold();
        assertEquals("- a+|- d", visible());
        h.editor().unfoldAll();
        // An item without children folds the one it is under.
        h.cursor(4, 0);
        h.editor().fold();
        assertEquals("- a+|- d", visible());
    }

    @Test
    public void aFenceFoldsItsCode() {
        on("# A\n```\nx\n# not a heading\n```\nafter").cursor(2, 0);
        h.editor().fold();
        assertEquals("# A|```+|after", visible());
    }

    @Test
    public void aSetextHeadingKeepsItsUnderline() {
        on("A\n===\nbody\n\nB\n===\nmore").cursor(0, 0);
        h.editor().fold();
        assertEquals("A|===+||B|===|more", visible());
    }

    @Test
    public void toggleFoldOpensWhatItClosed() {
        on(SECTIONS).cursor(0, 0);
        h.editor().toggleFold();
        assertEquals("# A+||# C|c", visible());
        h.editor().toggleFold();
        assertEquals("# A|text||## B|b||# C|c", visible());
    }

    @Test
    public void undoOpensAFold() {
        on(SECTIONS).cursor(0, 0);
        h.editor().fold();
        h.editor().undo();
        assertEquals("# A|text||## B|b||# C|c", visible());
    }

    @Test
    public void foldHeadingsLeavesAnOutline() {
        on("intro\n# A\na\n## B\nb\n### C\nc\nC2\n---\n# D").cursor(1, 0);
        MarkdownFolding.foldHeadings("2");
        assertEquals("intro|# A+|## B+|C2|---|# D", visible());
        MarkdownFolding.foldHeadings();
        assertEquals("intro|# A+|## B+|### C+|C2|---|# D", visible());
    }

    @Test
    public void foldHeadingsWantsALevel() {
        on(SECTIONS);
        MarkdownFolding.foldHeadings("seven");
        assertTrue(h.status(), h.status().contains("1 to 6"));
        assertEquals("# A|text||## B|b||# C|c", visible());
    }

    @Test
    public void vimFoldKeys() {
        on(SECTIONS).vim().cursor(0, 0);
        h.keys("zc");
        assertEquals("# A+||# C|c", visible());
        h.keys("zo");
        assertEquals("# A|text||## B|b||# C|c", visible());
        h.keys("za");
        assertEquals("# A+||# C|c", visible());
        h.keys("zR");
        assertEquals("# A|text||## B|b||# C|c", visible());
        h.keys("zM");
        assertEquals("# A+|## B+|# C+", visible());
    }

    @Test
    public void zcAfterZMFoldsTheSectionAHeadingIsIn() {
        // After zM a section's first line is hidden but its subheadings are
        // not, so it is not folded yet.
        on("# A\na\n## B\nb").vim().keys("zM");
        assertEquals("# A+|## B+", visible());
        h.cursor(2, 0);
        h.keys("zc");
        assertEquals("# A+", visible());
        h.cursor(0, 0);
        h.keys("zo");
        assertEquals("# A+|## B+", visible());
    }

    @Test
    public void foldHeadingsIsOnlyForMarkdown() {
        h = EditorHarness.create("# a comment\ncode()\n").mode(PythonMode.getMode());
        Editor.setCurrentEditor(h.editor());
        MarkdownFolding.foldHeadings();
        assertTrue(h.status(), h.status().contains("only in Markdown"));
        assertEquals("# a comment|code()", visible());
    }

    @Test
    public void aBlankLineIsAsFarInAsTheLineAfterIt() {
        on("- a\n  - b\n\n    more\n  - c\n- d").cursor(2, 0);
        h.editor().fold();
        assertEquals("- a|  - b+|  - c|- d", visible());
    }

    @Test
    public void anIndentedCodeBlockFoldsButItsFirstLine() {
        on("# H\npara\n\n    code1\n\n    code2\nafter").cursor(5, 0);
        h.editor().fold();
        assertEquals("# H|para||    code1+|after", visible());
    }

    @Test
    public void nothingToFoldSaysSo() {
        on("just text\nmore").cursor(0, 0);
        h.editor().fold();
        assertEquals("just text|more", visible());
        assertTrue(h.status(), h.status().contains("Nothing to fold"));
    }

    @Test
    public void zoOpensOnlyTheFoldBelowTheCaret() {
        on(SECTIONS).vim().cursor(6, 0);
        h.keys("zc");
        assertEquals("# A|text||## B|b||# C+", visible());
        h.cursor(0, 0);
        h.keys("zo");
        assertEquals("# A|text||## B|b||# C+", visible());
        assertTrue(h.status(), h.status().contains("No fold here"));
    }

    @Test
    public void foldAllWhereThereIsNothingToFoldSaysSo() {
        h = EditorHarness.create("one\n  two\n").mode(PlainTextMode.getMode());
        Editor.setCurrentEditor(h.editor());
        h.editor().foldAll();
        assertEquals("one|  two", visible());
        assertTrue(h.status(), h.status().contains("Nothing to fold"));
    }
}
