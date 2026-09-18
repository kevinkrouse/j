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
import org.armedbear.j.Line;
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

    // A command the keys so far already spell, held back in case the next key
    // completes a longer one. See KeyStrokeTrie.Match.fallback.
    private VimCommand fallback;
    private String fallbackCharacter;

    // What '.' repeats. The keys of a change are recorded as they are typed,
    // including everything typed in insert mode, and replayed verbatim -- so
    // a repeat runs the same command rather than an approximation of it.
    private final StringBuilder recording = new StringBuilder();
    private String lastChange;
    private boolean recordingEdit;
    private boolean replaying;
    // Set when a command actually changed the buffer. An operator on its own
    // has not: dw is only a change once the w arrives.
    private boolean edited;

    public VimInputHandler()
    {
        this(VimKeyMap.getShared());
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
    public CaretShape getCaretShape()
    {
        final VimMode mode = state.getMode();
        if (mode == VimMode.REPLACE)
            return CaretShape.UNDERLINE;
        return mode.isCommandMode() ? CaretShape.BLOCK : CaretShape.BAR;
    }

    @Override
    public boolean isLinewiseSelection()
    {
        return state.getMode() == VimMode.VISUAL_LINE;
    }

    @Override
    public String getModeIndicator()
    {
        return state.getMode().getIndicator();
    }

    @Override
    public String getPendingCommand()
    {
        final String pending = builder.getPendingText();
        return pending.isEmpty() ? null : pending;
    }

    @Override
    public void editorDeactivated(Editor editor)
    {
        builder.reset();
        fallback = null;
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

        if (isEscape(keyCode, modifiers))
            return escape(editor);

        if (!state.getMode().isCommandMode()) {
            if (recordingEdit && !replaying && isNamedKey(keyCode))
                recording.append(KeyNotation.name(keyCode, event.getKeyChar(),
                                                  modifiers));
            return Result.PASS_THROUGH;
        }

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
        if (!state.getMode().isCommandMode()) {
            if (recordingEdit && !replaying)
                recording.append(KeyNotation.name(0, event.getKeyChar(), 0));
            return Result.PASS_THROUGH;
        }
        dispatch(editor, KeyNotation.name(0, event.getKeyChar(), 0), 0);
        return Result.CONSUMED;
    }

    private Result escape(Editor editor)
    {
        builder.reset();
        fallback = null;
        if (recordingEdit && !replaying && state.getMode().isInsert()) {
            // The change was still being typed; Escape is the end of it.
            recording.append("<Esc>");
            lastChange = recording.toString();
            clearRecording();
        } else if (!replaying) {
            clearRecording();
        }
        if (state.getMode().isVisual()) {
            VimVisual.leave(editor, state);
            return Result.CONSUMED;
        }
        // Escape means "whatever is going on, stop": that includes a
        // selection left behind by something other than visual mode.
        editor.unmark();
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
        if (depth == 0 && !replaying)
            recording.append(key);
        if (builder.acceptCountDigit(key))
            return true;

        builder.pushKey(key);
        final MappingMode mappingMode = builder.hasOperator()
            ? MappingMode.OP_PENDING
            : MappingMode.forVimMode(state.getMode());
        final KeyStrokeTrie<VimCommand> trie = keyMap.getTrie(mappingMode);
        final KeyStrokeTrie.Match<VimCommand> match = trie.match(builder.getKeys());

        switch (match.status) {
            case PARTIAL:
                if (match.fallback != null) {
                    fallback = match.fallback;
                    fallbackCharacter = match.character;
                }
                return true;
            case FULL:
                break;
            default:
                if (fallback != null) {
                    // The longer command never arrived. Run the shorter one
                    // the earlier keys spelled, then start again with the key
                    // that broke the match.
                    final VimCommand held = fallback;
                    final String heldCharacter = fallbackCharacter;
                    fallback = null;
                    builder.dropLastKey();
                    execute(editor, held, heldCharacter, depth);
                    return dispatch(editor, key, depth);
                }
                builder.reset();
                return false;
        }
        fallback = null;

        final boolean handled = execute(editor, match.value, match.character,
                                        depth);
        if (depth == 0)
            afterCommand();
        return handled;
    }

    /**
     * Decides what the keys just typed mean for '.'.
     *
     * A change that ends in insert mode is not finished being typed, so the
     * recording stays open until Escape closes it. Nor is a command finished
     * while an operator is still waiting for its motion.
     */
    private void afterCommand()
    {
        if (replaying)
            return;
        if (edited) {
            edited = false;
            recordingEdit = true;
            if (!state.getMode().isInsert()) {
                lastChange = recording.toString();
                clearRecording();
            }
            return;
        }
        if (builder.hasOperator() || state.getMode().isInsert())
            return;
        clearRecording();
    }

    /**
     * True for a command that changes the buffer, which is what '.' repeats.
     *
     * Yank is an operator but not a change, so '.' after a yank repeats
     * whatever was changed before it, as in vim.
     */
    private static boolean isEdit(VimCommand command)
    {
        if (command.getKind() == VimCommand.Kind.OPERATOR)
            return !command.getCommand().equals("yank");
        return command.getBoolean("isEdit");
    }

    private void clearRecording()
    {
        recording.setLength(0);
        recordingEdit = false;
    }

    /** Replays the last change. */
    void repeatLastChange(Editor editor, int count, boolean countGiven)
    {
        if (lastChange == null || replaying)
            return;
        // A count given to '.' replaces the one the change was made with.
        final String keys = countGiven ? countGiven(lastChange, count)
                                       : lastChange;
        replaying = true;
        try {
            for (String key : KeyNotation.tokenize(keys))
                dispatchReplay(editor, key);
        }
        finally {
            replaying = false;
        }
    }

    /**
     * Feeds one replayed key, through insert mode as well as command mode.
     *
     * Insert mode keys are not commands, so they go where a typed character
     * would: straight into the buffer.
     */
    private void dispatchReplay(Editor editor, String key)
    {
        if (state.getMode().isInsert()) {
            final KeyNotation.Stroke stroke = KeyNotation.parseOne(key);
            if (stroke.keyCode == KeyEvent.VK_ESCAPE) {
                escape(editor);
                return;
            }
            if (stroke.keyCode == KeyEvent.VK_ENTER)
                editor.newlineAndIndent();
            else if (stroke.keyChar != KeyEvent.CHAR_UNDEFINED)
                editor.insertNormalChar(stroke.keyChar);
            return;
        }
        dispatch(editor, key, 0);
    }

    private static String countGiven(String keys, int count)
    {
        int i = 0;
        while (i < keys.length() && Character.isDigit(keys.charAt(i)))
            ++i;
        return count + keys.substring(i);
    }

    /** Runs a command the keys have completely spelled. */
    private boolean execute(Editor editor, VimCommand command, String character,
                            int depth)
    {
        if (command.getKind() == VimCommand.Kind.OPERATOR) {
            acceptOperator(editor, command);
            return true;
        }

        if (command.getKind() == VimCommand.Kind.KEY_TO_KEY) {
            // Stands for other keys, so put those through instead -- keeping
            // any operator and count already typed in front of it.
            final int count = builder.getCount();
            final boolean countGiven = builder.hasCount();
            builder.clearKeys();
            runKeyToKey(editor, command, count, countGiven, depth);
            return true;
        }

        final int count = builder.getEffectiveCount();
        final boolean countGiven = builder.hasEffectiveCount();
        final VimCommand operator = builder.getOperator();
        builder.reset();

        if (operator != null)
            runOperator(editor, operator, command, count, countGiven, character);
        else
            run(editor, command, count, countGiven, character, depth);
        return true;
    }

    /**
     * Takes an operator, or applies it to the whole line if it is the same
     * one again: dd, cc, yy.
     */
    private void acceptOperator(Editor editor, VimCommand operator)
    {
        if (state.getMode().isVisual()) {
            // Nothing to wait for: the selection is the range.
            final int count = builder.getEffectiveCount();
            builder.reset();
            final VimRange range = VimVisual.toRange(editor, state);
            VimVisual.remember(editor, state);
            editor.unmark();
            state.setMode(editor, VimMode.NORMAL);
            if (range != null)
                applyOperator(editor, operator, range, count, false, null);
            state.clampCaret(editor);
            return;
        }
        final VimCommand pending = builder.getOperator();
        if (pending != null) {
            final boolean doubled =
                pending.getCommand().equals(operator.getCommand());
            final int count = builder.getEffectiveCount();
            builder.reset();
            if (doubled)
                runLinewise(editor, pending, count);
            return;
        }
        builder.setOperator(operator);
    }

    /** dd and friends: count whole lines, starting at this one. */
    private void runLinewise(Editor editor, VimCommand operator, int count)
    {
        final Position from = editor.getDot();
        if (from == null)
            return;
        Line last = from.getLine();
        for (int i = 1; i < count; i++) {
            final Line next = last.nextVisible();
            if (next == null)
                break;
            last = next;
        }
        final VimRange range = RangeNormalizer.normalize(
            new Position(from.getLine(), 0), new Position(last, 0),
            MotionKind.LINEWISE, true);
        applyOperator(editor, operator, range, count, true, null);
    }

    /**
     * Runs an operator over the span a motion covers.
     *
     * The caret never visits the far end: the motion only says how far the
     * operator reaches.
     */
    private void runOperator(Editor editor, VimCommand operator,
                             VimCommand motionCommand, int count,
                             boolean countGiven, String character)
    {
        VimCommand effective = motionCommand;
        MotionKind kind = MotionKind.of(motionCommand);

        // cw and cW change to the end of the word rather than to the start of
        // the next one, so that the space after the word survives.
        if (isChangeWord(operator, motionCommand, editor)) {
            effective = VimKeyMap.parse(
                "o w motion moveByWords forward,wordEnd,inclusive"
                + (motionCommand.getBoolean("bigWord") ? ",bigWord" : ""));
            kind = MotionKind.CHARWISE_INCLUSIVE;
        }

        final VimMotions.Motion motion = VimMotions.get(effective.getCommand());
        if (motion == null) {
            Log.error("vim: no motion named " + effective.getCommand());
            return;
        }
        final Position from = editor.getDot();
        if (from == null)
            return;
        final MotionContext ctx = new MotionContext(this, editor, state, count,
                                                    countGiven, effective,
                                                    character, true);
        final Position to = motion.move(ctx, from);
        if (to == null)
            return;

        if (isForwardWordStart(effective)) {
            // w from an empty line takes the line itself: the empty line is
            // the word being moved over, and there is nothing on it to take.
            if (from.getLineLength() == 0) {
                runLinewise(editor, operator, count);
                return;
            }
            RangeNormalizer.clipWordMotionAtLineEnd(from, to);
        }

        final VimRange range = RangeNormalizer.normalize(
            new Position(from), to, kind, effective.getBoolean("forward"));
        applyOperator(editor, operator, range, count, countGiven, character);
    }

    /**
     * cw on a non-blank behaves as ce. Vim documents this as a special case
     * and it is the one people notice: without it, cw eats the space too.
     */
    private static boolean isChangeWord(VimCommand operator,
                                        VimCommand motionCommand, Editor editor)
    {
        if (!operator.getCommand().equals("change"))
            return false;
        if (!motionCommand.getCommand().equals("moveByWords"))
            return false;
        if (!motionCommand.getBoolean("forward")
            || motionCommand.getBoolean("wordEnd"))
            return false;
        final Position dot = editor.getDot();
        return dot != null && dot.getOffset() < dot.getLineLength()
            && !Character.isWhitespace(dot.getChar());
    }

    private static boolean isForwardWordStart(VimCommand motionCommand)
    {
        return motionCommand.getCommand().equals("moveByWords")
            && motionCommand.getBoolean("forward")
            && !motionCommand.getBoolean("wordEnd");
    }

    private void applyOperator(Editor editor, VimCommand operator,
                               VimRange range, int count, boolean countGiven,
                               String character)
    {
        final VimOperators.Operator op =
            VimOperators.get(operator.getCommand());
        if (op == null) {
            Log.error("vim: no operator named " + operator.getCommand());
            return;
        }
        if (isEdit(operator) && !replaying)
            edited = true;
        op.apply(new MotionContext(this, editor, state, count, countGiven,
                                   operator, character),
                 range);
        state.clearDesiredColumn();
        editor.updateDotLine();
    }

    private void run(Editor editor, VimCommand command, int count,
                     boolean countGiven, String character, int depth)
    {
        final MotionContext ctx = new MotionContext(this, editor, state, count,
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
            case EDITOR_COMMAND:
                // Whatever the user bound: one of j's own named commands,
                // which the modal layer knows nothing about.
                editor.executeCommand(command.getCommand(), false);
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
        final Line was = from.getLine();
        state.clearSelectionUnlessVisual(editor);
        editor.setDot(to.getLine(), to.getOffset());
        editor.moveCaretToDotCol();
        state.clampCaret(editor);
        rememberColumn(editor, command);
        editor.updateDotLine();
        state.selectionCrossedLines(editor, was, editor.getDotLine());
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
        if (isEdit(command) && !replaying)
            edited = true;
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

    /**
     * Escape, and the chord that has always meant it.
     *
     * CTRL-[ is what Escape sends on a terminal, and vim treats the two as the
     * same key. It is handled here rather than in the key map table because
     * leaving insert mode cannot go through the table: in insert mode the
     * table is not consulted at all.
     */
    private static boolean isEscape(int keyCode, int modifiers)
    {
        if (keyCode == KeyEvent.VK_ESCAPE)
            return true;
        return keyCode == KeyEvent.VK_OPEN_BRACKET
            && (modifiers & Constants.CTRL_MASK) != 0
            && (modifiers & (Constants.ALT_MASK | Constants.META_MASK)) == 0;
    }

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
