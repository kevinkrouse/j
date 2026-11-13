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
 * The electric-pairs decision table, tested as a pure function: text
 * and a caret position go in, a {@link Decision} comes out. The
 * insertion itself, the caret sync and the undo step are the caller's
 * and are tested with the wiring.
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
        // Each call makes a new editor; close the one before it so a
        // test that calls this twice leaves no editor registered.
        if (h != null)
            h.close();
        h = EditorHarness.create(text).mode(mode);
        h.cursor(0, caret);
        return ElectricPairs.decide(
            mode,
            h.buffer(),
            new Position(h.buffer().getLine(0), caret),
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
        // Typing ( before ) must not skip the ).
        Decision opener = type("(|)", '(');
        assertAction(INSERT_OPENER, opener);
        assertPair('(', ')', opener);
    }

    @Test
    public void balanceSuppressesCloser() {
        assertAction(SUPPRESS, type("(|foo)", ')'));
    }

    @Test
    public void balanceInsertsOpenerOnly() {
        Decision d = type("(|)", '(');
        assertAction(INSERT_OPENER, d);
        assertPair('(', ')', d);
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
        // Three backslashes: still an odd run, still escaped.
        assertAction(PLAIN, type("\"\\\\\\|\"", '"'));
    }

    @Test
    public void escapedBackslashTypesOver() {
        // Two backslashes escape each other; the quote is not escaped
        // and steps over the closer.
        assertAction(TYPE_OVER, type("\"\\\\|\"", '"'));
        // Four backslashes: even run, same result.
        assertAction(TYPE_OVER, type("\"\\\\\\\\|\"", '"'));
    }

    @Test
    public void wrapOnlyForPairChars() {
        Decision open = typeIn(PlainTextMode.getMode(), "foo|", '(', true, false);
        assertAction(WRAP, open);
        assertPair('(', ')', open);
        Decision close = typeIn(PlainTextMode.getMode(), "foo|", ')', true, false);
        assertAction(WRAP, close);
        // A selection wrapped by ) carries the pair opener-first, not
        // ) as the opening character.
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
        // The ) later on the line is inside a string, so it is not a
        // closer waiting in code: the opener still gets its own closer.
        Decision d = typeIn(JavaMode.getMode(), "foo(| \"x)", '(', false, false);
        assertAction(INSERT_PAIR, d);
        assertPair('(', ')', d);
    }

    @Test
    public void balanceIgnoresCommentCloser() {
        // The ) is inside a line comment, so typing the closer is a
        // plain insert, not a suppression.
        Decision d = typeIn(JavaMode.getMode(), "(|x // )", ')', false, false);
        assertAction(PLAIN, d);
    }

    @Test
    public void markupBackslashDoesNotEscapeQuote() {
        // In markup a backslash is literal, so the quote before the
        // caret is not escaped and the typed quote steps over it.
        Decision html = typeIn(HtmlMode.getMode(), "<a x=\"path\\|\"/>", '"', false, false);
        assertAction(TYPE_OVER, html);
        Decision xml = typeIn(XmlMode.getMode(), "<a x=\"path\\|\"/>", '"', false, false);
        assertAction(TYPE_OVER, xml);
    }

    @Test
    public void closerBeforeAnOpenerThatItMatchesIsNotSuppressed() {
        // (|() : the ( at the caret matches the ) after it, so the
        // typed ) closes the first ( and the line balances.
        assertAction(PLAIN, type("(|()", ')'));
    }

    @Test
    public void nestingOpenersIsAllowed() {
        // (( is nesting, not an inhibit: the same-delimiter rule is
        // only for symmetric delimiters.
        Decision d = type("(|", '(');
        assertAction(INSERT_PAIR, d);
    }

    // The wiring: typing through the real command path, asserting the
    // buffer, the caret and the undo step. The pure-function tests
    // above prove the decision; these prove insertNormalChar acts on it.

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
        // The displayed caret sits between the pair, not after the
        // closer: the early return bypasses the epilogue, so the
        // helper must sync it.
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
    public void insertOpenerWired() {
        // The caret sits between a pair, so a closer already waits
        // ahead and the opener inserts alone.
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        h.keys("(");
        h.assertText("(()\n");
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
        // One undo takes the whole pair back; nothing is left.
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
        // A quote inside a longer string: ordinary single-character
        // backspace, not delete-both.
        h = EditorHarness.create("\"ab\"").mode(PlainTextMode.getMode());
        h.cursor(0, 3);
        EditCommands.backspace(h.editor());
        h.assertText("\"a\"\n");
    }

    @Test
    public void backspaceBracketInsideStringIsOrdinary() {
        // (|) inside a string is content, not an empty pair: the
        // ordinary single-character backspace takes the opener.
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
        // "a"|"b" : the quotes either side end one string and start
        // the next; the left one does not open an empty pair.
        h = EditorHarness.create("\"a\"\"b\"").mode(PlainTextMode.getMode());
        h.cursor(0, 3);
        EditCommands.backspace(h.editor());
        h.assertText("\"a\"b\"\n");
    }

    @Test
    public void enterBetweenBracesWithoutContextIndentKeepsTheOpenersIndent() {
        // Plain text cannot indent by context: the interior and the
        // closer both match the opener's line, as a plain split would.
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
        // Pairing off: the ordinary single-character backspace takes
        // the opener only.
        h.assertText(")\n");
    }

    // Enter between an empty pair. The create-then-indent ordering:
    // both lines exist before the interior is indented, so the closer
    // is not on the interior line when getCorrectIndentation runs.

    @Test
    public void enterBetweenBracesOpensThreeLines() {
        h = EditorHarness.create("{}").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("{\n    \n}\n");
        // Caret at the end of the indented interior line.
        h.assertCursorAt(1, 4);
    }

    @Test
    public void enterBetweenBracesCloserAtOuterIndent() {
        // The closer must not be pulled to the interior indent: it
        // sits at column 0 on its own line.
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
        // Java's paren indent is alignment-based: the closer aligns
        // under the first-argument column, and the interior body
        // indent matches it.
        h = EditorHarness.create("()").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("(\n    \n    )\n");
        h.assertCursorAt(1, 4);
    }

    @Test
    public void enterInsideQuotesIsOrdinary() {
        // A symmetric delimiter does not expand: Enter inside "" is
        // a plain split.
        h = EditorHarness.create("\"\"").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("\"\n\"\n");
    }

    @Test
    public void enterWithSelectionReplacesItWithOneNewline() {
        // (x) with x selected: Enter replaces the selection with a
        // single newline. The decision must see the selection the user
        // had, not the caret left after deleting it -- otherwise the
        // deleted text leaves (|) and Enter wrongly expands the pair
        // into three lines.
        h = EditorHarness.create("(x)").mode(PlainTextMode.getMode());
        h.cursor(0, 2);
        h.editor().setMark(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("(\n)\n");
    }

    @Test
    public void enterBetweenBracesInStringIsOrdinary() {
        // {|} inside a string is content, not a structure to open:
        // the ordinary split cuts between the braces, leaving the
        // string broken across the lines as the user typed it.
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
        // The ordinary split: the closer stays on the line below.
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
        // No pair around the caret: ordinary newlineAndIndent.
        h = EditorHarness.create("ab").mode(JavaMode.getMode());
        h.cursor(0, 1);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("a\nb\n");
    }

    // The sub-rule settings: each turns off one behavior while the rest
    // of pairing stays on.

    @Test
    public void typeOverOffInsertsTheCloser() {
        h = EditorHarness.create("()").mode(PlainTextMode.getMode());
        // Balance is off too, so the closer that waits ahead does not
        // suppress the insert; this isolates the step-over rule.
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_TYPE_OVER, false);
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_BALANCE, false);
        h.cursor(0, 1);
        h.keys(")");
        // No step-over: the typed ) inserts, giving ()).
        h.assertText("())\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void typeOverOffStillBalancesAgainstACloserAhead() {
        h = EditorHarness.create("(foo)").mode(PlainTextMode.getMode());
        // Step-over is off, but a different closer waits later on the
        // line, so balance still suppresses the insert.
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
        // Balance off: the opener generates its closer even though a
        // closer already waits ahead.
        h.assertText("(())\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void balanceOffInsertsTheCloserWhenOneWaits() {
        h = EditorHarness.create("(foo)").mode(PlainTextMode.getMode());
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_BALANCE, false);
        h.cursor(0, 0);
        h.keys(")");
        // Balance off: the typed closer inserts rather than being
        // suppressed.
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
        // j's keyboard macro records the typed character and replays it
        // through insertNormalChar, so the pair is re-derived on playback
        // and one undo takes the replayed pair away.
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
