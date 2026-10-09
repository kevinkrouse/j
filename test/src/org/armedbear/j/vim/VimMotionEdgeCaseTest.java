/*
 * VimMotionEdgeCaseTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.Mode;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Ge over line ends, the {@code :help d} rule, % past brackets in
 * strings, and an arrow splitting an insert. Every expectation is nvim's.
 */
public class VimMotionEdgeCaseTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text, int line, int offset) {
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset);
        return h;
    }

    // ---------------------------------------- ge and gE

    @Test
    public void geStopsOnTheLastCharacterNotTheLineEnd() {
        vim("word\n\n", 1, 0).keys("ge");
        h.assertCursorAt(0, 3);
    }

    @Test
    public void aLineEndSeparatesWords() {
        vim("ab\ncd", 1, 1).keys("ge");
        h.assertCursorAt(0, 1);
        vim("ab\ncd", 1, 0).keys("ge");
        h.assertCursorAt(0, 1);
        vim("ab.\n.cd", 1, 0).keys("ge");
        h.assertCursorAt(0, 2);
        vim("x ab\ncd", 1, 1).keys("dge");
        assertEquals("x a", h.value());
    }

    @Test
    public void geFromAWordStopsAtTheAdjacentPunctuation() {
        vim("ab.cd", 0, 3).keys("ge");
        h.assertCursorAt(0, 2);
        vim("ab.cd", 0, 4).keys("ge");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void geInTheFirstWordGoesToTheStart() {
        vim("abc def", 0, 2).keys("ge");
        h.assertCursorAt(0, 0);
    }

    @Test
    public void geStopsOnAnEmptyLine() {
        vim("ab\n\n\ncd", 3, 0).keys("ge");
        h.assertCursorAt(2, 0);
        vim("ab\n\ncd", 2, 0).keys("2ge");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void geSkipsALineOfBlanks() {
        vim("ab\n  \ncd", 2, 0).keys("ge");
        h.assertCursorAt(0, 1);
        vim("ab  \ncd", 1, 0).keys("ge");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void bigGeTakesPunctuationAsPartOfTheWord() {
        vim("a.b", 0, 2).keys("gE");
        h.assertCursorAt(0, 0);
    }

    // ---------------------------------------- an inclusive end on an empty line

    @Test
    public void dgeOntoTheWordBeforeBlankLines() {
        vim("word\n\n", 1, 0).keys("dge");
        assertEquals("wor\n", h.value());
    }

    @Test
    public void dgeTakesNothingFromTheEmptyLineItEndsOn() {
        vim("ab\n\ncd", 1, 0).keys("dge");
        assertEquals("a\ncd", h.value());
        vim("ab\n\ncd", 2, 0).keys("dge");
        assertEquals("ab\nd", h.value());
    }

    @Test
    public void dDollarOnAnEmptyLineDoesNothing() {
        vim("ab\n\ncd", 1, 0).keys("d$");
        assertEquals("ab\n\ncd", h.value());
    }

    @Test
    public void visualOnAnEmptyLineTakesItsNewline() {
        vim("ab\n\ncd", 1, 0).keys("vd");
        assertEquals("ab\ncd", h.value());
        vim("ab\n\ncd", 0, 1).keys("vjd");
        assertEquals("acd", h.value());
    }

    // ---------------------------------------- :help d

    @Test
    public void dgeOverBlankLinesTakesThemWhole() {
        vim("\n  \n", 2, 0).keys("dge");
        assertEquals("", h.value());
        vim("\n\n", 1, 0).keys("dge");
        assertEquals("", h.value());
        vim("ab\n\n  x", 2, 2).keys("dge");
        assertEquals("ab", h.value());
    }

    @Test
    public void aDeleteBetweenBlanksTakesTheLines() {
        vim(" word1\nword2", 0, 1).keys("d2w");
        assertEquals("", h.value());
        vim(" ab\ncd \nefgh", 0, 1).keys("d/ <CR>");
        assertEquals("efgh", h.value());
    }

    @Test
    public void aTextObjectBetweenBlanksTakesTheLines() {
        vim("  (a\nb)  \nz", 0, 3).keys("da(");
        assertEquals("z", h.value());
        vim("  (a\nb) c\nz", 0, 3).keys("da(");
        assertEquals("   c\nz", h.value());
    }

    @Test
    public void notWithTextBeforeTheStartOrAfterTheEnd() {
        vim("ab\n  \n  x", 2, 2).keys("dge");
        assertEquals("a", h.value());
        vim("  word1\nword2 x", 0, 2).keys("d2w");
        assertEquals("  x", h.value());
        vim(" ab\ncd \nefgh", 0, 2).keys("d/ <CR>");
        assertEquals(" a \nefgh", h.value());
    }

    @Test
    public void notForChange() {
        vim(" word1\nword2", 0, 1).keys("c2wx<Esc>");
        assertEquals(" x", h.value());
    }

    @Test
    public void notForAVisualDelete() {
        vim(" ab\ncd\nef", 0, 1).keys("vjd");
        assertEquals(" \nef", h.value());
    }

    // ---------------------------------------- an arrow in insert mode

    private static final String THREE = "one\ntwo\nthree";

    @Test
    public void dotAfterAnArrowRepeatsWhatWasTypedAfterItAsAnInsert() {
        vim(THREE, 1, 0).keys("Oab<Down>cd<Esc>");
        assertEquals("one\nab\ntwcdo\nthree", h.value());
        h.assertCursorAt(2, 3);
        h.keys(".");
        assertEquals("one\nab\ntwccddo\nthree", h.value());
        h.assertCursorAt(2, 4);
    }

    @Test
    public void dotAfterLeftAndRight() {
        vim(THREE, 1, 0).keys("iab<Right>cd<Esc>j0.");
        assertEquals("one\nabtcdwo\ncdthree", h.value());
        h.assertCursorAt(2, 1);
        vim("one two\nthree four", 0, 0).keys("Aab<Left><Left>cd<Esc>j.");
        assertEquals("one twocdab\nthree focdur", h.value());
        h.assertCursorAt(1, 9);
    }

    @Test
    public void anArrowWithNothingAfterItLeavesTheInsertBeforeIt() {
        vim(THREE, 1, 0).keys("Oab<Down><Esc>");
        h.assertCursorAt(2, 1);
        h.keys(".");
        assertEquals("one\nab\nab\ntwo\nthree", h.value());
        vim(THREE, 1, 1).keys("iab<Right><Esc>j0.");
        assertEquals("one\ntabwo\nabthree", h.value());
        h.assertCursorAt(2, 1);
    }

    @Test
    public void aBackspaceAfterAnArrowIsTheChange() {
        vim(THREE, 1, 2).keys("iab<Left><BS><Esc>");
        assertEquals("one\ntwbo\nthree", h.value());
        h.keys("j$.");
        assertEquals("one\ntwbo\nthre", h.value());
        h.assertCursorAt(2, 2);
    }

    @Test
    public void anArrowDropsTheCount() {
        vim(THREE, 1, 1).keys("3iab<Right>c<Esc>");
        assertEquals("one\ntabwco\nthree", h.value());
        h.assertCursorAt(1, 4);
    }

    @Test
    public void anArrowThatCannotMoveChangesNothing() {
        vim("one\ntwo", 0, 0).keys("2I<Left>ab<Esc>");
        assertEquals("ababone\ntwo", h.value());
        h.assertCursorAt(0, 3);
        h.keys("j.");
        assertEquals("ababone\nababtwo", h.value());
        h.assertCursorAt(1, 3);
    }

    @Test
    public void afterAnArrowInReplaceModeDotInserts() {
        vim("abcdef\nuvwxyz", 0, 0).keys("R12<Right>34<Esc>");
        assertEquals("12c34f\nuvwxyz", h.value());
        h.keys("j0.");
        assertEquals("12c34f\n34uvwxyz", h.value());
        h.assertCursorAt(1, 1);
    }

    @Test
    public void aKeyThatLeavesTheBufferStartsNoInsert() {
        final EditorHarness other = EditorHarness.create("other").vim();
        try {
            vim("abc", 0, 0).keys("ix<A-Right>");
            assertEquals(VimMode.NORMAL, h.vimState().getMode());
            assertEquals(other.buffer(), h.editor().getBuffer());
            h.keys("x.");
            assertEquals("her", other.value());
        }
        finally {
            other.close();
        }
    }

    @Test
    public void anArrowEndsTheUndoStep() {
        vim("xy", 0, 0).keys("iab<Left>cd<Esc>u");
        assertEquals("abxy", h.value());
        h.assertCursorAt(0, 1);
        h.keys("u");
        assertEquals("xy", h.value());
        h.assertCursorAt(0, 0);
    }

    // ---------------------------------------- % and quotes

    private void percent(String text, int offset, int expected) {
        vim(text, 0, offset).keys("%");
        h.assertCursorAt(0, expected);
    }

    @Test
    public void percentSkipsABracketInAString() {
        percent("(\")\")", 0, 4);
        percent("(a \")\" b)", 0, 8);
        percent("(\"(\")", 0, 4);
    }

    @Test
    public void percentBackwardSkipsItToo() {
        percent("(\")\")", 4, 0);
    }

    @Test
    public void percentFromInsideAStringCountsQuotesFromThere() {
        percent("\"(x)\"", 1, 3);
        percent("\"(\" \")\"", 1, 5);
        percent("x \"(a)\" y", 3, 5);
        // Crossing a quote leaves the only ( behind one.
        percent("(\")\")", 2, 2);
    }

    @Test
    public void percentIgnoresQuotesOnALineWithAnOddNumber() {
        percent("(a \") b", 0, 4);
        percent("\"( \")\"", 1, 4);
    }

    @Test
    public void percentUnmatchedOutsideTheStringFails() {
        percent("(a \")\" b", 0, 0);
    }

    @Test
    public void percentSkipsACharacterLiteral() {
        percent("(')')", 0, 4);
        percent("(')' x)", 0, 6);
    }

    @Test
    public void percentPairsEscapedWithEscaped() {
        percent("(\\))", 0, 3);
        percent("\\((\\))", 1, 4);
        percent("(\"\\\")\")", 0, 6);
    }

    @Test
    public void percentQuotesStartAfreshOnEachLine() {
        vim("(a \"b\"\nc \")\" d)", 0, 0).keys("%");
        h.assertCursorAt(1, 7);
        vim("(\")\"\n)", 0, 0).keys("%");
        h.assertCursorAt(1, 0);
    }

    // ---------------------------------------- % on tags and directives

    private void percentIn(Mode mode, String text, int line, int offset) {
        tearDown();
        h = EditorHarness.create().vim();
        h.value(text).mode(mode);
        h.buffer().getFormatter().parseBuffer();
        h.cursor(line, offset).keys("%");
    }

    @Test
    public void percentGoesBetweenAnElementsTagsNames() {
        final String xml = "<a>\n  <b x=\"1\">\n  </b>\n</a>";
        percentIn(XmlMode.getMode(), xml, 1, 5);
        h.assertCursorAt(2, 4);
        h.keys("%");
        h.assertCursorAt(1, 3);
        // Before the tag on its line.
        percentIn(XmlMode.getMode(), xml, 3, 0);
        h.assertCursorAt(0, 1);
    }

    @Test
    public void percentDeletesToTheOtherTag() {
        percentIn(XmlMode.getMode(), "<b>x</b>", 0, 1);
        h.keys("d%");
        assertEquals("<>", h.value());
    }

    @Test
    public void percentGoesBetweenDirectives() {
        percentIn(CMode.getMode(), "#if X\nf();\n#endif", 0, 0);
        h.assertCursorAt(2, 0);
    }
}
