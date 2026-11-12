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

import static org.armedbear.j.ElectricPairs.Action.DELETE_BOTH;
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
    public void backspaceAndEnterArePlainUntilWired() {
        // The structural rules for BACKSPACE and ENTER arrive with
        // their call sites; the table returns PLAIN for them now.
        h = EditorHarness.create("(|)").mode(PlainTextMode.getMode());
        h.cursor(0, 1);
        assertEquals(
            PLAIN,
            ElectricPairs
                    .decide(
                        PlainTextMode.getMode(),
                        h.buffer(),
                        new Position(h.buffer().getLine(0), 1),
                        Operation.BACKSPACE,
                        '\0',
                        false,
                        false)
                    .action());
        assertEquals(
            PLAIN,
            ElectricPairs
                    .decide(
                        PlainTextMode.getMode(),
                        h.buffer(),
                        new Position(h.buffer().getLine(0), 1),
                        Operation.ENTER,
                        '\0',
                        false,
                        false)
                    .action());
    }

    @Test
    public void nestingOpenersIsAllowed() {
        // (( is nesting, not an inhibit: the same-delimiter rule is
        // only for symmetric delimiters.
        Decision d = type("(|", '(');
        assertAction(INSERT_PAIR, d);
    }
}
