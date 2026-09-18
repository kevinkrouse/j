/*
 * VimInputHandler.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.awt.event.KeyEvent;

import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.InputHandler;
import org.armedbear.j.JEvent;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Modal editing: the keystroke side.
 *
 * Holds the editor's {@link VimState} and decides, for each event, whether the
 * key is a command, text, or none of its business.
 *
 * <p>Only the pieces the current milestone needs are implemented. Normal mode
 * swallows every key it does not understand rather than letting it reach the
 * key maps, because a half-built normal mode that sometimes inserts text is
 * worse than one that does nothing: the whole point is that a key in normal
 * mode never means itself.
 */
public final class VimInputHandler implements InputHandler
{
    private final VimState state = new VimState();

    public VimState getState()
    {
        return state;
    }

    @Override
    public Result handle(Editor editor, JEvent event)
    {
        switch (event.getID()) {
            case JEvent.KEY_PRESSED:
                return keyPressed(editor, event);
            case JEvent.KEY_TYPED:
                return keyTyped(editor, event);
            default:
                // Mouse presses move the caret through j's own bindings.
                return Result.PASS_THROUGH;
        }
    }

    @Override
    public void editorDeactivated(Editor editor)
    {
        state.editorLeftBuffer(editor);
    }

    /**
     * A key press carries the physical key and the modifiers, but not reliably
     * the character: on many layouts {@code getKeyChar} is undefined here, and
     * which character a key produces depends on the layout. So anything that
     * is identified by its character waits for the key typed event.
     */
    private Result keyPressed(Editor editor, JEvent event)
    {
        final int keyCode = event.getKeyCode();
        final int modifiers = event.getModifiers();

        if (keyCode == KeyEvent.VK_ESCAPE)
            return escape(editor);

        // Control and alt chords keep their j bindings for now; vim's own
        // chords arrive with the key map table.
        if ((modifiers & (Constants.CTRL_MASK | Constants.ALT_MASK
                          | Constants.META_MASK)) != 0)
            return Result.PASS_THROUGH;

        if (isNamedKey(keyCode))
            return Result.PASS_THROUGH;

        // An ordinary character: decide when we can see which one it is.
        return state.getMode().isCommandMode() ? Result.DEFER
                                               : Result.PASS_THROUGH;
    }

    private Result keyTyped(Editor editor, JEvent event)
    {
        if (!state.getMode().isCommandMode())
            return Result.PASS_THROUGH;
        command(editor, event.getKeyChar());
        // Consumed whether or not it meant anything: in command mode a
        // character is never text.
        return Result.CONSUMED;
    }

    private Result escape(Editor editor)
    {
        if (state.getMode().isInsert()) {
            state.setMode(editor, VimMode.NORMAL);
            // Leaving insert steps back onto the last character typed.
            moveLeftWithinLine(editor);
            state.clampCaret(editor);
            return Result.CONSUMED;
        }
        // Already in a command mode: swallow it, so Escape does not reach
        // j's own escape() and close things the user is still using.
        state.clampCaret(editor);
        return Result.CONSUMED;
    }

    /** Runs one normal-mode command, or does nothing if it is not one yet. */
    private void command(Editor editor, char c)
    {
        switch (c) {
            case 'i':
                enterInsert(editor);
                break;
            case 'a':
                moveRightWithinLine(editor);
                enterInsert(editor);
                break;
            case 'I':
                editor.home();
                enterInsert(editor);
                break;
            case 'A':
                editor.eol();
                enterInsert(editor);
                break;
            case 'o':
                editor.eol();
                enterInsert(editor);
                editor.newlineAndIndent();
                break;
            case 'O':
                editor.bol();
                enterInsert(editor);
                editor.newlineAndIndent();
                moveUpToOpenedLine(editor);
                break;
            default:
                break;
        }
    }

    private void enterInsert(Editor editor)
    {
        state.beginInsert(editor, VimMode.INSERT);
    }

    // ------------------------------------------------------------ helpers

    /** True for keys identified by their code rather than their character. */
    private static boolean isNamedKey(int keyCode)
    {
        switch (keyCode) {
            case KeyEvent.VK_ENTER:
            case KeyEvent.VK_BACK_SPACE:
            case KeyEvent.VK_TAB:
            case KeyEvent.VK_DELETE:
            case KeyEvent.VK_INSERT:
            case KeyEvent.VK_UP:
            case KeyEvent.VK_DOWN:
            case KeyEvent.VK_LEFT:
            case KeyEvent.VK_RIGHT:
            case KeyEvent.VK_HOME:
            case KeyEvent.VK_END:
            case KeyEvent.VK_PAGE_UP:
            case KeyEvent.VK_PAGE_DOWN:
                return true;
            default:
                return keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12;
        }
    }

    private static void moveLeftWithinLine(Editor editor)
    {
        final Position dot = editor.getDot();
        if (dot != null && dot.getOffset() > 0)
            editor.setDot(dot.getLine(), dot.getOffset() - 1);
        editor.moveCaretToDotCol();
    }

    private static void moveRightWithinLine(Editor editor)
    {
        final Position dot = editor.getDot();
        if (dot != null && dot.getOffset() < dot.getLineLength())
            editor.setDot(dot.getLine(), dot.getOffset() + 1);
        editor.moveCaretToDotCol();
    }

    /**
     * After O has split the line, the caret is on the line below the new one.
     */
    private static void moveUpToOpenedLine(Editor editor)
    {
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Line previous = dot.getLine().previous();
        if (previous != null)
            editor.setDot(previous, previous.length());
        editor.moveCaretToDotCol();
    }
}
