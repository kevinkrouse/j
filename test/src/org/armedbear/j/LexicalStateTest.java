/*
 * LexicalStateTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.ElectricPairs.Action.INSERT_PAIR;
import static org.armedbear.j.ElectricPairs.Action.PLAIN;
import static org.armedbear.j.LexicalState.COMMENT;
import static org.armedbear.j.LexicalState.NEUTRAL;
import static org.armedbear.j.LexicalState.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.ElectricPairs.Action;
import org.armedbear.j.ElectricPairs.Decision;
import org.armedbear.j.ElectricPairs.Operation;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.python.PythonMode;
import org.armedbear.j.mode.sh.ShellScriptMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The lexical-state query: the state at a position after consuming
 * the line prefix through the caret, not the entering flags and not
 * the segment under the caret. The electric-pairs decision reads this
 * instead of the old {@code isInQuote}/{@code isInComment} pair.
 */
public class LexicalStateTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /** The state at {@code line}:{@code offset} in {@code text} under {@code mode}. */
    private LexicalState stateAt(Mode mode, String text, int line, int offset) {
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        return mode.lexicalState(h.buffer(), new Position(h.buffer().getLine(line), offset));
    }

    private void assertState(LexicalState expected, Mode mode, String text, int line, int offset) {
        assertEquals(
            expected,
            stateAt(mode, text, line, offset),
            mode.getDisplayName() + " at " + line + ":" + offset + " of [" + text + "]");
    }

    // The decision for typing {@code c} at {@code line}:{@code offset}.
    private Decision typeAt(Mode mode, String text, int line, int offset, char c) {
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        return ElectricPairs.decide(
            mode,
            h.buffer(),
            new Position(h.buffer().getLine(line), offset),
            Operation.INSERT,
            c,
            false,
            false);
    }

    // Python: the entering flags say NEUTRAL at EOL after a comment;
    // the query must report COMMENT. A closed string is NEUTRAL.

    @Test
    public void pythonCommentAtEol() {
        // "# comment|" — the caret after the whole comment.
        assertState(COMMENT, PythonMode.getMode(), "# comment", 0, 9);
    }

    @Test
    public void pythonInsideComment() {
        assertState(COMMENT, PythonMode.getMode(), "# comment", 0, 4);
    }

    @Test
    public void pythonClosedStringIsNeutral() {
        // "\"foo\"|" — the closing quote's transition consumed the string.
        assertState(NEUTRAL, PythonMode.getMode(), "\"foo\"", 0, 5);
    }

    @Test
    public void pythonInsideString() {
        assertState(STRING, PythonMode.getMode(), "\"foo\"", 0, 2);
    }

    @Test
    public void pythonTripleQuoteRemainsString() {
        // A triple-quoted string with an isolated " and an escaped
        // quote inside stays STRING across continuation lines.
        String text = "x = \"\"\"a \" b \\\" c\nstill inside\nd\"\"\" done";
        assertState(STRING, PythonMode.getMode(), text, 0, 12);
        assertState(STRING, PythonMode.getMode(), text, 1, 5);
        // After the closing triple delimiter: NEUTRAL.
        assertState(NEUTRAL, PythonMode.getMode(), text, 2, 8);
    }

    @Test
    public void pythonTripleQuoteTabbedPrefix() {
        String text = "\t\"\"\"tabbed\ninside\"\"\"";
        assertState(STRING, PythonMode.getMode(), text, 0, 5);
        assertState(STRING, PythonMode.getMode(), text, 1, 2);
        assertState(NEUTRAL, PythonMode.getMode(), text, 1, 10);
    }

    @Test
    public void pythonCommentDecisionBlocksPair() {
        // Trailing spaces so the word-inhibit rule cannot hide a
        // broken comment query.
        Decision d = typeAt(PythonMode.getMode(), "# x ", 0, 4, '(');
        assertEquals(PLAIN, d.action());
    }

    @Test
    public void pythonStringDecisionBlocksPair() {
        Decision d = typeAt(PythonMode.getMode(), "\"x ", 0, 3, '(');
        assertEquals(PLAIN, d.action());
    }

    // Shell: a heredoc body is STRING until the exact terminator; a
    // partial terminator does not end it; the line after is NEUTRAL.

    @Test
    public void shellHeredocBodyIsString() {
        String text = "cat <<EOF\nbody line\nEOF\ndone";
        assertState(STRING, ShellScriptMode.getMode(), text, 1, 4);
        // The terminator line itself: the body ended before it, so
        // the line entering HERE_DOCUMENT is the body; the terminator
        // line enters NEUTRAL.
        assertState(NEUTRAL, ShellScriptMode.getMode(), text, 2, 0);
        assertState(NEUTRAL, ShellScriptMode.getMode(), text, 3, 0);
    }

    @Test
    public void shellPartialTerminatorDoesNotEnd() {
        // A line that starts with the terminator but is longer is
        // still body.
        String text = "cat <<EOF\nEOFextra\nEOF\n";
        assertState(STRING, ShellScriptMode.getMode(), text, 1, 3);
    }

    @Test
    public void shellSequentialHeredocsDoNotLeak() {
        // Two heredocs with distinct terminators: the second must see
        // its own, not a leaked one.
        String text = "cat <<AAA\nfirst\nAAA\ncat <<BBB\nsecond\nBBB\n";
        assertState(STRING, ShellScriptMode.getMode(), text, 1, 2);
        assertState(STRING, ShellScriptMode.getMode(), text, 4, 2);
        assertState(NEUTRAL, ShellScriptMode.getMode(), text, 3, 0);
    }

    @Test
    public void shellMultiLineSingleQuoteCarriesAcrossLines() {
        String text = "awk '\n  { print $1 }\n' | grep \"x(\"";
        // Line 2 is inside the single-quoted string.
        assertState(STRING, ShellScriptMode.getMode(), text, 1, 2);
        // On line 3 the quote closes it: after it, NEUTRAL.
        assertState(NEUTRAL, ShellScriptMode.getMode(), text, 2, 2);
    }

    @Test
    public void javaCaretAfterABackslashInAStringIsInTheString() {
        // s = "a\| b"; : the caret sits between the backslash and the
        // character it escapes.
        assertState(STRING, JavaMode.getMode(), "s = \"a\\ b\";", 0, 7);
    }

    @Test
    public void javaCaretBetweenCommentMarkerCharsIsBeforeTheComment() {
        assertState(NEUTRAL, JavaMode.getMode(), "x /* c */", 0, 3);
        assertState(NEUTRAL, JavaMode.getMode(), "x // c", 0, 3);
        assertState(COMMENT, JavaMode.getMode(), "/* c */ x", 0, 6);
    }

    @Test
    public void shellCommentBlocksPair() {
        Decision d = typeAt(ShellScriptMode.getMode(), "# x ", 0, 4, '(');
        assertEquals(PLAIN, d.action());
    }

    // Markdown: a blank fence body at offset zero is COMMENT; an HTML
    // comment and inline code are COMMENT; after their closers,
    // NEUTRAL. Prose with an apostrophe stays NEUTRAL.

    @Test
    public void markdownBlankFenceAtOffsetZero() {
        String text = "```\n\ncode\n```";
        // The blank line inside the fence, at offset zero.
        assertState(COMMENT, MarkdownMode.getMode(), text, 1, 0);
    }

    @Test
    public void markdownFenceWithLanguage() {
        String text = "```python\n\ncode\n```";
        assertState(COMMENT, MarkdownMode.getMode(), text, 1, 0);
    }

    @Test
    public void markdownCodeSpanAfterAnUnclosedBacktickRun() {
        // The `` has no closer, so it is literal; the caret is inside
        // the single-backtick span after it.
        String text = "Use `` and `f()` here";
        assertState(COMMENT, MarkdownMode.getMode(), text, 0, 13);
    }

    @Test
    public void markdownHtmlComment() {
        String text = "<!-- hidden -->";
        assertState(COMMENT, MarkdownMode.getMode(), text, 0, 5);
        // After the closing marker: NEUTRAL.
        assertState(NEUTRAL, MarkdownMode.getMode(), text, 0, 15);
    }

    @Test
    public void markdownInlineCode() {
        String text = "a `code` b";
        assertState(COMMENT, MarkdownMode.getMode(), text, 0, 4);
        // After the closing backtick: NEUTRAL.
        assertState(NEUTRAL, MarkdownMode.getMode(), text, 0, 9);
    }

    @Test
    public void markdownUnclosedBacktickIsLiteral() {
        // No close anywhere: the backticks are literal, not a span.
        assertState(NEUTRAL, MarkdownMode.getMode(), "a ` not code", 0, 8);
    }

    @Test
    public void markdownApostropheStaysNeutral() {
        // "don't |" — the apostrophe is prose, not a string.
        assertState(NEUTRAL, MarkdownMode.getMode(), "don't ", 0, 6);
        Decision d = typeAt(MarkdownMode.getMode(), "don't ", 0, 6, '(');
        assertEquals(INSERT_PAIR, d.action());
    }

    @Test
    public void markdownEscapedBacktickIsLiteral() {
        // A backslash-escaped backtick does not open a code span.
        assertState(NEUTRAL, MarkdownMode.getMode(), "a \\` not code", 0, 8);
    }

    // Java and Lisp: the existing overrides adapt; the query agrees
    // with the old behavior where it was already correct.

    @Test
    public void javaLineComment() {
        assertState(COMMENT, JavaMode.getMode(), "// x ", 0, 4);
    }

    @Test
    public void javaString() {
        assertState(STRING, JavaMode.getMode(), "\"x\"", 0, 2);
    }

    @Test
    public void javaClosedStringIsNeutral() {
        assertState(NEUTRAL, JavaMode.getMode(), "\"x\" ", 0, 4);
    }

    @Test
    public void lispLineComment() {
        assertState(COMMENT, LispMode.getMode(), "; x ", 0, 4);
    }

    @Test
    public void lispString() {
        assertState(STRING, LispMode.getMode(), "\"x\"", 0, 2);
    }

    @Test
    public void lispBlockComment() {
        assertState(COMMENT, LispMode.getMode(), "#| block |# x", 0, 5);
        // After the closing |#: NEUTRAL.
        assertState(NEUTRAL, LispMode.getMode(), "#| block |# x", 0, 11);
    }

    // The plain-text default: the formatter has no query, so the
    // fallback to isInQuote/isInComment still applies.

    @Test
    public void plainTextFallsBackToDefaults() {
        // The default isInQuote treats " as a quote.
        assertState(STRING, PlainTextMode.getMode(), "\"x", 0, 2);
    }

    // A disabled line reads as COMMENT: pairing has no business inside
    // a #if 0 block, so a delimiter there must not open a pair. The
    // disabled state is line-based, set by CMode's formatter.

    @Test
    public void disabledLineIsComment() {
        String text = "#if 0\nint x = (1);\n#endif\n";
        assertState(COMMENT, CMode.getMode(), text, 1, 9);
    }

    // A line that enters inside a multiline HTML comment is COMMENT
    // only until the closing marker is consumed; the prose after it
    // pairs normally.
    @Test
    public void markdownResumesAfterMultilineComment() {
        String text = "<!-- open\nstill --> prose (here\n";
        // Before the marker: still inside the comment.
        assertState(COMMENT, MarkdownMode.getMode(), text, 1, 3);
        // Just past the marker: the comment closed, prose is NEUTRAL.
        assertState(NEUTRAL, MarkdownMode.getMode(), text, 1, 12);
    }

    // The exact boundary: the caret one past the closing marker of a
    // multiline comment is prose, not comment. Typing ( there must
    // insert the pair, not only the opener.
    @Test
    public void markdownCaretRightAfterMultilineCommentMarker() {
        String text = "<!-- hidden\n-->";
        // Inside the marker: still COMMENT.
        assertState(COMMENT, MarkdownMode.getMode(), text, 1, 2);
        // One past the marker: NEUTRAL, and ( pairs.
        assertState(NEUTRAL, MarkdownMode.getMode(), text, 1, 3);
        Decision d = typeAt(MarkdownMode.getMode(), text, 1, 3, '(');
        assertEquals(INSERT_PAIR, d.action());
    }

    // An unmatched backtick run is literal: a caret between the
    // backticks is NEUTRAL, not left unset. If it fell through to the
    // generic quote scanner, the apostrophe in "don't" earlier on the
    // line would read as an open string and inhibit pairing.
    @Test
    public void markdownUnmatchedBackticksAreNeutral() {
        String text = "don't ``";
        // Between the two backticks: NEUTRAL, and ( pairs.
        assertState(NEUTRAL, MarkdownMode.getMode(), text, 0, 7);
        Decision d = typeAt(MarkdownMode.getMode(), text, 0, 7, '(');
        assertEquals(INSERT_PAIR, d.action());
    }

    // The empty-quote-pair exception: the caret between two quotes of
    // a configured symmetric pair, with NEUTRAL before the left one.
    // The guard that lets Backspace delete both despite the STRING
    // state at the caret.

    private boolean emptyPair(Mode mode, String text, int offset) {
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        return ElectricPairs.isEmptyQuotePair(mode, h.buffer(), h.buffer().getLine(0), offset);
    }

    @Test
    public void emptyQuotePairIsRecognized() {
        // "\"|\"" — the caret between the two quotes.
        assertTrue(emptyPair(PlainTextMode.getMode(), "\"\"", 1));
    }

    @Test
    public void emptyQuotePairAfterClosedString() {
        // "a\"\"|\"\"" — the second pair follows a closed string on
        // the same line; the state before its left quote is NEUTRAL
        // because the first string closed.
        assertTrue(emptyPair(PlainTextMode.getMode(), "\"\" \"\"", 4));
    }

    @Test
    public void escapedQuoteIsNotAnEmptyPair() {
        // "\"\\\"|\"" — the left quote is escaped, so the pair is
        // not the empty string.
        assertFalse(emptyPair(PlainTextMode.getMode(), "\"\\\"\"", 3));
    }

    @Test
    public void quoteInsideStringIsNotAnEmptyPair() {
        // The two quotes sit inside a longer string: the state
        // before the left one is STRING, not NEUTRAL.
        assertFalse(emptyPair(PlainTextMode.getMode(), "\"a\"\"b\"", 3));
    }

    @Test
    public void quotesInCommentAreNotAnEmptyPair() {
        assertFalse(emptyPair(PythonMode.getMode(), "# \"\"", 3));
    }

    @Test
    public void nonQuotePairIsNotAnEmptyPair() {
        // (|) is a bracket pair, not a quote pair: the exception is
        // for quotes only.
        assertFalse(emptyPair(PlainTextMode.getMode(), "()", 1));
    }

    // Parse freshness: a query after an edit that changes the
    // entering context must see the new state, not the flags left by
    // the parse before the edit.
    @Test
    public void querySeesFreshParseAfterEdit() {
        if (h != null)
            h.close();
        h = EditorHarness.create("x = 1\nsecond line\n").mode(PythonMode.getMode());
        // Before the edit: line 1 is ordinary code.
        assertEquals(NEUTRAL, h.buffer().getMode().lexicalState(h.buffer(), new Position(h.buffer().getLine(1), 3)));
        // Open a triple-quoted string on line 0; line 1 is now body.
        h.buffer().insertString(new Position(h.buffer().getLine(0), 5), " \"\"\"open");
        assertEquals(STRING, h.buffer().getMode().lexicalState(h.buffer(), new Position(h.buffer().getLine(1), 3)));
    }

    // The refresh inside the query rewrote line flags downstream of
    // the edit. The insert itself schedules no repaint, so without the
    // repaint in the refresh path the idle parser finds the flags
    // already correct and never schedules one, leaving the old
    // highlighting on the lines this keystroke changed the state of.
    @Test
    public void queryRepaintsWhenRefreshChangesFlags() {
        if (h != null)
            h.close();
        h = EditorHarness.create("x = 1\nsecond line\n").mode(PythonMode.getMode());
        h.clearRepaintPending();
        h.buffer().insertString(new Position(h.buffer().getLine(0), 5), " \"\"\"open");
        // The insert alone owes no repaint.
        assertFalse(h.repaintPending());
        h.clearRepaintPending();
        // The query refreshes the parse and must ask for a repaint.
        assertEquals(STRING, h.buffer().getMode().lexicalState(h.buffer(), new Position(h.buffer().getLine(1), 3)));
        assertTrue(h.repaintPending());
    }
}
