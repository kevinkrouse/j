/*
 * PairTableTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.Test;

/** PairTable: opener and closer lookups over a mode's pairs string. */
public class PairTableTest {
    private static final String DEFAULT = "()[]{}\"\"";

    @Test
    public void openerMapsToItsCloser() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertEquals(')', t.closerFor('('));
        assertEquals(']', t.closerFor('['));
        assertEquals('}', t.closerFor('{'));
        assertEquals('"', t.closerFor('"'));
    }

    @Test
    public void nonOpenerMapsToNothing() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertEquals(0, t.closerFor(')'));
        assertEquals(0, t.closerFor('a'));
        assertEquals(0, t.closerFor(' '));
    }

    @Test
    public void asymmetricDelimitersAreOnlyOneRole() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertTrue(t.isOpener('('));
        assertFalse(t.isCloser('('));
        assertTrue(t.isCloser(')'));
        assertFalse(t.isOpener(')'));
    }

    @Test
    public void symmetricDelimiterIsBothRoles() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertTrue(t.isOpener('"'));
        assertTrue(t.isCloser('"'));
    }

    @Test
    public void oddLengthPairsStringIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PairTable.forPairs("()("));
        assertThrows(IllegalArgumentException.class, () -> PairTable.forPairs("\""));
    }

    @Test
    public void emptyPairsStringIsAnEmptyTable() {
        PairTable t = PairTable.forPairs("");
        assertFalse(t.isOpener('('));
        assertFalse(t.isCloser(')'));
        assertEquals(0, t.closerFor('('));
    }

    @Test
    public void tablesAreSharedByTheirString() {
        assertSame(PairTable.forPairs(DEFAULT), PairTable.forPairs(DEFAULT));
    }

    @Test
    public void eitherEndResolvesToTheSamePair() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertEquals(new PairTable.Pair('(', ')'), t.pairFor('('));
        assertEquals(new PairTable.Pair('(', ')'), t.pairFor(')'));
        assertEquals(new PairTable.Pair('[', ']'), t.pairFor('['));
        assertEquals(new PairTable.Pair('[', ']'), t.pairFor(']'));
        assertEquals(new PairTable.Pair('{', '}'), t.pairFor('{'));
        assertEquals(new PairTable.Pair('{', '}'), t.pairFor('}'));
    }

    @Test
    public void symmetricDelimiterResolvesToItself() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertEquals(new PairTable.Pair('"', '"'), t.pairFor('"'));
    }

    @Test
    public void unpairedCharResolvesToNothing() {
        PairTable t = PairTable.forPairs(DEFAULT);
        assertNull(t.pairFor('a'));
        assertNull(t.pairFor('\''));
        assertNull(t.pairFor('<'));
    }

    @Test
    public void defaultModePairsBracketsAndDoubleQuote() {
        PairTable t = PlainTextMode.getMode().getPairTable();
        assertTrue(t.isOpener('('));
        assertTrue(t.isOpener('['));
        assertTrue(t.isOpener('{'));
        assertTrue(t.isOpener('"'));
        assertFalse(t.isOpener('\''));
        assertFalse(t.isCloser('\''));
    }

    @Test
    public void lispDropsTheApostrophe() {
        PairTable t = LispMode.getMode().getPairTable();
        assertTrue(t.isOpener('('));
        assertTrue(t.isOpener('"'));
        assertFalse(t.isOpener('\''));
        assertFalse(t.isCloser('\''));
    }

    @Test
    public void htmlPairsOnlyTheDoubleQuote() {
        PairTable t = HtmlMode.getMode().getPairTable();
        assertTrue(t.isOpener('"'));
        assertTrue(t.isCloser('"'));
        assertFalse(t.isOpener('('));
        assertFalse(t.isOpener('<'));
        assertFalse(t.isCloser('>'));
    }

    @Test
    public void xmlPairsOnlyTheDoubleQuote() {
        PairTable t = XmlMode.getMode().getPairTable();
        assertTrue(t.isOpener('"'));
        assertTrue(t.isCloser('"'));
        assertFalse(t.isOpener('('));
        assertFalse(t.isOpener('<'));
        assertFalse(t.isCloser('>'));
    }
}
