/*
 * VimLiteralInsertTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.armedbear.j.Block;
import org.armedbear.j.Buffer;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.Registers;
import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Literal insertion: text that arrives from a block-insert session or a
 * CTRL-R register must not trigger electric pairing, the suppression
 * must nest, and the repeat must carry the text as it was captured --
 * never re-paired, never re-read from the register.
 */
public class VimLiteralInsertTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text) {
        h = EditorHarness.create(text).vim();
        return h;
    }

    private boolean suppressed() {
        return h.editor().isPairingSuppressed();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        final Field f = VimState.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ------------------------------------------------------------ 23

    @Test
    public void blockInsertCopiesLiteralTextWithoutPairing() {
        vim("abc\ndef\nghi\n").cursor(0, 0).keys("<C-v>2jI(");
        // The opener typed on the first line stays literal: no closer.
        h.assertText("(abc\ndef\nghi\n");
        h.keys("<Esc>");
        h.assertText("(abc\n(def\n(ghi\n");
        assertFalse(suppressed(), "the session released its suppression");
    }

    @Test
    public void arrowCancelsBlockReplicationAndPairingReturns() {
        vim("abc\ndef\nghi\n\n").cursor(0, 0).keys("<C-v>2jI(");
        h.assertText("(abc\ndef\nghi\n\n");
        assertTrue(suppressed(), "the block session holds it");
        // An arrow cancels the replication and releases the suppression.
        h.keys("<Left><Esc>");
        assertFalse(suppressed());
        assertEquals("(abc\ndef\nghi\n\n", h.text(), "the cancel never copies");
        // Ordinary typing pairs again.
        h.cursor(3, 0).keys("i(");
        h.assertText("(abc\ndef\nghi\n()\n");
    }

    @Test
    public void bufferSwitchReleasesSuppressionAndPairingWorksInOtherBuffer() {
        vim("abc\ndef\n").cursor(0, 0).keys("<C-v>1jI(");
        assertTrue(suppressed());
        // Switch buffers without Escape: the old session is released.
        final Buffer first = h.buffer();
        final EditorHarness other = EditorHarness.create("second\n\n").vim();
        try {
            h.editor().deactivate();
            assertFalse(suppressed(), "leaving the buffer released it");
            h.showBuffer(other.buffer());
            h.editor().setDot(other.buffer().getLine(1), 0);
            h.keys("i(");
            assertEquals("second\n()\n", other.text(), "pairing works in the other buffer");
        }
        finally {
            h.showBuffer(first);
            h.editor().setDot(first.getFirstLine(), 0);
            other.close();
        }
    }

    @Test
    public void suppressionDepthReturnsToZeroOnEveryExit() {
        // Normal completion.
        vim("ab\ncd\n").cursor(0, 0).keys("<C-v>1jIX<Esc>");
        assertFalse(suppressed());
        h.assertText("Xab\nXcd\n");
        // Empty insertion: I then Escape, nothing typed.
        vim("ab\ncd\n").cursor(0, 0).keys("<C-v>1jI<Esc>");
        assertFalse(suppressed());
        h.assertText("ab\ncd\n");
        // Cancellation by arrow: the text stays on the first line only.
        vim("ab\ncd\n").cursor(0, 0).keys("<C-v>1jIX<Left><Esc>");
        assertFalse(suppressed());
        h.assertText("Xab\ncd\n");
        // Repeated cleanup is harmless.
        h.vimState().releaseBlockInsert(h.editor());
        h.vimState().releaseBlockInsert(h.editor());
        assertFalse(suppressed());
    }

    @Test
    public void setupFailureOpensNoSuppression() {
        vim("ab\ncd\n\n");
        final Block block =
                new Block(h.buffer(), h.buffer().getFirstLine(), h.buffer().getFirstLine().next(), 0, 1, false);
        h.editor().setDot(null);
        h.vimState().beginBlockInsert(h.editor(), block, false, null);
        assertFalse(suppressed(), "a session that never began holds nothing");
        h.cursor(2, 0);
        h.keys("i(");
        h.assertText("ab\ncd\n()\n");
    }

    @Test
    public void copyFailureStillReleasesSuppression() throws Exception {
        vim("ab\ncd\n\n").cursor(0, 0).keys("<C-v>1jI");
        assertTrue(suppressed());
        // Inject a copy failure: the first line's text throws on substring.
        final Line boom = (Line) Proxy
                .newProxyInstance(Line.class.getClassLoader(), new Class<?>[] { Line.class }, (proxy, method, args) -> {
                    throw new RuntimeException("injected copy failure");
                });
        setField(h.vimState(), "blockInsertLine", boom);
        h.editor().setDot(boom, 2);
        assertThrows(RuntimeException.class, () -> h.vimState().finishBlockInsert(h.editor()));
        assertFalse(suppressed(), "the finally released it despite the throw");
        // And the editor still works: pairing is live again. Move the dot
        // off the poisoned line before Escape touches it.
        h.editor().setDot(h.buffer().getLine(2), 0);
        h.keys("<Esc>");
        h.cursor(2, 0);
        h.keys("i(");
        h.assertText("ab\ncd\n()\n");
    }

    // ------------------------------------------------------------ 24

    @Test
    public void nestedSuppressionKeepsTheBlockHeld() {
        vim("ab\nab\nab\n");
        Registers.setText("a", " ");
        h.cursor(0, 0).keys("<C-v>2j$A");
        assertTrue(suppressed(), "the block session holds it");
        h.keys("<C-r>a");
        // The inner run closed; the block's suppression survives.
        assertTrue(suppressed(), "the block's suppression outlives the register run");
        h.assertText("ab \nab\nab\n");
        h.keys("(");
        // Whitespace before the caret and no following word: pairing
        // would fire if the inner scope had cleared the outer one.
        h.assertText("ab (\nab\nab\n");
        h.keys("<Esc>");
        assertFalse(suppressed());
        h.assertText("ab (\nab (\nab (\n");
    }

    @Test
    public void sameSpaceAndOpenerOutsideABlockPairs() {
        vim("\n");
        Registers.setText("a", " ");
        h.cursor(0, 0).keys("i<C-r>a(");
        h.assertText(" ()\n");
        h.assertCursorAt(0, 2);
        h.keys("<Esc>");
    }

    // ------------------------------------------------------------ 25

    @Test
    public void ctrlRInsertsLiteralOpenerAndRepeatsIt() {
        vim("\n\n");
        Registers.setText("a", "(");
        h.cursor(0, 0).keys("i<C-r>a");
        h.assertText("(\n\n");
        h.assertCursorAt(0, 1);
        h.keys("<Esc>");
        h.cursor(1, 0).keys(".");
        h.assertText("(\n(\n");
    }

    // ------------------------------------------------------------ 26

    @Test
    public void repeatCarriesTheSnapshotNotTheRegister() {
        vim("\n\n");
        Registers.setText("a", "(");
        h.cursor(0, 0).keys("i<C-r>a<Esc>");
        h.assertText("(\n\n");
        Registers.setText("a", ")");
        h.cursor(1, 0).keys(".");
        assertEquals("(\n(\n", h.text(), "the text recorded at insertion time, not the register's now");
    }

    // ------------------------------------------------------------ 27

    @Test
    public void ctrlRNewlineIndentsLikeEnter() {
        vim("class A {\n    void f() {\n    }\n}\n");
        h.mode(JavaMode.getMode());
        Registers.setText("a", "a\nb");
        h.cursor(1, 17).keys("A<C-r>a<Esc>");
        assertEquals(
            "class A {\n    void f() {a\n        b\n    }\n}\n",
            h.text(),
            "the literal newline indents as Enter does");
        // The repeat goes through the same live loop: the new line is
        // indented again, which a raw insert could not do.
        h.cursor(2, 0).keys(".");
        assertEquals("class A {\n    void f() {a\n        ba\n        b\n    }\n}\n", h.text());
    }

    @Test
    public void countedInsertRepeatsTheLiteralRunSuppressed() {
        vim("\n");
        Registers.setText("a", "(");
        h.cursor(0, 0).keys("2i<C-r>a<Esc>");
        assertEquals("((\n", h.text(), "every repetition keeps the text and the suppression");
    }
}
