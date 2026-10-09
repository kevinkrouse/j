/*
 * PairMatcherTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * findMatchingPair and the PairMatcher under it, which vim's % and the
 * matching-bracket highlight share: brackets in every mode, #if in C, and
 * start and end tags in XML and HTML.
 */
public class PairMatcherTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private void open(String text, Mode mode) {
        h = EditorHarness.create(text).mode(mode);
        h.buffer().getFormatter().parseBuffer();
        Editor.setCurrentEditor(h.editor());
    }

    private Line line(int lineNumber) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            l = l.next();
        return l;
    }

    /** Where findMatchingPair takes a caret at line:offset, as "line:offset". */
    private String jump(int lineNumber, int offset) {
        h.editor().setDot(line(lineNumber), offset);
        h.editor().moveCaretToDotCol();
        CaretCommands.findMatchingPair(h.editor());
        return h.editor().getDotLineNumber() + ":" + h.editor().getDotOffset();
    }

    /** The pair the highlight shows for pos, as "line:offset+length line:offset+length". */
    private String pairAt(int lineNumber, int offset) {
        final PairMatcher.Pair pair =
                h.buffer().getMode().getPairMatcher().pairAt(h.editor(), new Position(line(lineNumber), offset), 0);
        if (pair == null)
            return null;
        return describe(pair.at()) + " " + describe(pair.match());
    }

    private static String describe(PairMatcher.Delimiter d) {
        return d.pos().lineNumber() + ":" + d.pos().getOffset() + "+" + d.length();
    }

    // ---------------------------------------------------------- brackets

    @Test
    public void anOpenerAtTheCaretGoesPastItsCloser() {
        open("f(a, (b));\n", JavaMode.getMode());
        assertEquals("0:9", jump(0, 1));
    }

    @Test
    public void aCloserJustBeforeABarCaretGoesToItsOpener() {
        open("f(a, (b));\n", JavaMode.getMode());
        assertEquals("0:1", jump(0, 9));
    }

    @Test
    public void withNoBracketAtTheCaretTheFirstOneAfterItOnTheLineCounts() {
        open("if (a) { b(); }\n", JavaMode.getMode());
        // As vim's % looks ahead: over "if " to the '('.
        assertEquals("0:6", jump(0, 0));
    }

    @Test
    public void aBracketInAStringIsPassedOver() {
        open("f(\"(\", x);\n", JavaMode.getMode());
        assertEquals("0:9", jump(0, 1));
    }

    @Test
    public void nothingToMatchLeavesTheCaret() {
        open("abc\n", JavaMode.getMode());
        assertEquals("0:1", jump(0, 1));
    }

    // ---------------------------------------------------------------- C

    @Test
    public void aDirectiveGoesToItsPartner() {
        open("#if X\na(1);\n#else\nb(2);\n#endif\n", CMode.getMode());
        assertEquals("2:0", jump(0, 0));
        assertEquals("4:0", jump(2, 0));
    }

    @Test
    public void pastTheHashTheDirectiveStillMatchesWhenNoBracketDoes() {
        // j's C syntax hides a directive's brackets.
        open("#if defined(X)\n#endif\n", CMode.getMode());
        assertEquals("1:0", jump(0, 4));
    }

    // -------------------------------------------------------------- XML

    private static final String XML =
            "<project>\n" + "  <target name=\"a > b\">\n" + "    <target/>\n" + "    <target>x</target>\n"
                    + "    <!-- </target> -->\n" + "    <![CDATA[ </target> ]]>\n" + "  </target>\n" + "</project>\n";

    @Test
    public void aStartTagGoesToTheNameInItsEndTag() {
        open(XML, XmlMode.getMode());
        // From anywhere in the tag; nested, empty, commented and CDATA ones are passed over.
        assertEquals("6:4", jump(1, 2));
        assertEquals("6:4", jump(1, 14));
        assertEquals("6:4", jump(1, 22));
    }

    @Test
    public void anEndTagGoesToTheNameInItsStartTag() {
        open(XML, XmlMode.getMode());
        assertEquals("1:3", jump(6, 4));
        assertEquals("0:1", jump(7, 0));
    }

    @Test
    public void beforeATagOnItsLineTheTagCounts() {
        open(XML, XmlMode.getMode());
        assertEquals("7:2", jump(0, 0));
        assertEquals("1:3", jump(6, 0));
    }

    @Test
    public void aCaretAtTheEndOfTheLineIsInTheTagBeforeIt() {
        open(XML, XmlMode.getMode());
        assertEquals("0:1", jump(7, 10));
    }

    @Test
    public void anEmptyElementTagMatchesNothing() {
        open(XML, XmlMode.getMode());
        assertEquals("2:6", jump(2, 6));
    }

    @Test
    public void aCommentsEndsMatch() {
        open(XML, XmlMode.getMode());
        assertEquals("4:19", jump(4, 4));
        assertEquals("4:4", jump(4, 20));
    }

    @Test
    public void aMultiLineStartTagMatchesFromItsAttributes() {
        open("<a\n   x=\"1\">\n</a>\n", XmlMode.getMode());
        assertEquals("2:2", jump(1, 4));
    }

    @Test
    public void thePairIsTheTwoNames() {
        open(XML, XmlMode.getMode());
        assertEquals("1:3+6 6:4+6", pairAt(1, 10));
        assertEquals("4:4+4 4:19+3", pairAt(4, 5));
        assertNull(pairAt(2, 6));
        // Inside a comment, a tag is only text.
        assertNull(pairAt(4, 11));
    }

    // ------------------------------------------------------------- HTML

    @Test
    public void htmlNamesMatchWhateverTheirCase() {
        open("<DIV>\n<div>x</div>\n</div>\n", HtmlMode.getMode());
        assertEquals("2:2", jump(0, 0));
    }

    @Test
    public void htmlVoidElementsAndScriptTextDoNotNest() {
        open("<p>\n<br>\n<script>if (a<p) {}</script>\n</p>\n", HtmlMode.getMode());
        assertEquals("3:2", jump(0, 0));
        assertEquals("0:1", jump(3, 0));
    }

    // ---------------------------------------------------------- aliases

    @Test
    public void theOldNamesRunFindMatchingPair() {
        open(XML, XmlMode.getMode());
        for (String command : new String[] { "findMatchingChar", "xmlFindMatch", "htmlFindMatch", "cppFindMatch" }) {
            h.editor().setDot(line(1), 2);
            h.editor().executeCommand(command);
            assertEquals(6, h.editor().getDotLineNumber(), command);
            assertEquals(4, h.editor().getDotOffset(), command);
        }
    }

    @Test
    public void aScriptsTextIsNotTags() {
        open("<script>for (i=0;i<n;i++) f();</script>\n<p>x</p>\n", HtmlMode.getMode());
        assertEquals("0:32", jump(0, 1));
        assertEquals("0:1", jump(0, 33));
    }

    @Test
    public void anEmptyCommentsEndsMatch() {
        open("<!---->\n<!-- x -->\n", XmlMode.getMode());
        assertEquals("0:4", jump(0, 0));
    }
}
