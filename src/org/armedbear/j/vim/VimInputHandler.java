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
import java.util.List;

import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.InputHandler;
import org.armedbear.j.JEvent;
import org.armedbear.j.Log;
import org.armedbear.j.Position;

/**
 * Modal editing: the keystroke side.
 *
 * Holds the editor's {@link VimState}, turns each event into a key name, and
 * runs whatever the key map says that key means.
 *
 * <p>In a command mode an ordinary character is always consumed, whether or not
 * it means anything yet, because the whole point is that a key never stands for
 * itself there. A key that is <em>not</em> an ordinary character -- a chord, a
 * function key -- falls through to j's own key maps when the modal map has no
 * binding for it, so Ctrl+S still saves.
 */
public final class VimInputHandler implements InputHandler
{
    /** Guards against a key-to-key binding that leads back to itself. */
    private static final int MAX_KEY_TO_KEY_DEPTH = 32;

    private final VimState state = new VimState();
    private final VimKeyMap keyMap;
    private final CommandBuilder builder = new CommandBuilder();

    public VimInputHandler()
    {
        this(VimKeyMap.getDefault());
    }

    public VimInputHandler(VimKeyMap keyMap)
    {
        this.keyMap = keyMap;
    }

    public VimState getState()
    {
        return state;
    }

    public VimKeyMap getKeyMap()
    {
        return keyMap;
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
        builder.reset();
        state.editorLeftBuffer(editor);
    }

    /**
     * A key press carries the physical key and the modifiers, but not reliably
     * the character: on many layouts {@code getKeyChar} is undefined here, and
     * which character a key produces depends on the layout. So anything
     * identified by its character waits for the key typed event.
     */
    private Result keyPressed(Editor editor, JEvent event)
    {
        final int keyCode = event.getKeyCode();
        final int modifiers = event.getModifiers();

        if (keyCode == KeyEvent.VK_ESCAPE)
            return escape(editor);

        if (!state.getMode().isCommandMode())
            return Result.PASS_THROUGH;

        final boolean modified =
            (modifiers & (Constants.CTRL_MASK | Constants.ALT_MASK
                          | Constants.META_MASK)) != 0;
        if (!modified && !isNamedKey(keyCode)) {
            // An ordinary character: decide once we know which one it is.
            return Result.DEFER;
        }

        final String key = KeyNotation.name(keyCode, event.getKeyChar(), modifiers);
        if (dispatch(editor, key, 0))
            return Result.CONSUMED;

        // Nothing in the modal map wants it. If no command is part-typed, let
        // j's own key maps have it, so existing bindings keep working.
        return builder.isEmpty() ? Result.PASS_THROUGH : Result.CONSUMED;
    }

    private Result keyTyped(Editor editor, JEvent event)
    {
        if (!state.getMode().isCommandMode())
            return Result.PASS_THROUGH;
        dispatch(editor, KeyNotation.name(0, event.getKeyChar(), 0), 0);
        return Result.CONSUMED;
    }

    private Result escape(Editor editor)
    {
        builder.reset();
        if (state.getMode().isInsert()) {
            state.setMode(editor, VimMode.NORMAL);
            // Leaving insert steps back onto the last character typed.
            final Position dot = editor.getDot();
            if (dot != null && dot.getOffset() > 0)
                editor.setDot(dot.getLine(), dot.getOffset() - 1);
            editor.moveCaretToDotCol();
        }
        state.clampCaret(editor);
        return Result.CONSUMED;
    }

    // ---------------------------------------------------------- dispatch

    /**
     * Feeds one key to the command being built.
     *
     * @return true if the key was part of a command, complete or not
     */
    private boolean dispatch(Editor editor, String key, int depth)
    {
        if (builder.acceptCountDigit(key))
            return true;

        builder.pushKey(key);
        final KeyStrokeTrie<VimCommand> trie =
            keyMap.getTrie(MappingMode.forVimMode(state.getMode()));
        final KeyStrokeTrie.Match<VimCommand> match = trie.match(builder.getKeys());

        switch (match.status) {
            case PARTIAL:
                return true;
            case FULL:
                break;
            default:
                builder.reset();
                return false;
        }

        final int count = builder.getCount();
        final boolean countGiven = builder.hasCount();
        final VimCommand command = match.value;
        final String character = match.character;
        builder.reset();

        run(editor, command, count, countGiven, character, depth);
        return true;
    }

    private void run(Editor editor, VimCommand command, int count,
                     boolean countGiven, String character, int depth)
    {
        final MotionContext ctx = new MotionContext(editor, state, count,
                                                    countGiven, command,
                                                    character);
        switch (command.getKind()) {
            case MOTION:
                runMotion(editor, ctx, command);
                break;
            case ACTION:
                runAction(editor, ctx, command);
                break;
            case KEY_TO_KEY:
                runKeyToKey(editor, command, count, countGiven, depth);
                break;
            case IDLE:
                break;
            default:
                Log.error("vim: " + command.getKind()
                          + " is not implemented yet: " + command);
                break;
        }
    }

    private void runMotion(Editor editor, MotionContext ctx, VimCommand command)
    {
        final VimMotions.Motion motion = VimMotions.get(command.getCommand());
        if (motion == null) {
            Log.error("vim: no motion named " + command.getCommand());
            return;
        }
        final Position from = editor.getDot();
        if (from == null)
            return;
        final Position to = motion.move(ctx, from);
        if (to == null)
            return;
        editor.setDot(to.getLine(), to.getOffset());
        editor.moveCaretToDotCol();
        state.clampCaret(editor);
        rememberColumn(editor, command);
        editor.updateDotLine();
    }

    /**
     * Keeps or forgets the column j and k aim for.
     *
     * Only the vertical motions preserve it; $ makes it stick to the end of
     * the line; everything else takes it from wherever the caret ended up.
     */
    private void rememberColumn(Editor editor, VimCommand command)
    {
        if (command.getBoolean("keepColumn"))
            return;
        if (command.getBoolean("stickyEol"))
            state.setDesiredColumn(VimState.STICKY_EOL);
        else
            state.clearDesiredColumn();
    }

    private void runAction(Editor editor, MotionContext ctx, VimCommand command)
    {
        final VimActions.Action action = VimActions.get(command.getCommand());
        if (action == null) {
            Log.error("vim: no action named " + command.getCommand());
            return;
        }
        action.run(ctx);
        state.clearDesiredColumn();
    }

    private void runKeyToKey(Editor editor, VimCommand command, int count,
                             boolean countGiven, int depth)
    {
        if (depth >= MAX_KEY_TO_KEY_DEPTH) {
            Log.error("vim: key map recursion at " + command);
            return;
        }
        // The count was typed in front of the original key, so it belongs to
        // the sequence this stands for.
        final List<String> keys = KeyNotation.tokenize(command.getCommand());
        for (int i = 0; i < keys.size(); i++) {
            if (i == 0 && countGiven)
                for (char digit : Integer.toString(count).toCharArray())
                    builder.acceptCountDigit(String.valueOf(digit));
            dispatch(editor, keys.get(i), depth + 1);
        }
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
}
