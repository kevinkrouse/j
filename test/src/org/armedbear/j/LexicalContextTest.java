/*
 * LexicalContextTest.java
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
import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.armedbear.j.LexicalContext.OPAQUE;
import static org.armedbear.j.LexicalContext.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.ElectricPairs.Action;
import org.armedbear.j.ElectricPairs.Decision;
import org.armedbear.j.ElectricPairs.Operation;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.css.CSSMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.python.PythonMode;
import org.armedbear.j.mode.sh.ShellScriptMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The lexical-context query: the state after consuming the line prefix
 * through the caret, not the entering flags or the segment under it.
 * The electric-pairs decision reads this instead of the old
 * {@code isInQuote}/{@code isInComment} pair.
 */
public class LexicalContextTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /** The state at {@code line}:{@code offset} in {@code text} under {@code mode}. */
    private LexicalContext contextAt(Mode mode, String text, int line, int offset) {
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        return mode.lexicalContext(h.buffer(), new Position(h.buffer().getLine(line), offset));
    }

    private void assertContext(LexicalContext expected, Mode mode, String text, int line, int offset) {
        assertEquals(
            expected,
            contextAt(mode, text, line, offset),
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
        assertContext(COMMENT, PythonMode.getMode(), "# comment", 0, 9);
    }

    @Test
    public void pythonInsideComment() {
        assertContext(COMMENT, PythonMode.getMode(), "# comment", 0, 4);
    }

    @Test
    public void pythonClosedStringIsNeutral() {
        // "\"foo\"|" — the closing quote's transition consumed the string.
        assertContext(NEUTRAL, PythonMode.getMode(), "\"foo\"", 0, 5);
    }

    @Test
    public void pythonInsideString() {
        assertContext(STRING, PythonMode.getMode(), "\"foo\"", 0, 2);
    }

    @Test
    public void pythonTripleQuoteRemainsString() {
        // A triple-quoted string with an isolated " and an escaped
        // quote inside stays STRING across continuation lines.
        String text = "x = \"\"\"a \" b \\\" c\nstill inside\nd\"\"\" done";
        assertContext(STRING, PythonMode.getMode(), text, 0, 12);
        assertContext(STRING, PythonMode.getMode(), text, 1, 5);
        // After the closing triple delimiter: NEUTRAL.
        assertContext(NEUTRAL, PythonMode.getMode(), text, 2, 8);
    }

    @Test
    public void pythonTripleQuoteTabbedPrefix() {
        String text = "\t\"\"\"tabbed\ninside\"\"\"";
        assertContext(STRING, PythonMode.getMode(), text, 0, 5);
        assertContext(STRING, PythonMode.getMode(), text, 1, 2);
        assertContext(NEUTRAL, PythonMode.getMode(), text, 1, 10);
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
        assertContext(STRING, ShellScriptMode.getMode(), text, 1, 4);
        // The body ended before the terminator line, so the terminator
        // line itself enters NEUTRAL.
        assertContext(NEUTRAL, ShellScriptMode.getMode(), text, 2, 0);
        assertContext(NEUTRAL, ShellScriptMode.getMode(), text, 3, 0);
    }

    @Test
    public void shellPartialTerminatorDoesNotEnd() {
        // A line that starts with the terminator but is longer is
        // still body.
        String text = "cat <<EOF\nEOFextra\nEOF\n";
        assertContext(STRING, ShellScriptMode.getMode(), text, 1, 3);
    }

    @Test
    public void shellSequentialHeredocsDoNotLeak() {
        // Two heredocs with distinct terminators: the second must see
        // its own, not a leaked one.
        String text = "cat <<AAA\nfirst\nAAA\ncat <<BBB\nsecond\nBBB\n";
        assertContext(STRING, ShellScriptMode.getMode(), text, 1, 2);
        assertContext(STRING, ShellScriptMode.getMode(), text, 4, 2);
        assertContext(NEUTRAL, ShellScriptMode.getMode(), text, 3, 0);
    }

    @Test
    public void shellMultiLineSingleQuoteCarriesAcrossLines() {
        String text = "awk '\n  { print $1 }\n' | grep \"x(\"";
        // Line 2 is inside the single-quoted string; line 3 closes it.
        assertContext(STRING, ShellScriptMode.getMode(), text, 1, 2);
        assertContext(NEUTRAL, ShellScriptMode.getMode(), text, 2, 2);
    }

    @Test
    public void javaCaretAfterABackslashInAStringIsInTheString() {
        // s = "a\| b"; : the caret sits between the backslash and the
        // character it escapes.
        assertContext(STRING, JavaMode.getMode(), "s = \"a\\ b\";", 0, 7);
    }

    @Test
    public void javaCaretBetweenCommentMarkerCharsIsBeforeTheComment() {
        assertContext(NEUTRAL, JavaMode.getMode(), "x /* c */", 0, 3);
        assertContext(NEUTRAL, JavaMode.getMode(), "x // c", 0, 3);
        assertContext(COMMENT, JavaMode.getMode(), "/* c */ x", 0, 6);
    }

    @Test
    public void shellCommentBlocksPair() {
        Decision d = typeAt(ShellScriptMode.getMode(), "# x ", 0, 4, '(');
        assertEquals(PLAIN, d.action());
    }

    // Markdown: fence bodies and inline code are OPAQUE, not comments;
    // HTML comments are COMMENT; after closers, NEUTRAL. Prose with an
    // apostrophe stays NEUTRAL.

    @Test
    public void markdownBlankFenceAtOffsetZero() {
        String text = "```\n\ncode\n```";
        // The blank line inside the fence, at offset zero.
        assertContext(OPAQUE, MarkdownMode.getMode(), text, 1, 0);
    }

    @Test
    public void markdownFenceWithLanguage() {
        String text = "```python\n\ncode\n```";
        assertContext(OPAQUE, MarkdownMode.getMode(), text, 1, 0);
    }

    @Test
    public void markdownCodeSpanAfterAnUnclosedBacktickRun() {
        // The `` has no closer so it is literal; the caret is inside
        // the single-backtick span after it.
        String text = "Use `` and `f()` here";
        assertContext(OPAQUE, MarkdownMode.getMode(), text, 0, 13);
    }

    @Test
    public void markdownHtmlComment() {
        String text = "<!-- hidden -->";
        assertContext(COMMENT, MarkdownMode.getMode(), text, 0, 5);
        assertContext(NEUTRAL, MarkdownMode.getMode(), text, 0, 15);
    }

    @Test
    public void markdownInlineCode() {
        String text = "a `code` b";
        assertContext(OPAQUE, MarkdownMode.getMode(), text, 0, 4);
        assertContext(NEUTRAL, MarkdownMode.getMode(), text, 0, 9);
    }

    @Test
    public void markdownUnclosedBacktickIsLiteral() {
        // No close anywhere: the backticks are literal, not a span.
        assertContext(NEUTRAL, MarkdownMode.getMode(), "a ` not code", 0, 8);
    }

    @Test
    public void markdownApostropheStaysNeutral() {
        // "don't |" — the apostrophe is prose, not a string.
        assertContext(NEUTRAL, MarkdownMode.getMode(), "don't ", 0, 6);
        Decision d = typeAt(MarkdownMode.getMode(), "don't ", 0, 6, '(');
        assertEquals(INSERT_PAIR, d.action());
    }

    @Test
    public void markdownEscapedBacktickIsLiteral() {
        // A backslash-escaped backtick does not open a code span.
        assertContext(NEUTRAL, MarkdownMode.getMode(), "a \\` not code", 0, 8);
    }

    // Java and Lisp: the query agrees with the old behavior where it
    // was already correct.

    @Test
    public void javaLineComment() {
        assertContext(COMMENT, JavaMode.getMode(), "// x ", 0, 4);
    }

    @Test
    public void javaString() {
        assertContext(STRING, JavaMode.getMode(), "\"x\"", 0, 2);
    }

    @Test
    public void javaClosedStringIsNeutral() {
        assertContext(NEUTRAL, JavaMode.getMode(), "\"x\" ", 0, 4);
    }

    @Test
    public void lispLineComment() {
        assertContext(COMMENT, LispMode.getMode(), "; x ", 0, 4);
    }

    @Test
    public void lispString() {
        assertContext(STRING, LispMode.getMode(), "\"x\"", 0, 2);
    }

    @Test
    public void lispBlockComment() {
        assertContext(COMMENT, LispMode.getMode(), "#| block |# x", 0, 5);
        assertContext(NEUTRAL, LispMode.getMode(), "#| block |# x", 0, 11);
    }

    // The plain-text default: the formatter has no query, so the
    // fallback to isInQuote/isInComment still applies.

    @Test
    public void plainTextFallsBackToDefaults() {
        assertContext(STRING, PlainTextMode.getMode(), "\"x", 0, 2);
    }

    // A disabled line (#if 0) is OPAQUE, not COMMENT: pairing has no
    // business inside it. The state is line-based, set by CMode's formatter.

    @Test
    public void disabledLineIsOpaque() {
        // Completed string and closed block comment on a disabled line
        // must not make any position NEUTRAL: the whole line stays OPAQUE.
        String text = "#if 0\nint x = \"s\"; /* c */ (1);\n#endif\n";
        assertContext(OPAQUE, CMode.getMode(), text, 1, 9);
        assertContext(OPAQUE, CMode.getMode(), text, 1, 18);
        assertContext(OPAQUE, CMode.getMode(), text, 1, 24);
    }

    // A line entering inside a multiline HTML comment is COMMENT only
    // until the closing marker is consumed; prose after it pairs normally.
    @Test
    public void markdownResumesAfterMultilineComment() {
        String text = "<!-- open\nstill --> prose (here\n";
        assertContext(COMMENT, MarkdownMode.getMode(), text, 1, 3);
        assertContext(NEUTRAL, MarkdownMode.getMode(), text, 1, 12);
    }

    // The exact boundary: one past a multiline comment's closing marker
    // is prose, not comment.
    @Test
    public void markdownCaretRightAfterMultilineCommentMarker() {
        String text = "<!-- hidden\n-->";
        assertContext(COMMENT, MarkdownMode.getMode(), text, 1, 2);
        // One past the marker: NEUTRAL, and ( pairs.
        assertContext(NEUTRAL, MarkdownMode.getMode(), text, 1, 3);
        Decision d = typeAt(MarkdownMode.getMode(), text, 1, 3, '(');
        assertEquals(INSERT_PAIR, d.action());
    }

    // An unmatched backtick run is literal: a caret between the
    // backticks is NEUTRAL. Falling through to the generic quote scanner
    // would read the apostrophe in "don't" as an open string and inhibit
    // pairing.
    @Test
    public void markdownUnmatchedBackticksAreNeutral() {
        String text = "don't ``";
        assertContext(NEUTRAL, MarkdownMode.getMode(), text, 0, 7);
        Decision d = typeAt(MarkdownMode.getMode(), text, 0, 7, '(');
        assertEquals(INSERT_PAIR, d.action());
    }

    // The empty-quote-pair exception: caret between two quotes of a
    // symmetric pair with NEUTRAL before the left one -- the guard that
    // lets Backspace delete both despite STRING at the caret.

    private boolean emptyPair(Mode mode, String text, int offset) {
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        return ElectricPairs.isEmptyQuotePair(mode, h.buffer(), h.buffer().getLine(0), offset);
    }

    @Test
    public void emptyQuotePairIsRecognized() {
        // "\"|\"" : the caret between the two quotes.
        assertTrue(emptyPair(PlainTextMode.getMode(), "\"\"", 1));
    }

    @Test
    public void emptyQuotePairAfterClosedString() {
        // "a\"\"|\"\"" : the second pair follows a closed string, so
        // the state before its left quote is NEUTRAL.
        assertTrue(emptyPair(PlainTextMode.getMode(), "\"\" \"\"", 4));
    }

    @Test
    public void escapedQuoteIsNotAnEmptyPair() {
        // "\"\\\"|\"" : the left quote is escaped, so no empty pair.
        assertFalse(emptyPair(PlainTextMode.getMode(), "\"\\\"\"", 3));
    }

    @Test
    public void quoteInsideStringIsNotAnEmptyPair() {
        // Both quotes sit inside a longer string: the state before the
        // left one is STRING, not NEUTRAL.
        assertFalse(emptyPair(PlainTextMode.getMode(), "\"a\"\"b\"", 3));
    }

    @Test
    public void quotesInCommentAreNotAnEmptyPair() {
        assertFalse(emptyPair(PythonMode.getMode(), "# \"\"", 3));
    }

    @Test
    public void nonQuotePairIsNotAnEmptyPair() {
        // (|) is a bracket pair: the exception is for quotes only.
        assertFalse(emptyPair(PlainTextMode.getMode(), "()", 1));
    }

    // Parse freshness: a query after an edit that changes the entering
    // context must see the new state, not the pre-edit flags.
    @Test
    public void querySeesFreshParseAfterEdit() {
        if (h != null)
            h.close();
        h = EditorHarness.create("x = 1\nsecond line\n").mode(PythonMode.getMode());
        assertEquals(NEUTRAL, h.buffer().getMode().lexicalContext(h.buffer(), new Position(h.buffer().getLine(1), 3)));
        // Open a triple-quoted string on line 0; line 1 is now body.
        h.buffer().insertString(new Position(h.buffer().getLine(0), 5), " \"\"\"open");
        assertEquals(STRING, h.buffer().getMode().lexicalContext(h.buffer(), new Position(h.buffer().getLine(1), 3)));
    }

    // The query's refresh rewrote line flags downstream of the edit.
    // The insert itself schedules no repaint, so without one in the
    // refresh path the idle parser finds the flags already correct and
    // never repaints the lines this keystroke changed the state of.
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
        assertEquals(STRING, h.buffer().getMode().lexicalContext(h.buffer(), new Position(h.buffer().getLine(1), 3)));
        assertTrue(h.repaintPending());
    }

    // The point and bulk queries are two views of one scan: at every
    // offset the bulk entry must equal the point answer. Drift means the
    // two no longer share the same transitions.
    @Test
    public void pointAndBulkAgreeAtEveryOffset() {
        Mode[] modes = {
            JavaMode.getMode(),
            CMode.getMode(),
            LispMode.getMode(),
            PythonMode.getMode(),
            ShellScriptMode.getMode(),
            MarkdownMode.getMode(),
            CSSMode.getMode() };
        String[] fixtures = {
            "",
            "x",
            "  \t  ",
            "// c",
            "\"s\"",
            "\"a\\\"b\"",
            "/* b */ x",
            "#if 0\nint x=\"s\"; /* c */ (1);\n#endif\n",
            "; c",
            "#| blk |# x",
            "'q'",
            "(a b)",
            "\"unterminated",
            "# c\nx",
            "x = 'a'",
            "x = '''t'''",
            "x = \"\"\"a\\\"b\"\"\"",
            "x = \"a\" # c",
            "echo hi # c",
            "cat <<EOF\nbody\nEOF",
            "x='a' y=\"b\"",
            "a `code` b",
            "`` lit `x`",
            "<!-- c -->",
            "```\nfence\n```",
            "---\nfront\n---\nprose",
            "don't ``",
            "<!-- a\nb --> c",
            "a\\`b",
            "```` ````",
            "x `` y `z` w",
            "a { content: 'x'; color: \"y\"; }",
            "a { content: 'it\\'s'; } /* c */",
            "/* c */ a { b: 'x'; }",
            "a { b: 'x /* not closer */; }" };
        for (Mode mode : modes) {
            for (String text : fixtures) {
                if (h != null)
                    h.close();
                h = EditorHarness.create(text).mode(mode);
                Buffer buffer = h.buffer();
                int lineNo = 0;
                for (Line line = buffer.getFirstLine(); line != null; line = line.next(), lineNo++) {
                    int len = line.length();
                    LexicalContext[] bulk = mode.lexicalContexts(buffer, line, len).orElse(null);
                    if (bulk == null)
                        continue; // a fallback mode with no bulk answer
                    for (int off = 0; off <= len; off++) {
                        LexicalContext point = mode.lexicalContext(buffer, new Position(line, off));
                        assertEquals(
                            bulk[off],
                            point,
                            mode.getDisplayName() + " at " + lineNo + ":" + off + " of [" + text + "]");
                    }
                }
            }
        }
    }
}
