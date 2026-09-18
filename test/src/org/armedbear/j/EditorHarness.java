/*
 * EditorHarness.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.util.List;

import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.vim.KeyNotation;

/**
 * A running editor with no window, for tests that need to press keys.
 *
 * Keys go through the real {@link Dispatcher} and the real
 * {@link Editor#handleJEvent}, not a reimplementation of them, so what a test
 * exercises is what a user gets -- including the parts that are easy to get
 * wrong, like the self-insert fallback living below {@code handleJEvent} and
 * the {@code ignoreKeyTyped} bookkeeping that keeps one physical keystroke
 * from being handled twice.
 *
 * Not named *Test so that the build's test-class glob skips it.
 *
 * <pre>
 *     EditorHarness h = EditorHarness.create("alpha\nbravo\n");
 *     try {
 *         h.cursor(0, 0).keys("dw");
 *         h.assertText("bravo\n");
 *     } finally {
 *         h.close();
 *     }
 * </pre>
 */
public final class EditorHarness
{
    private final Editor editor;
    private final Buffer buffer;

    private EditorHarness(Editor editor, Buffer buffer)
    {
        this.editor = editor;
        this.buffer = buffer;
    }

    /**
     * How tall a harness display is, in lines.
     *
     * A display with no size has no rows, which makes every scrolling
     * behaviour degenerate: j would decide it must scroll on each cursor
     * move down. Tests get a conventional terminal's worth of screen.
     */
    public static final int DEFAULT_ROWS = 24;

    /** An editor on an empty buffer. */
    public static EditorHarness create()
    {
        return create("");
    }

    /** An editor on a buffer holding {@code text}, caret at the start. */
    public static EditorHarness create(String text)
    {
        if (!GraphicsEnvironment.isHeadless()) {
            // Not fatal -- the editor works either way -- but a test that
            // silently opened a window would be a surprise on a build machine.
            Log.debug("EditorHarness: not headless");
        }

        final Mode mode = PlainTextMode.getMode();
        // A plausible path that is never written to: the buffer exists only
        // in memory, but a Buffer needs a File to have a mode and a name.
        final Buffer buffer =
            new Buffer(File.getInstance(
                new java.io.File(System.getProperty("java.io.tmpdir"),
                                 "j-harness-" + System.nanoTime() + ".txt").getPath()));
        buffer.setMode(mode);
        buffer.setFormatter(mode.getFormatter(buffer));
        buffer.autosaveEnabled = false;
        buffer.setText(text);

        final Editor editor = new Editor();
        editor.setBufferDirectly(buffer);
        editor.getDisplay().initialize();
        Editor.setCurrentEditor(editor);

        final EditorHarness harness = new EditorHarness(editor, buffer);
        harness.rows(DEFAULT_ROWS);
        harness.text(text);
        return harness;
    }

    public Editor editor()
    {
        return editor;
    }

    public Buffer buffer()
    {
        return buffer;
    }

    /**
     * Puts this editor into modal (vim) editing.
     *
     * Set on the buffer rather than globally so that one test cannot leak an
     * edit mode into the next.
     */
    public EditorHarness vim()
    {
        buffer.setProperty(Property.EDIT_MODE, "vim");
        return this;
    }

    /** The modal state, or null unless {@link #vim} was called. */
    public org.armedbear.j.vim.VimState vimState()
    {
        final InputHandler handler = editor.getInputHandler();
        return handler instanceof org.armedbear.j.vim.VimInputHandler
            ? ((org.armedbear.j.vim.VimInputHandler) handler).getState()
            : null;
    }

    /**
     * Sets how many lines the display shows.
     *
     * H, M, L, CTRL-F and 'scrolloff' are all defined in terms of the visible
     * window, so a test for any of them has to say how big that window is.
     */
    public EditorHarness rows(int rows)
    {
        final int charHeight = Display.getCharHeight();
        editor.getDisplay().setSize(1000, rows * charHeight);
        editor.setSize(1000, rows * charHeight);
        return this;
    }

    /** Replaces the buffer contents and puts the caret back at the start. */
    public EditorHarness text(String text)
    {
        buffer.setText(text);
        editor.setDot(buffer.getFirstLine(), 0);
        editor.setMark(null);
        editor.getDisplay().setTopLine(buffer.getFirstLine());
        return this;
    }

    /** Moves the caret. Both coordinates are zero based, as in vim's API. */
    public EditorHarness cursor(int line, int offset)
    {
        final Line l = lineAt(line);
        if (l == null)
            throw new IllegalArgumentException("no line " + line);
        editor.setDot(l, offset);
        return this;
    }

    /**
     * Presses every key in a vim key sequence, e.g. "3dw" or "i" or
     * "cwhello&lt;Esc&gt;".
     */
    public EditorHarness keys(String keys)
    {
        final List<KeyNotation.Stroke> strokes = KeyNotation.parse(keys);
        for (KeyNotation.Stroke stroke : strokes)
            press(stroke);
        return this;
    }

    /** Presses one stroke, the way AWT would deliver it. */
    public void press(KeyNotation.Stroke stroke)
    {
        final Dispatcher dispatcher = editor.getDispatcher();
        final java.awt.Component source = editor.getDisplay();
        final long when = System.currentTimeMillis();
        final int ex = KeyNotation.awtModifiers(stroke.modifiers);

        int keyCode = stroke.keyCode;
        if (keyCode == 0)
            keyCode = KeyEvent.getExtendedKeyCodeForChar(stroke.keyChar);

        dispatcher.keyPressed(new KeyEvent(source, KeyEvent.KEY_PRESSED, when,
                                           ex, keyCode, stroke.keyChar));

        // AWT follows a key press with a key typed only when the keystroke
        // produces a character. Sending it unconditionally would let a test
        // pass on a path that cannot happen in the running editor.
        if (stroke.producesChar())
            dispatcher.keyTyped(new KeyEvent(source, KeyEvent.KEY_TYPED, when,
                                             ex, KeyEvent.VK_UNDEFINED,
                                             stroke.keyChar));
    }

    /**
     * Replaces the buffer with exactly these lines.
     *
     * {@link #text} goes through {@code Buffer.setText}, which reads the
     * string as a file: a trailing newline terminates the last line, so
     * "a\nb\n" is two lines. That is vim's model too. It is not
     * CodeMirror's, where a newline separates, so "a\nb\n" is three
     * lines, the last one empty. The conformance corpus is written against
     * CodeMirror's model, so it needs a way to say what the lines are without
     * going through a string at all.
     */
    public EditorHarness lines(java.util.List<String> lines)
    {
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        try {
            buffer.empty();
            if (lines.isEmpty())
                buffer.appendLine("");
            else
                for (String line : lines)
                    buffer.appendLine(line);
            buffer.renumber();
            buffer.invalidate();
            buffer.setLoaded(true);
        }
        finally {
            buffer.unlockWrite();
        }
        editor.setDot(buffer.getFirstLine(), 0);
        editor.setMark(null);
        editor.getDisplay().setTopLine(buffer.getFirstLine());
        return this;
    }

    /**
     * Sets the buffer from a string the way CodeMirror's setValue does:
     * every '\n' separates two lines, so a trailing one leaves an empty
     * last line.
     */
    public EditorHarness value(String value)
    {
        return lines(java.util.Arrays.asList(value.split("\n", -1)));
    }

    /**
     * The buffer as CodeMirror's getValue would render it: lines joined with
     * '\n' and no trailing newline.
     */
    public String value()
    {
        final StringBuilder sb = new StringBuilder();
        for (Line line = buffer.getFirstLine(); line != null; line = line.next()) {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(line.getText());
        }
        return sb.toString();
    }

    /** The whole buffer, lines joined with '\n' and a trailing '\n'. */
    public String text()
    {
        final StringBuilder sb = new StringBuilder();
        for (Line line = buffer.getFirstLine(); line != null; line = line.next()) {
            sb.append(line.getText());
            sb.append('\n');
        }
        return sb.toString();
    }

    public int lineNumber()
    {
        return editor.getDotLineNumber();
    }

    public int offset()
    {
        return editor.getDotOffset();
    }

    public void assertText(String expected)
    {
        assertEquals(expected, text());
    }

    public void assertCursorAt(int line, int offset)
    {
        assertEquals("line", line, lineNumber());
        assertEquals("offset", offset, offset());
    }

    private Line lineAt(int lineNumber)
    {
        Line line = buffer.getFirstLine();
        for (int i = 0; i < lineNumber && line != null; i++)
            line = line.next();
        return line;
    }

    /**
     * Detaches the editor and buffer from j's global lists.
     *
     * The current editor is deliberately left alone: {@code setCurrentEditor}
     * does not accept null, and every {@link #create} sets it anyway.
     */
    public void close()
    {
        Editor.getBufferList().remove(buffer);
        Editor.getEditorList().remove(editor);
    }
}
