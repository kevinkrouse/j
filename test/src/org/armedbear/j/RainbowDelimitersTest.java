/*
 * RainbowDelimitersTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The depths rainbowDelimiters colors brackets by, and the other ends of
 * strings highlightMatchingBracket shows.
 */
public class RainbowDelimitersTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text, Mode mode) {
        h = EditorHarness.create(text).mode(mode);
        Editor.setCurrentEditor(h.editor());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    private Line line(int lineNumber) {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            line = line.next();
        return line;
    }

    /** The brackets of a line, as "offset:level" for each. */
    private String levels(int lineNumber) {
        final int[] levels =
            h.buffer().getDelimiterDepths().levels(line(lineNumber));
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++)
            if (levels[i] != DelimiterDepths.NONE)
                sb.append(sb.length() == 0 ? "" : " ")
                    .append(i)
                    .append(':')
                    .append(levels[i]);
        return sb.toString();
    }

    private String matchingQuote(int lineNumber, int offset) {
        final Position match = CaretCommands.findMatchingQuote(
            h.editor(),
            new Position(line(lineNumber), offset),
            0
        );
        return match == null
            ? null
            : match.lineNumber() + ":" + match.getOffset();
    }

    @Test
    public void eachPairTakesTheDepthItOpens() {
        on("(a [b {c}] (d))\n", LispMode.getMode());
        assertEquals("0:1 3:2 6:3 8:3 9:2 11:2 13:2 14:1", levels(0));
    }

    @Test
    public void theDepthCarriesFromLineToLine() {
        on("f(a,\n  g(b),\n  c)\n", JavaMode.getMode());
        assertEquals("1:1", levels(0));
        assertEquals("3:2 5:2", levels(1));
        assertEquals("3:1", levels(2));
    }

    @Test
    public void bracketsInStringsAndCommentsAreLeftAlone() {
        on("f(\"(\", // )\n  /* ( */ ')')\n", JavaMode.getMode());
        assertEquals("1:1", levels(0));
        assertEquals("13:1", levels(1));
    }

    @Test
    public void aCommentRunningOnHidesTheNextLinesBrackets() {
        on("/* (\n ( */ (\n)\n", JavaMode.getMode());
        assertEquals("", levels(0));
        assertEquals("6:1", levels(1));
        assertEquals("0:1", levels(2));
    }

    @Test
    public void aClosingBracketWithNothingOpenIsUnmatched() {
        on("a) (b)\n)\n", JavaMode.getMode());
        assertEquals("1:0 3:1 5:1", levels(0));
        assertEquals("0:0", levels(1));
    }

    @Test
    public void anEditChangesTheDepthsBelowIt() {
        on("(\nx\n)\n", JavaMode.getMode());
        assertEquals("0:1", levels(2));
        h.cursor(1, 0).editor().insertChar('(');
        h.buffer().getFormatter().parseBuffer();
        assertEquals("0:2", levels(2));
    }

    @Test
    public void aNewModeMeansNewDepths() {
        on("(\n", JavaMode.getMode());
        final DelimiterDepths depths = h.buffer().getDelimiterDepths();
        h.mode(LispMode.getMode());
        assertNotSame(depths, h.buffer().getDelimiterDepths());
    }

    @Test
    public void xmlElementNamesTakeTheDepthTheyOpen() {
        on("<a>\n  <b x=\"(\"/>\n  <!-- <c> -->\n  <b\n     y=\"1\">\n  </b>\n</a>\n</z>\n", XmlMode.getMode());
        assertEquals("1:1", levels(0));
        // An empty element, a bracket in a value and a commented tag nest nothing.
        assertEquals("", levels(1));
        assertEquals("", levels(2));
        assertEquals("3:2", levels(3));
        assertEquals("", levels(4));
        assertEquals("4:2", levels(5));
        assertEquals("2:1", levels(6));
        assertEquals("2:0", levels(7));
    }

    @Test
    public void htmlLeavesVoidAndOftenUnclosedElementsOut() {
        on("<ul>\n<li><br>\n</ul>\n", HtmlMode.getMode());
        assertEquals("1:1 2:1", levels(0));
        assertEquals("", levels(1));
        assertEquals("2:1 3:1", levels(2));
    }

    @Test
    public void aQuoteFindsTheOtherEndOfItsString() {
        on("x = \"a\\\"b\" + 'c';\n", JavaMode.getMode());
        assertEquals("0:9", matchingQuote(0, 4));
        assertEquals("0:4", matchingQuote(0, 9));
        assertEquals("0:15", matchingQuote(0, 13));
        // The escaped one inside is not an end.
        assertNull(matchingQuote(0, 7));
    }

    @Test
    public void aLispStringCanRunOverLines() {
        on("(defun f ()\n  \"Doc\n  more.\")\n", LispMode.getMode());
        assertEquals("2:7", matchingQuote(1, 2));
        assertEquals("1:2", matchingQuote(2, 7));
    }

    @Test
    public void quotesInCommentsAndWordsMatchNothing() {
        on("; \"a\"\ndon't\n", LispMode.getMode());
        assertNull(matchingQuote(0, 2));
        on("don't\n", JavaMode.getMode());
        assertNull(matchingQuote(0, 3));
    }

    /** What highlightMatchingBracket highlights with the caret where it is. */
    private String highlighted() throws Exception {
        h.buffer().setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, true);
        final Display display = h.editor().getDisplay();
        final java.lang.reflect.Method paint =
            Display.class.getDeclaredMethod("initializePaint");
        paint.setAccessible(true);
        paint.invoke(display);
        final Position match = display.getMatchingBracketPosition();
        return match == null
            ? null
            : match.lineNumber() + ":" + match.getOffset();
    }

    @Test
    public void aBlockCaretOnAClosingBracketHighlightsTheOpeningOne()
        throws Exception {
        h = EditorHarness.create("f(a,\n  b)\n").vim().mode(JavaMode.getMode());
        Editor.setCurrentEditor(h.editor());
        h.cursor(1, 3);
        assertEquals("0:1", highlighted());
        // Vim's caret just past one is on something else.
        h.cursor(1, 4);
        assertNull(highlighted());
    }

    @Test
    public void aBarCaretJustPastAClosingBracketHighlightsTheOpeningOne()
        throws Exception {
        on("f(a,\n  b)\n", JavaMode.getMode());
        h.cursor(1, 4);
        assertEquals("0:1", highlighted());
        h.cursor(0, 1);
        assertEquals("1:3", highlighted());
    }

    @Test
    public void aCaretInATagHighlightsTheOtherTagsName() throws Exception {
        on("<a>\n  <b x=\"1\">text</b>\n</a>\n", XmlMode.getMode());
        h.cursor(1, 4);
        assertEquals("1:17", highlighted());
        // A quote in the tag is a pair of its own.
        h.cursor(1, 7);
        assertEquals("1:9", highlighted());
        // Just past the end tag's '>', a bar caret is still in it.
        h.cursor(1, 19);
        assertEquals("1:3", highlighted());
        h.cursor(1, 13);
        assertNull(highlighted());
    }

    @Test
    public void theCaretsOwnDelimiterIsHighlightedToo() throws Exception {
        on("f(a,\n  b)\n", JavaMode.getMode());
        h.cursor(0, 1);
        assertEquals("1:3", highlighted());
        final Position at = h.editor().getDisplay().getBracketPosition();
        assertEquals("0:1", at.lineNumber() + ":" + at.getOffset());
    }

    @Test
    public void aTagNameOffTheCaretsLineIsRepaintedWhenTheCaretLeaves() throws Exception {
        on("<a\n   x=\"1\">\ntext\n</a>\n", XmlMode.getMode());
        h.cursor(1, 4);
        assertEquals("3:2", highlighted());
        assertEquals(0, h.editor().getDisplay().getBracketPosition().lineNumber());
        final java.lang.reflect.Field field = Display.class.getDeclaredField("changedLines");
        field.setAccessible(true);
        final java.util.Map<?, ?> changed = (java.util.Map<?, ?>) field.get(h.editor().getDisplay());
        changed.clear();
        h.cursor(2, 1);
        assertNull(highlighted());
        assertTrue(changed.containsKey(h.buffer().getFirstLine()), "the tag's line is repainted");
    }
}
