/*
 * VimM20Test.java
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * M20: section, bracket and sentence motions, and { and } on the same scan
 * as sections. Every expectation is nvim's.
 */
public class VimM20Test {
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

    /** Where the keys leave the caret, as "line,offset". */
    private String caret(String text, int line, int offset, String keys) {
        vim(text, line, offset).keys(keys);
        return h.lineNumber() + "," + h.offset();
    }

    /** The text the keys leave. */
    private String value(String text, int line, int offset, String keys) {
        vim(text, line, offset).keys(keys);
        return h.value();
    }

    // ------------------------------------------------------- { and }

    @Test
    public void paragraphMotionsPassARunOfEmptyLines() {
        final String text = "a\n\n\n\nb\n\nc";
        assertEquals("5,0", caret(text, 1, 0, "}"));
        assertEquals("3,0", caret(text, 5, 0, "{"));
    }

    @Test
    public void aParagraphCountThatRunsOutGoesNowhere() {
        final String text = "a\n\nb\nc\n\nd";
        assertEquals("5,0", caret(text, 0, 0, "3}"));
        assertEquals("0,0", caret(text, 0, 0, "6}"));
        assertEquals("5,0", caret(text, 5, 0, "6{"));
    }

    @Test
    public void formFeedsAndMacrosStartParagraphs() {
        assertEquals("1,0", caret("a\n\fb\nc", 0, 0, "}"));
        assertEquals("1,0", caret("a\n.PP x\nc", 0, 0, "}"));
    }

    // ------------------------------------------------- ]] [[ ][ []

    @Test
    public void sectionsStartAtABraceInTheFirstColumn() {
        final String text = "a\n {\n{\nb\n}\nc";
        assertEquals("2,0", caret(text, 0, 0, "]]"));
        assertEquals("4,0", caret(text, 0, 0, "]["));
        assertEquals("2,0", caret(text, 5, 0, "[["));
        assertEquals("4,0", caret(text, 5, 0, "[]"));
    }

    @Test
    public void sectionMotionsGoToTheFirstNonBlankAtTheEdges() {
        assertEquals("1,3", caret("abc\n   def", 0, 0, "]]"));
        assertEquals("1,3", caret("  abc\n   def", 0, 0, "]["));
        assertEquals("0,2", caret("  abc\ndef", 1, 0, "[["));
        assertEquals("0,2", caret("  abc\ndef", 1, 0, "[]"));
    }

    @Test
    public void formFeedsAndSectionMacrosStartSections() {
        assertEquals("1,0", caret("a\n\fb\nc", 0, 0, "]]"));
        assertEquals("1,0", caret("a\n.SH x\nc", 0, 0, "]]"));
        // A paragraph macro is not a section.
        assertEquals("2,0", caret("a\n.PP x\nc", 0, 0, "]]"));
    }

    @Test
    public void aSectionCountThatRunsOutGoesNowhere() {
        assertEquals("0,0", caret("a\n{\nb", 0, 0, "5]]"));
        assertEquals("2,0", caret("a\n{\nb", 2, 0, "5[["));
    }

    @Test
    public void deleteToASectionStopsAtAClosingBraceAndTakesIt() {
        final String text = "abc\n{\nx\n}\ny";
        assertEquals("{\nx\n}\ny", value(text, 0, 0, "d]]"));
        assertEquals("abc\n{", value(text, 2, 0, "d]]"));
        assertEquals("abc\n{\n}\ny", value(text, 2, 0, "d]["));
        assertEquals("abc\n{\nZ", value(text, 2, 0, "c]]Z<Esc>"));
    }

    @Test
    public void deleteToTheLastSectionTakesTheLastLine() {
        assertEquals("", value("abc\ndef", 0, 0, "d]]"));
        assertEquals("", value("a\n{", 0, 0, "d]]"));
        // A closing brace on the last line leaves nothing after it to take.
        assertEquals("}", value("x\n}", 0, 0, "d]]"));
        // Nor from that line itself.
        assertEquals("x\n}ab", value("x\n}ab", 1, 2, "d]]"));
    }

    @Test
    public void deleteToASectionStopsAtTheClosingBraceBeforeIt() {
        assertEquals(
            "abc\n{\ny\nz\n{\nw",
            value("abc\n{\nx\n}\ny\nz\n{\nw", 2, 0, "d]]")
        );
    }

    @Test
    public void sectionMotionsAreJumps() {
        assertEquals("0,0", caret("a\n{\nb", 0, 0, "]]``"));
    }

    // --------------------------------------------------- [( [{ ]) ]}

    @Test
    public void toTheUnmatchedBracket() {
        assertEquals("0,1", caret("x(a b)y", 0, 3, "[("));
        assertEquals("0,5", caret("x(a b)y", 0, 3, "])"));
        assertEquals("0,2", caret("a {b} c", 0, 3, "[{"));
        assertEquals("0,4", caret("a {b} c", 0, 3, "]}"));
    }

    @Test
    public void theBracketUnderTheCaretDoesNotCount() {
        assertEquals("0,3", caret("x(a(b)c)", 0, 5, "[("));
        assertEquals("0,5", caret("x(a(b)c)", 0, 3, "])"));
        assertEquals("0,1", caret("x(a(b)c)", 0, 1, "[("));
    }

    @Test
    public void aBracketCountGoesAsFarOutAsItCan() {
        assertEquals("0,1", caret("x(a(b)c)", 0, 4, "2[("));
        assertEquals("0,1", caret("x(a(b)c)", 0, 4, "5[("));
    }

    @Test
    public void unmatchedBracketsSkipQuotesAndEscapes() {
        assertEquals("0,7", caret("x(a\"(\")b)", 0, 7, "[("));
        assertEquals("0,3", caret("a\\(b", 0, 3, "[("));
        assertEquals("0,0", caret("(a\\(b", 0, 4, "[("));
    }

    @Test
    public void theCaretBeingEscapedDoesNotMatter() {
        // Unlike %, which matches an escaped bracket only with another.
        assertEquals("0,5", caret("\\aa{')", 0, 1, "])"));
        assertEquals("0,1", caret("a{\\)", 0, 3, "[{"));
    }

    @Test
    public void aDoubleQuoteInSingleQuotesIsNotAQuote() {
        assertEquals("0,1", caret("(('\"'()(", 0, 7, "[("));
        assertEquals("0,5", caret("x('\"')", 0, 1, "%"));
    }

    @Test
    public void unmatchedBracketMotionsAreExclusive() {
        assertEquals("a()c", value("a(b)c", 0, 2, "d])"));
        assertEquals("ab)c", value("a(b)c", 0, 2, "d[("));
        assertEquals("x)", value("x(a(b)c)", 0, 1, "d])"));
    }

    @Test
    public void unmatchedBracketMotionsAreJumps() {
        assertEquals("0,3", caret("x(a b)y", 0, 3, "[(``"));
    }

    // ------------------------------------------------------- ( and )

    @Test
    public void sentencesForward() {
        final String text = "One two.  Three four. Five";
        assertEquals("0,10", caret(text, 0, 0, ")"));
        assertEquals("0,22", caret(text, 0, 0, "2)"));
        assertEquals("0,25", caret(text, 0, 0, "3)"));
        assertEquals("0,0", caret(text, 0, 0, "4)"));
        assertEquals("0,10", caret(text, 0, 8, ")"));
    }

    @Test
    public void sentencesBackward() {
        final String text = "One two.  Three four. Five";
        assertEquals("0,22", caret(text, 0, 24, "("));
        assertEquals("0,10", caret(text, 0, 24, "2("));
        assertEquals("0,10", caret(text, 0, 12, "("));
        assertEquals("0,0", caret(text, 0, 10, "("));
        assertEquals("0,0", caret(text, 0, 7, "("));
    }

    @Test
    public void closingPunctuationAfterTheEndBelongsToTheSentence() {
        assertEquals("0,10", caret("Say (hi.) Then.", 0, 0, ")"));
        assertEquals("0,10", caret("Say \"hi.\" Then.", 0, 0, ")"));
        assertEquals("0,6", caret("Wow!? Next", 0, 0, ")"));
        // Back over one end only, not on over the one before.
        assertEquals("0,5", caret("a. . b", 0, 3, ")"));
        // Not an end without a blank after it.
        assertEquals("0,5", caret("e.g. this. Next", 0, 0, ")"));
    }

    @Test
    public void sentencesAcrossLines() {
        assertEquals("1,5", caret("One\ntwo. Three", 0, 0, ")"));
        assertEquals("1,0", caret("One.\ntwo. Three", 0, 0, ")"));
        assertEquals("0,8", caret("   One. Two.", 0, 0, ")"));
        assertEquals("0,0", caret("   One. Two.", 0, 8, "("));
    }

    @Test
    public void emptyLinesAndSectionsAreSentenceBoundaries() {
        assertEquals("1,0", caret("\n.a a \t!  ", 1, 2, "("));
        final String text = "One.\n\n\ntwo";
        assertEquals("1,0", caret(text, 0, 0, ")"));
        assertEquals("3,0", caret(text, 1, 0, ")"));
        assertEquals("2,0", caret(text, 3, 0, "("));
        assertEquals("0,0", caret(text, 2, 0, "("));
        assertEquals("1,0", caret("a. b\n.SH x\nc", 0, 0, "2)"));
    }

    @Test
    public void sentenceMotionsUnderAnOperator() {
        assertEquals("Three", value("One two. Three", 0, 0, "d)"));
        assertEquals("Three", value("One two. Three", 0, 9, "d("));
        assertEquals("One Three", value("One two. Three", 0, 4, "d)"));
        assertEquals("One. ", value("One. Two.", 0, 5, "d)"));
    }

    @Test
    public void aSentenceMotionThatDoesNotMoveTriesAgain() {
        assertEquals("1,0", caret(".\n.\"  ", 1, 0, "("));
        assertEquals("1,0", caret(".\n.\"  ", 1, 0, ")"));
    }

    @Test
    public void sentenceMotionsAreJumps() {
        assertEquals("0,8", caret("A b. C d.", 0, 8, "(``"));
    }

    // ------------------------------------------------------------- ^

    @Test
    public void aFormFeedIsNotABlank() {
        assertEquals("0,0", caret("\fb", 0, 1, "^"));
        // Nor to :help d, which takes whole lines only after blanks.
        assertEquals("\f", value("\fab\nc", 0, 1, "d}"));
        assertEquals("", value(" ab\nc", 0, 1, "d}"));
    }
}
