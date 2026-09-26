/*
 * VimM13Test.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.SwingUtilities;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.junit.After;
import org.junit.Test;

/**
 * CTRL-F and CTRL-B, insert-mode CTRL-T and CTRL-D, :w and :wq, and CTRL-^.
 * Each is one of j's own commands underneath; the expectations are nvim's.
 */
public class VimM13Test
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text, int line, int offset)
    {
        h = EditorHarness.create().vim();
        h.value(text).cursor(line, offset);
        return h;
    }

    private static String lines(int n)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++)
            sb.append("  line").append(i).append(i < n ? "\n" : "");
        return sb.toString();
    }

    private int top()
    {
        return h.editor().getDisplay().getTopLine().lineNumber();
    }

    private int rows()
    {
        return h.editor().getDisplay().getRows();
    }

    private void topAt(int line)
    {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < line; i++)
            l = l.next();
        h.editor().getDisplay().setTopLine(l);
    }

    // ------------------------------------------------------ CTRL-F, CTRL-B

    @Test
    public void ctrlFKeepsTwoLinesAndPutsTheCaretOnTheNewTop()
    {
        // nvim, eleven rows: top 1 -> 10, the caret to the new top line.
        vim(lines(200), 2, 5);
        topAt(0);
        final int step = rows() - 2;
        h.keys("<C-f>");
        assertEquals(step, top());
        assertEquals("the caret is on the new top line", step, h.lineNumber());
        assertEquals("and keeps its column: nvim defaults nostartofline",
                     5, h.offset());
    }

    @Test
    public void ctrlFTakesACount()
    {
        vim(lines(200), 0, 0);
        topAt(0);
        h.keys("2<C-f>");
        assertEquals(2 * (rows() - 2), top());
    }

    @Test
    public void ctrlBScrollsBackAndPutsTheCaretOnTheNewBottom()
    {
        vim(lines(200), 0, 0);
        final int step = rows() - 2;
        topAt(3 * step);
        h.cursor(3 * step, 0);
        h.keys("<C-b>");
        assertEquals(2 * step, top());
        assertEquals("the caret is on the new bottom line",
                     2 * step + rows() - 1, h.lineNumber());
    }

    @Test
    public void ctrlBAtTheTopDoesNothing()
    {
        vim(lines(200), 0, 0);
        topAt(0);
        h.keys("<C-b>");
        assertEquals(0, top());
        assertEquals(0, h.lineNumber());
    }

    @Test
    public void ctrlFWithTheLastLineShowingPutsItAtTheTop()
    {
        // nvim: with line 100 already on screen, CTRL-F makes it the top.
        vim(lines(100), 99, 0);
        topAt(100 - rows());
        h.keys("<C-f>");
        assertEquals(99, top());
        assertEquals(99, h.lineNumber());
    }

    @Test
    public void plainPageDownIsUnchanged()
    {
        // The vim argument is a choice, not a change: j's own pageDown still
        // keeps one line of overlap and the caret's row.
        vim(lines(200), 2, 0);
        topAt(0);
        h.editor().pageDown();
        assertEquals(rows() - 1, top());
        assertEquals("the caret keeps its row", rows() - 1 + 2, h.lineNumber());
    }

    // ---------------------------------------------------- CTRL-T, CTRL-D

    @Test
    public void ctrlTAndCtrlDRoundToAMultipleOfTheShiftwidth()
    {
        // nvim, shiftwidth 4: 3 -> 4 going right and 7 -> 4 going left,
        // where >> would make 3 into 7.
        vim("   ab", 0, 4);
        h.buffer().setIndentSize(4);
        h.keys("i<C-t><Esc>");
        assertEquals("    ab", h.value());
        h.close();
        vim("       ab", 0, 8);
        h.buffer().setIndentSize(4);
        h.keys("i<C-d><Esc>");
        assertEquals("    ab", h.value());
    }

    @Test
    public void theCaretStaysWithTheText()
    {
        // nvim: "    ab" with the caret before b, CTRL-T then X -> "        aXb",
        // and from inside the indent the caret moves by the same amount.
        vim("    ab", 0, 5);
        h.buffer().setIndentSize(4);
        h.keys("i<C-t>X<Esc>");
        assertEquals("        aXb", h.value());
        h.close();
        vim("    ab", 0, 1);
        h.buffer().setIndentSize(4);
        h.keys("i<C-t>X<Esc>");
        assertEquals("     X   ab", h.value());
    }

    @Test
    public void ctrlTIndentsAnEmptyLineAndCtrlDStopsAtNone()
    {
        vim("", 0, 0);
        h.buffer().setIndentSize(4);
        h.keys("i<C-t>X<Esc>");
        assertEquals("    X", h.value());
        h.close();
        vim("ab", 0, 0);
        h.buffer().setIndentSize(4);
        h.keys("i<C-d>X<Esc>");
        assertEquals("Xab", h.value());
    }

    @Test
    public void ctrlDInInsertModeIsNotJsDir()
    {
        // j binds Ctrl-D to dir. In the middle of typing it has to be the
        // dedent, not a directory buffer opening.
        vim("    ab", 0, 4);
        h.buffer().setIndentSize(4);
        h.keys("i<C-d>");
        assertEquals("ab", h.value());
        assertEquals("still typing", "INSERT",
                     h.vimModeIndicator());
    }

    @Test
    public void dotRepeatsAnInsertThatShiftedTheLine()
    {
        vim("ab\ncd", 0, 0);
        h.buffer().setIndentSize(4);
        h.keys("i<C-t>X<Esc>");
        assertEquals("    Xab\ncd", h.value());
        h.keys("j0.");
        assertEquals("    Xab\n    Xcd", h.value());
    }

    // --------------------------------------------------------- :w and :wq

    /**
     * A file's contents, less a trailing newline: j's save keeps whatever
     * final newline the buffer had, and the harness builds its buffer with
     * none, where vim would always write one. That is j's policy on a real
     * file opened from disk too, and not what these tests are about.
     */
    private static String read(Path file) throws java.io.IOException
    {
        final String s = new String(Files.readAllBytes(file),
                                    StandardCharsets.UTF_8);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private static void onEdt(Runnable r) throws Exception
    {
        // Buffer.save insists on the event thread, which is where an ex
        // command runs in the editor.
        SwingUtilities.invokeAndWait(r);
    }

    /** The file the harness buffer is backed by, as a path. */
    private Path ownFile()
    {
        return java.nio.file.Paths.get(h.buffer().getFile().getAbsolutePath());
    }

    @Test
    public void wWritesTheBufferToItsOwnFile() throws Exception
    {
        vim("hello", 0, 0);
        h.keys("ccbye<Esc>");
        onEdt(() -> h.exCommand("w"));
        assertEquals("bye", read(ownFile()));
        assertFalse(h.buffer().isModified());
    }

    @Test
    public void wToAnotherFileIsACopyAndTheBufferKeepsItsName()
        throws Exception
    {
        // vim: :w FILE on a buffer that has a name writes a copy and leaves
        // the name alone. (On one with no name it names it; that path opens
        // j's Save As machinery and is checked on screen.)
        final Path copy = Files.createTempDirectory("vimw").resolve("b.txt");
        vim("one", 0, 0);
        final String name = h.buffer().getFile().getName();
        onEdt(() -> h.exCommand("w " + copy));
        assertEquals("one", read(copy));
        assertEquals(name, h.buffer().getFile().getName());
    }

    @Test
    public void wWillNotOverwriteAnotherFileWithoutABang() throws Exception
    {
        final Path dir = Files.createTempDirectory("vimw");
        final Path other = dir.resolve("taken.txt");
        Files.write(other, "keep\n".getBytes(StandardCharsets.UTF_8));
        vim("new", 0, 0);
        onEdt(() -> h.exCommand("w " + other));
        assertEquals("E13: File exists (add ! to override)", h.status());
        assertEquals("keep", read(other));
        onEdt(() -> h.exCommand("w! " + other));
        assertEquals("new", read(other));
    }

    @Test
    public void wqWritesFirst() throws Exception
    {
        // Closing the window needs a frame; the write does not, and it has
        // to come first, since vim will not quit after a write that failed.
        vim("data", 0, 0);
        h.keys("ccmore<Esc>");
        onEdt(() -> h.exCommand("wq"));
        assertEquals("more", read(ownFile()));
    }

    @Test
    public void writingPartOfABufferIsRefusedRatherThanIgnored()
    {
        vim("a\nb", 0, 0).exCommand("1w /tmp/nowhere");
        assertEquals("Writing part of a buffer is not supported",
                     h.status());
    }

    // ---------------------------------------------------------------- CTRL-^

    /**
     * Takes every other buffer out of j's global list. Some test elsewhere
     * leaves one behind; the alternate buffer is chosen from that list, so
     * without this the answer depends on which tests ran first.
     */
    private void onlyThisBuffer()
    {
        final java.util.List<org.armedbear.j.Buffer> others =
            new java.util.ArrayList<org.armedbear.j.Buffer>();
        for (org.armedbear.j.BufferIterator it =
                 new org.armedbear.j.BufferIterator(); it.hasNext();) {
            final org.armedbear.j.Buffer b = it.next();
            if (b != h.buffer())
                others.add(b);
        }
        for (org.armedbear.j.Buffer b : others)
            org.armedbear.j.Editor.getBufferList().remove(b);
    }

    @Test
    public void ctrlCaretWithNoOtherBufferSaysSo()
    {
        vim("a", 0, 0);
        onlyThisBuffer();
        h.keys("<C-^>");
        assertEquals("E23: No alternate file", h.status());
    }

    @Test
    public void ctrlCaretGoesToTheBufferUsedMostRecently()
    {
        // Not the one before this in the list -- that is j's prevBuffer
        // without the argument. The alternate file is the last one used.
        final EditorHarness older = EditorHarness.create("older");
        final EditorHarness recent = EditorHarness.create("recent");
        try {
            vim("here", 0, 0);
            older.buffer().setLastActivated(100);
            recent.buffer().setLastActivated(200);
            h.buffer().setLastActivated(300);
            h.keys("<C-^>");
            assertTrue(h.editor().getBuffer() == recent.buffer());
        }
        finally {
            older.close();
            recent.close();
        }
    }

    // ---------------------------------------------------------- autoindent

    @Test
    public void oTypedThenEscapeKeepsTheIndent()
    {
        vim("  abc", 0, 3);
        h.keys("ox<Esc>");
        assertEquals("  abc\n  x", h.value());
        h.assertCursorAt(1, 2);
    }

    @Test
    public void oEscapeWithNothingTypedLeavesAnEmptyLine()
    {
        vim("  abc", 0, 3);
        h.keys("o<Esc>");
        assertEquals("  abc\n", h.value());
        h.assertCursorAt(1, 0);
    }

    @Test
    public void capitalOEscapeLeavesAnEmptyLine()
    {
        vim("  abc", 0, 3);
        h.keys("O<Esc>");
        assertEquals("\n  abc", h.value());
        h.assertCursorAt(0, 0);
    }

    @Test
    public void capitalOTakesTheIndentOfTheLineBelowAndLeavesItAlone()
    {
        vim("  abc", 0, 3);
        h.keys("Ox<Esc>");
        assertEquals("  x\n  abc", h.value());
        h.assertCursorAt(0, 2);
    }

    @Test
    public void ccKeepsTheIndent()
    {
        vim("  abc", 0, 3);
        h.keys("ccx<Esc>");
        assertEquals("  x", h.value());
        h.assertCursorAt(0, 2);
    }

    @Test
    public void ccEscapeLeavesAnEmptyLine()
    {
        vim("a\n  abc", 1, 3);
        h.keys("cc<Esc>");
        assertEquals("a\n", h.value());
    }

    @Test
    public void capitalSKeepsTheIndentOnceSomethingIsTyped()
    {
        vim("a\n  abc", 1, 3);
        h.keys("Sx<Esc>");
        assertEquals("a\n  x", h.value());
    }

    @Test
    public void capitalSEscapeLeavesAnEmptyLine()
    {
        vim("a\n  abc", 1, 3);
        h.keys("S<Esc>");
        assertEquals("a\n", h.value());
    }

    @Test
    public void oEscapeThenUndoRemovesTheLine()
    {
        vim("  abc", 0, 3);
        h.keys("o<Esc>u");
        assertEquals("  abc", h.value());
    }
}
