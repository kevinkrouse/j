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
        // j's last find pattern, jump list and bookmarks are every window's,
        // so one test's would otherwise reach the next.
        editor.setLastSearch(null);
        editor.setSearchHighlightHidden(false);
        JumpList.clear();
        for (char c = '0'; c <= '9'; c++)
            Editor.setBookmark(c, null);
        for (char c = 'A'; c <= 'Z'; c++)
            Editor.setBookmark(c, null);
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
        return vim(null);
    }

    /**
     * Turns on modal editing, with a vimrc.
     *
     * Always goes through here rather than through the shared key map, so a
     * test never reads the vimrc of whoever is running it.
     */
    public EditorHarness vim(String vimrc)
    {
        final org.armedbear.j.vim.VimKeyMap keyMap =
            org.armedbear.j.vim.VimKeyMap.getDefault();
        final org.armedbear.j.vim.VimOptions options =
            new org.armedbear.j.vim.VimOptions();
        if (vimrc != null) {
            try {
                new org.armedbear.j.vim.VimrcParser(keyMap, options)
                    .load(new java.io.StringReader(vimrc));
            }
            catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        org.armedbear.j.vim.VimKeyMap.setShared(keyMap, options);
        buffer.setProperty(Property.EDIT_MODE, "vim");
        // Registers are global, as they are in vim, so one test's yank would
        // otherwise be visible to the next.
        org.armedbear.j.vim.VimRegisters.getInstance().clear();
        return this;
    }

    /** The modal state, or null unless {@link #vim} was called. */
    public org.armedbear.j.vim.VimState vimState()
    {
        final org.armedbear.j.vim.VimInputHandler handler = vimHandler();
        return handler != null ? handler.getState() : null;
    }

    private org.armedbear.j.vim.VimInputHandler vimHandler()
    {
        final InputHandler handler = editor.getInputHandler();
        return handler instanceof org.armedbear.j.vim.VimInputHandler
            ? (org.armedbear.j.vim.VimInputHandler) handler
            : null;
    }

    /**
     * Answers the pattern prompt a {@code /} or {@code ?} is waiting on.
     *
     * A frameless editor has no location bar to type into, so the keystrokes
     * that would go there are supplied here instead. Everything after the
     * pattern arrives -- the parked operator, the range, the move -- is the
     * production path.
     */
    public EditorHarness searchPattern(String pattern)
    {
        vimHandler().searchEntered(editor, pattern);
        return this;
    }

    /** Stands in for typing a pattern into the prompt so far, for incsearch. */
    public EditorHarness searchTyped(String pattern)
    {
        vimHandler().searchTyped(editor, pattern);
        return this;
    }

    /** The search matches painted on a line, as "start-end start-end". */
    public String searchMatches(int lineNumber)
    {
        return spans(editor.getSearchMatches(lineAt(lineNumber)));
    }

    /** The match painted as the one incsearch is on, as "start-end". */
    public String currentSearchMatch(int lineNumber)
    {
        return spans(editor.getCurrentSearchMatch(lineAt(lineNumber)));
    }

    private static String spans(int[] spans)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; spans != null && i < spans.length; i += 2)
            sb.append(sb.length() == 0 ? "" : " ")
              .append(spans[i]).append('-').append(spans[i + 1]);
        return sb.toString();
    }

    /**
     * Supplies the line a waiting {@code :} asked for.
     *
     * The counterpart of {@link #searchPattern}: a frameless editor has no
     * location bar, so this stands in for typing at one. Everything after it
     * is the production path.
     */
    public EditorHarness exCommand(String line)
    {
        vimHandler().exEntered(editor, line);
        return this;
    }

    /** The mode vim edit mode would show in the status bar, or null. */
    public String vimModeIndicator()
    {
        return vimHandler().getModeIndicator();
    }

    /** The last status message, or "" if there has been none. */
    public String status()
    {
        final String s = editor.getLastStatus();
        return s == null ? "" : s;
    }

    /** True while a {@code :} is waiting for its line. */
    public boolean awaitingExCommand()
    {
        return vimHandler().isAwaitingExCommand();
    }

    /** True while a {@code /} or {@code ?} is waiting for its pattern. */
    public boolean awaitingSearchPattern()
    {
        return vimHandler().isAwaitingSearchPattern();
    }

    /** Puts the buffer in another mode: its key map and its indentation. */
    public EditorHarness mode(Mode mode)
    {
        buffer.setMode(mode);
        buffer.setFormatter(mode.getFormatter(buffer));
        return this;
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
        final char keyChar = characterAwtWouldSend(stroke);

        dispatcher.keyPressed(new KeyEvent(source, KeyEvent.KEY_PRESSED, when,
                                           ex, keyCode, keyChar));

        // AWT follows a key press with a key typed only when the keystroke
        // produces a character. Sending it unconditionally would let a test
        // pass on a path that cannot happen in the running editor.
        if (stroke.producesChar())
            dispatcher.keyTyped(new KeyEvent(source, KeyEvent.KEY_TYPED, when,
                                             ex, KeyEvent.VK_UNDEFINED,
                                             keyChar));
    }

    /**
     * What AWT puts in {@code getKeyChar} for a stroke.
     *
     * A key map writes Ctrl-R as the letter r, but AWT reports the character
     * the keystroke <em>produces</em>: 0x12. Sending the letter here would
     * make every control binding pass on a path the running editor never
     * takes -- which is how &lt;C-r&gt; shipped opening j's replace dialog
     * with a green test.
     */
    private static char characterAwtWouldSend(KeyNotation.Stroke stroke)
    {
        final char c = stroke.keyChar;
        if ((stroke.modifiers & Constants.CTRL_MASK) != 0
            && c >= 'a' && c <= 'z')
            return (char) (c - 'a' + 1);
        return c;
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
        // Count lines rather than testing whether anything has been appended:
        // a leading empty line appends nothing and would lose its separator.
        boolean first = true;
        for (Line line = buffer.getFirstLine(); line != null; line = line.next()) {
            if (!first)
                sb.append('\n');
            first = false;
            sb.append(textOf(line));
        }
        return sb.toString();
    }

    private static String textOf(Line line)
    {
        final String text = line.getText();
        return text == null ? "" : text;
    }

    /** The whole buffer, lines joined with '\n' and a trailing '\n'. */
    public String text()
    {
        final StringBuilder sb = new StringBuilder();
        for (Line line = buffer.getFirstLine(); line != null; line = line.next()) {
            sb.append(textOf(line));
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

    /**
     * Forgets any repaint the display is owed, so that a test can ask whether
     * the <em>next</em> keystroke asks for one.
     */
    public EditorHarness clearRepaintPending()
    {
        editor.getDisplay().clearRepaintPending();
        return this;
    }

    /** True if something has asked for the whole display to be redrawn. */
    public boolean repaintPending()
    {
        return editor.getDisplay().isRepaintPending();
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
