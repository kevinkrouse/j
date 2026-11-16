/*
 * ElectricPairsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.ElectricPairs.Action.INSERT_OPENER;
import static org.armedbear.j.ElectricPairs.Action.INSERT_PAIR;
import static org.armedbear.j.ElectricPairs.Action.PLAIN;
import static org.armedbear.j.ElectricPairs.Action.SUPPRESS;
import static org.armedbear.j.ElectricPairs.Action.TYPE_OVER;
import static org.armedbear.j.ElectricPairs.Action.WRAP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.ElectricPairs.Action;
import org.armedbear.j.ElectricPairs.Decision;
import org.armedbear.j.ElectricPairs.Operation;
import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.python.PythonMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The electric-pairs decision table as a pure function. Insertion,
 * caret sync and undo are the caller's, tested with the wiring below.
 */
public class ElectricPairsTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /** The decision for typing {@code c} at {@code caret} in {@code text} (caret is a | in text). */
    private Decision type(String textWithCaret, char c) {
        return typeIn(PlainTextMode.getMode(), textWithCaret, c, false, false);
    }

    private Decision typeIn(Mode mode, String textWithCaret, char c, boolean selection, boolean suppressed) {
        int caret = textWithCaret.indexOf('|');
        String text = textWithCaret.replace("|", "");
        // Close the previous editor so a test calling this twice leaves none.
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        // The | may sit on a later line: map it to line/offset. Scan
        // only up to the caret; past it the newline count would run on
        // and push seen beyond the caret, giving a negative offset.
        int lineNo = 0;
        int seen = 0;
        for (int i = 0; i < caret; i++) {
            if (text.charAt(i) == '\n') {
                lineNo++;
                seen = i + 1;
            }
        }
        int offset = caret - seen;
        h.cursor(lineNo, offset);
        return ElectricPairs.decide(
            mode,
            h.buffer(),
            new Position(h.buffer().getLine(lineNo), offset),
            Operation.INSERT,
            c,
            selection,
            suppressed);
    }

    private void assertAction(Action expected, Decision actual) {
        assertEquals(expected, actual.action());
    }

    private void assertPair(char opener, char closer, Decision actual) {
        assertEquals(new PairTable.Pair(opener, closer), actual.pair());
    }

    @Test
    public void insertPair() {
        Decision d = type("|", '(');
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void symmetricDelimiterPairs() {
        Decision d = type("|", '"');
        assertAction(INSERT_PAIR, d);
        assertPair('"', '"', d);
    }

    @Test
    public void typeOverMatchesTypedCloser() {
        assertAction(TYPE_OVER, type("(|)", ')'));
        // Typing ( before ) must not step over: the ) ahead is
        // reserved for the enclosing (.
        Decision opener = type("(|)", '(');
        assertAction(INSERT_PAIR, opener);
        assertPair('(', ')', opener);
    }

    @Test
    public void balanceSuppressesCloser() {
        assertAction(SUPPRESS, type("(|foo)", ')'));
    }

    @Test
    public void balanceInsertsOpenerOnly() {
        // Nothing open before the caret: the ) ahead is free to close the new opener.
        Decision d = type("|)", '(');
        assertAction(INSERT_OPENER, d);
        assertPair('(', ')', d);
    }

    @Test
    public void closerReservedByEnclosingFormStillGeneratesCloser() {
        // foo(bar|) + ( : the ) belongs to foo's (, so the new (
        // generates its own closer. The old single-line balance saw the
        // ) at depth zero and wrongly suppressed it.
        Decision d = type("foo(bar|)", '(');
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void closerReservedAcrossLinesStillGeneratesCloser() {
        // The enclosing opener is on a previous line; the ) here is
        // reserved for it, so the new ( generates its own closer.
        Decision d = typeIn(JavaMode.getMode(), "foo(bar\n     |)", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void multiLineCloserAheadOfEnclosingOpenerIsReserved() {
        // (def x\n  (foo\n    |)) : the )) close the two enclosing
        // forms, so with a seed of two the new ( still generates its
        // own closer.
        Decision d = typeIn(JavaMode.getMode(), "(def x\n  (foo\n    |))", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void closerOnLaterLineIsOutOfScanScope() {
        // The ) on the later line is invisible to the line-bounded
        // forward scan, so nothing reserves it and the full pair is
        // inserted.
        Decision d = typeIn(JavaMode.getMode(), "|\n)", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void stringCloserAheadDoesNotReserveAcrossLines() {
        // The ) ahead is inside a string on the previous line: not a
        // closer in code, so the new ( generates its own closer.
        Decision d = typeIn(JavaMode.getMode(), "foo \"x)\"\nbar|", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void escapedDelimiterIsNotStructure() {
        // In Lisp #\( the ( is a character literal, not an enclosing
        // opener: the formatter reports it NEUTRAL, so the seed must
        // exclude it by the escape run. With the escaped ( wrongly
        // counted, the seed is one and the ) ahead is reserved, so the
        // new ( generates a closer and leaves foo()) unbalanced.
        Decision d = typeIn(LispMode.getMode(), "(defparameter c #\\()\nfoo|", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void escapedCloserAheadIsNotWaiting() {
        // The ) ahead is escaped (#\)), a character literal, so it is
        // not a closer waiting to be typed over: the new ( generates
        // its own closer.
        Decision d = typeIn(LispMode.getMode(), "foo| #\\)", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void strayCloserInPrefixDoesNotCancelOpenOpener() {
        // The ) before the ( is stray: forward-reading, it closes
        // nothing, so the ( is genuinely open and the ) ahead is
        // reserved for it. The backward walk must see the stray as
        // debt paid by an opener further back, not as cancelling the
        // ( between it and the caret; the naive counter returned seed
        // zero here and inserted the opener alone, re-stealing the
        // closer.
        assertAction(INSERT_PAIR, type(")(|)", '('));
        assertAction(INSERT_PAIR, type("a) (|)", '('));
        assertAction(INSERT_PAIR, type("))(|)", '('));
    }

    @Test
    public void strayCloserStillPaysForEarlierOpener() {
        // The debt model: the stray ) is paid by the ( before it, so
        // the seed is still one and the ) ahead is reserved.
        assertAction(INSERT_PAIR, type("(a) ) (|)", '('));
    }

    @Test
    public void inhibitAfterWordChar() {
        assertAction(PLAIN, type("a|", '"'));
    }

    @Test
    public void inhibitBeforeWordChar() {
        assertAction(PLAIN, type("(|foo", ')'));
    }

    @Test
    public void javaOpenersBeforeIdentifierStayLiteral() {
        assertAction(PLAIN, typeIn(JavaMode.getMode(), "foo|bar", '(', false, false));
        assertAction(PLAIN, typeIn(JavaMode.getMode(), "int|value", '[', false, false));
    }

    @Test
    public void escapedQuoteIsLiteral() {
        // " \ | "  -> the quote after one backslash is escaped.
        assertAction(PLAIN, type("\"\\|\"", '"'));
        // Odd run of three: still escaped.
        assertAction(PLAIN, type("\"\\\\\\|\"", '"'));
    }

    @Test
    public void escapedBackslashTypesOver() {
        // Two backslashes escape each other; the quote is unescaped
        // and steps over the closer.
        assertAction(TYPE_OVER, type("\"\\\\|\"", '"'));
        // Even run of four: same result.
        assertAction(TYPE_OVER, type("\"\\\\\\\\|\"", '"'));
    }

    @Test
    public void wrapOnlyForPairChars() {
        Decision open = typeIn(PlainTextMode.getMode(), "foo|", '(', true, false);
        assertAction(WRAP, open);
        assertPair('(', ')', open);
        Decision close = typeIn(PlainTextMode.getMode(), "foo|", ')', true, false);
        assertAction(WRAP, close);
        // A selection wrapped by ) still carries the pair opener-first.
        assertPair('(', ')', close);
        Decision quote = typeIn(PlainTextMode.getMode(), "foo|", '"', true, false);
        assertAction(WRAP, quote);
        assertPair('"', '"', quote);
    }

    @Test
    public void nonPairCharOverSelectionReplaces() {
        Decision d = typeIn(PlainTextMode.getMode(), "foo|", 'x', true, false);
        assertAction(PLAIN, d);
    }

    @Test
    public void suppressedIsPlain() {
        assertAction(PLAIN, typeIn(PlainTextMode.getMode(), "|", '(', false, true));
    }

    @Test
    public void lispApostropheNeverPairs() {
        assertAction(PLAIN, typeIn(LispMode.getMode(), "|", '\'', false, false));
    }

    @Test
    public void balanceIgnoresStringCloser() {
        // The ) later on the line is inside a string: not a closer in
        // code, so the opener still gets its own closer.
        Decision d = typeIn(JavaMode.getMode(), "foo(| \"x)", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void balanceIgnoresCommentCloser() {
        // The ) is inside a line comment: plain insert, not suppression.
        Decision d = typeIn(JavaMode.getMode(), "(|x // )", ')', false, false);
        assertAction(PLAIN, d);
    }

    @Test
    public void markupBackslashDoesNotEscapeQuote() {
        // In markup a backslash is literal, so the quote before the
        // caret is unescaped and the typed quote steps over it.
        Decision html = typeIn(HtmlMode.getMode(), "<a x=\"path\\|\"/>", '"', false, false);
        assertAction(TYPE_OVER, html);
        Decision xml = typeIn(XmlMode.getMode(), "<a x=\"path\\|\"/>", '"', false, false);
        assertAction(TYPE_OVER, xml);
    }

    @Test
    public void closerBeforeAnOpenerThatItMatchesIsNotSuppressed() {
        // (|() : the typed ) closes the first ( and the line balances.
        assertAction(PLAIN, type("(|()", ')'));
    }

    @Test
    public void nestingOpenersIsAllowed() {
        // (( is nesting, not an inhibit: the same-delimiter rule
        // applies only to symmetric delimiters.
        Decision d = type("(|", '(');
        assertAction(INSERT_PAIR, d);
    }

    // The wiring: typing through the real command path proves
    // insertNormalChar acts on the decision, on buffer, caret and undo.

    @Test
    public void insertPairWired() {
        h = EditorHarness.create("").mode(PlainTextMode.getMode());
        h.keys("(");
        h.assertText("()\n");
        h.assertCursorAt(0, 1);
        EditCommands.undo(h.editor());
        h.assertText("\n");
    }

    @Test
    public void javaCallPairsAfterIdentifier() {
        h = EditorHarness.create("foo").mode(JavaMode.getMode());
        h.cursor(0, 3).keys("(");
        h.assertText("foo()\n");
        h.assertCursorAt(0, 4);
        EditCommands.undo(h.editor());
        h.assertText("foo\n");
        h.assertCursorAt(0, 3);
    }

    @Test
    public void javaArrayPairsAfterType() {
        h = EditorHarness.create("int").mode(JavaMode.getMode());
        h.cursor(0, 3).keys("[");
        h.assertText("int[]\n");
        h.assertCursorAt(0, 4);
        EditCommands.undo(h.editor());
        h.assertText("int\n");
        h.assertCursorAt(0, 3);
    }

    @Test
    public void caretSyncWired() {
        // The early return bypasses the epilogue, so the helper must
        // sync the displayed caret to sit between the pair.
        h = EditorHarness.create("").mode(PlainTextMode.getMode());
        h.keys("(");
        assertEquals(1, h.editor().getDisplay().getCaretCol());
    }

    @Test
    public void typeOverWired() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        h.keys(")");
        h.assertText("()\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void insertPairInsideExistingPairWired() {
        // The closer ahead is reserved for the enclosing (: typing (
        // generates its own closer, unlike the old single-line balance.
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        h.keys("(");
        h.assertText("(())\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void wrapWired() {
        h = EditorHarness.create("foo").mode(PlainTextMode.getMode());
        h.cursor(0, 3);
        h.editor().setMark(0, 0);
        h.keys("(");
        h.assertText("(foo)\n");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void lispApostropheNeverPairsWired() {
        h = EditorHarness.create("").mode(LispMode.getMode());
        h.keys("'");
        h.assertText("'\n");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void pairUndoIsOneStepWired() {
        h = EditorHarness.create("").mode(PlainTextMode.getMode());
        h.keys("(");
        EditCommands.undo(h.editor());
        h.assertText("\n");
    }

    // Backspace delete-both through the real command path.

    @Test
    public void backspaceDeletesEmptyBracketPair() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        EditCommands.backspace(h.editor());
        h.assertText("\n");
        h.assertCursorAt(0, 0);
    }

    @Test
    public void backspaceDeletesEmptyQuotePair() {
        // The in-string guard must not block the delete here.
        h = EditorHarness.create("\"\"").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        EditCommands.backspace(h.editor());
        h.assertText("\n");
        h.assertCursorAt(0, 0);
    }

    @Test
    public void backspaceEmptyQuotePairOneUndo() {
        h = EditorHarness.create("\"\"").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        EditCommands.backspace(h.editor());
        EditCommands.undo(h.editor());
        h.assertText("\"\"\n");
    }

    @Test
    public void backspaceEmptyPairAfterClosedString() {
        // The second pair follows a closed string on the same line.
        h = EditorHarness.create("\"\" \"\"").mode(PlainTextMode.getMode());
        h.cursor(0, 4);
        EditCommands.backspace(h.editor());
        h.assertText("\"\" \n");
    }

    @Test
    public void backspaceInsideStringIsOrdinary() {
        // A quote inside a longer string: ordinary backspace, not delete-both.
        h = EditorHarness.create("\"ab\"").mode(PlainTextMode.getMode());
        h.cursor(0, 3);
        EditCommands.backspace(h.editor());
        h.assertText("\"a\"\n");
    }

    @Test
    public void backspaceBracketInsideStringIsOrdinary() {
        // (|) inside a string is content: ordinary backspace takes the opener.
        h = EditorHarness.create("\"()\"").mode(PlainTextMode.getMode());
        h.cursor(0, 2);
        EditCommands.backspace(h.editor());
        h.assertText("\")\"\n");
    }

    @Test
    public void backspaceQuotesInCommentIsOrdinary() {
        h = EditorHarness.create("# \"\"").mode(PythonMode.getMode());
        h.cursor(0, 3);
        EditCommands.backspace(h.editor());
        h.assertText("# \"\n");
    }

    @Test
    public void backspaceBetweenTwoClosersIsOrdinary() {
        // )|) is not an empty pair: the left ) is a closer.
        h = EditorHarness.create("foo(bar())").mode(PlainTextMode.getMode());
        h.cursor(0, 9);
        EditCommands.backspace(h.editor());
        h.assertText("foo(bar()\n");
    }

    @Test
    public void backspaceBetweenClosingAndOpeningQuotesIsOrdinary() {
        // "a"|"b" : the quotes end one string and start the next; the
        // left one does not open an empty pair.
        h = EditorHarness.create("\"a\"\"b\"").mode(PlainTextMode.getMode());
        h.cursor(0, 3);
        EditCommands.backspace(h.editor());
        h.assertText("\"a\"b\"\n");
    }

    @Test
    public void enterBetweenBracesWithoutContextIndentKeepsTheOpenersIndent() {
        // Plain text cannot indent by context: interior and closer both
        // match the opener's line, as a plain split would.
        h = EditorHarness.create("    foo() {}").mode(PlainTextMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_OPEN_LINE, true);
        h.cursor(0, 11);
        EditCommands.newlineAndIndent(h.editor());
        assertEquals("    }", h.buffer().getLine(2).getText());
        assertEquals(4, h.editor().getDisplay().getAbsoluteCaretCol());
    }

    @Test
    public void backspaceNonPairIsOrdinary() {
        h = EditorHarness.create("ab").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        EditCommands.backspace(h.editor());
        h.assertText("b\n");
    }

    @Test
    public void backspaceDeleteBothRespectsTheSetting() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS, false);
        h.cursor(0, 1);
        EditCommands.backspace(h.editor());
        // Pairing off: ordinary backspace takes only the opener.
        h.assertText(")\n");
    }

    // Enter between an empty pair. Create-then-indent: both lines
    // exist before the interior is indented, so the closer is not on
    // the interior line when getCorrectIndentation runs.

    @Test
    public void enterBetweenBracesOpensThreeLines() {
        h = EditorHarness.create("{}").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("{\n    \n}\n");
        h.assertCursorAt(1, 4);
    }

    @Test
    public void enterBetweenBracesCloserAtOuterIndent() {
        // The closer must not be pulled to the interior indent.
        h = EditorHarness.create("{}").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        assertEquals("}", h.buffer().getLine(2).getText());
    }

    @Test
    public void enterBetweenBracesOneUndo() {
        h = EditorHarness.create("{}").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        EditCommands.undo(h.editor());
        h.assertText("{}\n");
    }

    @Test
    public void enterBetweenParensOpensThreeLines() {
        // Java's paren indent is alignment-based: closer and interior
        // both align under the first-argument column.
        h = EditorHarness.create("()").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("(\n    \n    )\n");
        h.assertCursorAt(1, 4);
    }

    @Test
    public void enterInsideQuotesIsOrdinary() {
        // A symmetric delimiter does not expand: Enter inside "" is a plain split.
        h = EditorHarness.create("\"\"").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("\"\n\"\n");
    }

    @Test
    public void enterWithSelectionReplacesItWithOneNewline() {
        // (x) with x selected: Enter replaces the selection with one
        // newline. The decision must see the user's selection, not the
        // caret left after deleting it -- otherwise (|) expands to
        // three lines.
        h = EditorHarness.create("(x)").mode(PlainTextMode.getMode());
        h.cursor(0, 2);
        h.editor().setMark(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("(\n)\n");
    }

    @Test
    public void enterBetweenBracesInStringIsOrdinary() {
        // {|} inside a string is content: the ordinary split cuts
        // between the braces as the user typed it.
        h = EditorHarness.create("\"{}\"").mode(JavaMode.getMode());
        h.cursor(0, 2);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("\"{\n}\"\n");
    }

    @Test
    public void enterBetweenBracesRespectsOpenLineSetting() {
        h = EditorHarness.create("{}").mode(JavaMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_OPEN_LINE, false);
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("{\n}\n");
    }

    @Test
    public void enterBetweenBracesRespectsPairingSetting() {
        h = EditorHarness.create("{}").mode(JavaMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS, false);
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("{\n}\n");
    }

    @Test
    public void enterNotBetweenPairIsOrdinary() {
        h = EditorHarness.create("ab").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("a\nb\n");
    }

    // Sub-rule settings: each turns off one behavior, the rest stay on.

    @Test
    public void typeOverOffInsertsTheCloser() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        // Balance off too, so the waiting closer doesn't suppress:
        // this isolates the step-over rule.
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_TYPE_OVER, false);
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_BALANCE, false);
        h.cursor(0, 1);
        h.keys(")");
        h.assertText("())\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void typeOverOffStillBalancesAgainstACloserAhead() {
        h = EditorHarness.create("(foo)").mode(PlainTextMode.getMode());
        // Step-over off, but a closer waits later on the line: balance
        // still suppresses the insert.
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_TYPE_OVER, false);
        h.cursor(0, 1);
        h.keys(")");
        h.assertText("(foo)\n");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void balanceOffGeneratesTheCloserAnyway() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_BALANCE, false);
        h.cursor(0, 1);
        h.keys("(");
        // Balance off: the opener generates its closer despite the one
        // already waiting ahead.
        h.assertText("(())\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void balanceOffInsertsTheCloserWhenOneWaits() {
        h = EditorHarness.create("(foo)").mode(PlainTextMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_BALANCE, false);
        h.cursor(0, 0);
        h.keys(")");
        // Balance off: the typed closer inserts rather than being suppressed.
        h.assertText(")(foo)\n");
        h.assertCursorAt(0, 1);
    }

    @Test
    public void deletePairsOffTakesOnlyTheOpener() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_DELETE_PAIRS, false);
        h.cursor(0, 1);
        EditCommands.backspace(h.editor());
        // The empty pair is not deleted as one: only the opener goes.
        h.assertText(")\n");
    }

    @Test
    public void keyboardMacroReproducesThePair() throws Exception {
        // The macro records the typed character and replays it through
        // insertNormalChar: the pair is re-derived on playback.
        resetMacro();
        h = EditorHarness.create("\n\n").mode(PlainTextMode.getMode());
        h.cursor(0, 0);
        h.editor().executeCommand("startMacro");
        h.keys("(");
        h.assertText("()\n\n");
        h.assertCursorAt(0, 1);
        h.editor().executeCommand("endMacro");
        h.cursor(1, 0);
        // No key event follows to sync the display caret, and insertChar
        // fills to a stale caret column.
        h.editor().moveCaretToDotCol();
        h.editor().executeCommand("playbackMacro");
        h.assertText("()\n()\n");
        h.assertCursorAt(1, 1);
        EditCommands.undo(h.editor());
        h.assertText("()\n\n");
    }

    /**
     * The macro is a static, so a leftover one would make startMacro ask
     * to overwrite it.
     */
    private static void resetMacro() throws Exception {
        final java.lang.reflect.Field field = Macro.class.getDeclaredField("macro");
        field.setAccessible(true);
        field.set(null, null);
        Editor.setRecordingMacro(false);
    }
}
