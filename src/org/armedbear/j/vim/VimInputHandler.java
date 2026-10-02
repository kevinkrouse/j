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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.armedbear.j.Block;
import org.armedbear.j.Buffer;
import org.armedbear.j.CommandTable;
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

    {
        state.setHandler(this);
    }
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
    // The recording is an i begun by an arrow or CTRL-O in insert mode, and
    // is not a change until something is typed after the i.
    private boolean insertRestarted;
    private boolean replaying;
    // Set when a command actually changed the buffer. An operator on its own
    // has not: dw is only a change once the w arrives.
    private boolean edited;
    /** The first half of a surrogate pair typed as a command key. */
    private char highSurrogate;

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
    public boolean isBlockSelection()
    {
        return state.getMode() == VimMode.VISUAL_BLOCK;
    }

    @Override
    public int[] getBlockSelection(Editor editor, Line line)
    {
        if (!isBlockSelection() || editor.getMark() == null
            || editor.getDot() == null)
            return null;
        final Block block = VimVisual.block(editor, state);
        final int n = line.lineNumber();
        if (n < block.getFirstLine().lineNumber()
            || n > block.getLastLine().lineNumber())
            return null;
        return block.getOffsets(line);
    }

    @Override
    public String getModeIndicator()
    {
        return state.getModeIndicator();
    }

    @Override
    public String getPendingCommand()
    {
        final String pending = builder.getPendingText();
        return pending.isEmpty() ? null : pending;
    }

    @Override
    public int[] getSearchMatches(Editor editor, Line line)
    {
        return state.searchMatches(editor, line);
    }

    @Override
    public int[] getCurrentSearchMatch(Editor editor, Line line)
    {
        return state.currentSearchMatch(editor, line);
    }

    @Override
    public void editorDeactivated(Editor editor)
    {
        builder.reset();
        fallback = null;
        insertBindingKeys.clear();
        // A buffer switch mid-insert never runs Escape, so without this a
        // partial insert-mode recording would survive and contaminate the
        // next '.' repeat.
        clearRecording();
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

        if (typedLine != null) {
            // Enter and Backspace are the only key-coded ones a pattern
            // cares about; the characters arrive as key typed.
            if (keyCode == KeyEvent.VK_ENTER
                || keyCode == KeyEvent.VK_BACK_SPACE)
                return collectLine(editor, keyCode, KeyEvent.CHAR_UNDEFINED);
            if (typedKind == LineKind.SEARCH
                && runCommandLineKey(editor, keyCode, event.getKeyChar(),
                                     modifiers))
                return Result.CONSUMED;
            return Result.DEFER;
        }

        if (!state.getMode().isCommandMode()) {
            state.noteInsertStart(editor);
            // The rest of a binding, as the register after CTRL-R. A
            // character waits for its key typed event.
            if (!insertBindingKeys.isEmpty()) {
                if (!isNamedKey(keyCode) && !isChorded(modifiers))
                    return Result.DEFER;
                final String key =
                    KeyNotation.name(keyCode, event.getKeyChar(), modifiers);
                if (runInsertBinding(editor, key))
                    return Result.CONSUMED;
            }
            final int recordedTo = recording.length();
            final int insertKeysTo = state.insertKeysLength();
            if (!replaying && isNamedKey(keyCode)) {
                final String name =
                    KeyNotation.name(keyCode, event.getKeyChar(), modifiers);
                if (recordingEdit)
                    recording.append(name);
                state.noteInsertKey(name);
            }
            // A chorded key bound in insert mode -- CTRL-T, CTRL-D -- is the
            // vim command, not j's: j binds Ctrl-D to dir, which would open
            // a directory buffer in the middle of typing.
            if (isChorded(modifiers)
                && runInsertBinding(editor,
                       KeyNotation.name(keyCode, event.getKeyChar(), modifiers)))
                return Result.CONSUMED;
            // Enter is j's, but the indent it makes is the session's: Escape
            // straight after it takes the indent away again.
            if (keyCode == KeyEvent.VK_ENTER && !isChorded(modifiers)
                && VimActions.insertNewline(editor, state, event))
                return Result.CONSUMED;
            // Tab is typing too, and arrives with no character.
            if (keyCode == KeyEvent.VK_TAB && !isChorded(modifiers))
                state.forgetAutoIndent();
            if (state.getMode() == VimMode.REPLACE) {
                // Shift-Backspace is still Backspace to vim. Held with Ctrl,
                // Alt or Meta it is one of j's own bindings, so it goes
                // through -- and whatever that does to the caret, the next
                // Backspace notices, because the record is checked against
                // where the last keystroke left it.
                if (keyCode == KeyEvent.VK_BACK_SPACE && !isChorded(modifiers)) {
                    VimActions.replaceBackspace(editor, state);
                    return Result.CONSUMED;
                }
                // Tab arrives with no character, so the typed path never sees
                // it, and passing it through would insert rather than type
                // over. Vim writes one tab over one character.
                if (keyCode == KeyEvent.VK_TAB && !isChorded(modifiers)) {
                    VimActions.replaceTypedCharacter(editor, state, '\t');
                    return Result.CONSUMED;
                }
            }
            if (isNamedKey(keyCode) || isChorded(modifiers))
                return runInInsert(editor, event, recordedTo, insertKeysTo);
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
        final boolean dispatched = dispatch(editor, key, 0);
        resumeInsert(editor);
        if (dispatched)
            return Result.CONSUMED;

        // Nothing in the modal map wants it. If no command is part-typed, let
        // j's own key maps have it, so existing bindings keep working.
        return builder.isEmpty() ? Result.PASS_THROUGH : Result.CONSUMED;
    }

    /**
     * Runs j's binding for a key in insert mode. One that moves the caret
     * without changing the text -- an arrow, Home, a page key -- splits the
     * insert as vim's arrows do: the undo step ends, '.' keeps what was typed
     * before it, and what is typed after is a new change that '.' repeats as
     * an i. The count is dropped. One that does neither -- an arrow at the
     * edge -- is not recorded at all.
     *
     * @param recordedTo   the recording's length before this key was added
     * @param insertKeysTo the session's keys' length before it
     */
    private Result runInInsert(Editor editor, JEvent event, int recordedTo,
                               int insertKeysTo)
    {
        final Buffer buffer = editor.getBuffer();
        final Position before =
            editor.getDot() != null ? new Position(editor.getDot()) : null;
        final int modCount = buffer.getModCount();
        if (!editor.handleKeyMapEvent(event))
            return Result.DEFER; // unbound: the key maps' own false
        final Position after = editor.getDot();
        // Not after a binding that left insert mode or the buffer: that has
        // already ended the session.
        if (replaying || before == null || after == null
            || editor.getBuffer() != buffer || !state.getMode().isInsert())
            return Result.CONSUMED;
        if (buffer.getModCount() != modCount) {
            state.insertDeletedBack(editor);
            return Result.CONSUMED;
        }
        // Typed nothing: nothing for '.' or a count to type again.
        recording.setLength(recordedTo);
        state.truncateInsertKeys(insertKeysTo);
        if (after.equals(before))
            return Result.CONSUMED;
        state.markInsert(editor, before);
        if (recordingEdit)
            commitInsert();
        recording.setLength(0);
        startSplitRecording();
        state.restartInsert();
        return Result.CONSUMED;
    }

    /** After a split, '.' repeats what is typed next as an i. */
    private void startSplitRecording()
    {
        recording.append('i');
        recordingEdit = true;
        insertRestarted = true;
    }

    /**
     * Keys of an insert-mode binding typed so far, while CTRL-R waits for
     * its register.
     */
    private final List<String> insertBindingKeys = new ArrayList<String>();

    /**
     * Feeds a key to the insert-mode bindings: a whole one runs, and the
     * start of one waits for the rest.
     *
     * @return false when the key is not bound, so it goes to j as before
     */
    private boolean runInsertBinding(Editor editor, String key)
    {
        insertBindingKeys.add(key);
        final KeyStrokeTrie.Match<VimCommand> match = keyMap
            .getTrie(MappingMode.INSERT).match(insertBindingKeys);
        if (match.status == KeyStrokeTrie.Status.PARTIAL)
            return true;
        final List<String> keys = new ArrayList<String>(insertBindingKeys);
        insertBindingKeys.clear();
        if (match.status != KeyStrokeTrie.Status.FULL)
            // What came before is dropped; the key may start another.
            return keys.size() > 1 && runInsertBinding(editor, key);
        // Part of the insert session, so '.' replays it with the rest --
        // unless it records what it does itself, as CTRL-R does.
        if (!replaying && !match.value.getBoolean("unrecorded")) {
            for (String k : keys) {
                if (recordingEdit)
                    recording.append(k);
                state.noteInsertKey(k);
            }
        }
        run(editor, match.value, 1, false, match.character, 1);
        return true;
    }

    /** True when a modifier other than Shift is held. */
    private static boolean isChorded(int modifiers)
    {
        return (modifiers & (Constants.CTRL_MASK | Constants.ALT_MASK
                             | Constants.META_MASK)) != 0;
    }

    private Result keyTyped(Editor editor, JEvent event)

    {
        if (typedLine != null)
            return collectLine(editor, 0, event.getKeyChar());

        if (!state.getMode().isCommandMode()) {
            state.noteInsertStart(editor);
            if (!insertBindingKeys.isEmpty()
                && runInsertBinding(editor,
                       KeyNotation.name(0, event.getKeyChar(), 0)))
                return Result.CONSUMED;
            if (!replaying) {
                final String name = KeyNotation.name(0, event.getKeyChar(), 0);
                if (recordingEdit)
                    recording.append(name);
                state.noteInsertKey(name);
            }
            final char typed = event.getKeyChar();
            // Typing makes the indent the user's.
            if (typed >= ' ' && typed != KeyEvent.CHAR_UNDEFINED
                && typed != '\u007f')
                state.forgetAutoIndent();
            if (state.getMode() == VimMode.REPLACE && typed >= ' '
                && typed != KeyEvent.CHAR_UNDEFINED && typed != '\u007f') {
                VimActions.replaceTypedCharacter(editor, state, typed);
                return Result.CONSUMED;
            }
            return Result.PASS_THROUGH;
        }
        // AWT sends a character outside the Basic Multilingual Plane as two
        // key typed events. A command takes it as one key, so that f can
        // find an emoji: hold the first half until the second arrives.
        final char c = event.getKeyChar();
        if (Character.isHighSurrogate(c)) {
            highSurrogate = c;
            return Result.CONSUMED;
        }
        final String key = highSurrogate != 0 && Character.isLowSurrogate(c)
            ? new String(new char[] { highSurrogate, c })
            : KeyNotation.name(0, c, 0);
        highSurrogate = 0;
        dispatch(editor, key, 0);
        resumeInsert(editor);
        return Result.CONSUMED;
    }

    private Result escape(Editor editor)
    {
        // CTRL-R waiting for its register: Escape takes back only the CTRL-R.
        if (!insertBindingKeys.isEmpty()) {
            insertBindingKeys.clear();
            return Result.CONSUMED;
        }
        dropPartialCommand(editor);
        if (recordingEdit && !replaying && state.getMode().isInsert()) {
            // The change was still being typed; Escape is the end of it.
            commitInsert();
            clearRecording();
        } else if (!replaying) {
            clearRecording();
        }
        if (state.getMode().isVisual()) {
            VimVisual.leave(editor, state);
            resumeInsert(editor);
            return Result.CONSUMED;
        }
        // Escape means "whatever is going on, stop": that includes a
        // selection left behind by something other than visual mode.
        editor.unmark();
        if (state.getMode().isInsert()) {
            // Before the mode changes, so both are part of the insert's undo
            // step.
            repeatInsert(editor);
            dropUntouchedAutoIndent(editor);
            state.finishBlockInsert(editor);
            state.markInsertStop(editor);
            state.setMode(editor, VimMode.NORMAL);
            // Leaving insert steps back onto the last character typed.
            final Position dot = editor.getDot();
            if (dot != null && dot.getOffset() > 0)
                editor.setDot(dot.getLine(),
                              CodePoints.previous(dot.getLine(), dot.getOffset()));
            editor.moveCaretToDotCol();
            // I and A over a block go back to its top left.
            final Position landing = state.takeBlockInsertLanding();
            if (landing != null) {
                editor.setDot(landing);
                editor.moveCaretToDotCol();
            }
        }
        state.clampCaret(editor);
        resumeInsert(editor);
        return Result.CONSUMED;
    }

    /** True when CTRL-O's command comes from keys being replayed. */
    private boolean oneCommandReplaying;
    /**
     * The insert CTRL-O split, which is the last change once the command is
     * over -- unless the command was a change itself. Until then . inside
     * it repeats the change before, as in vim.
     */
    private String heldInsert;
    private String changeBeforeCommand;

    /**
     * CTRL-O: one command in normal mode, then back to insert. The insert
     * is split there as an arrow splits it, and its count is dropped.
     */
    void runOneCommand(Editor editor)
    {
        if (!replaying) {
            heldInsert = recordingEdit ? recordedInsert() : null;
            changeBeforeCommand = lastChange;
            clearRecording();
        }
        dropUntouchedAutoIndent(editor);
        state.markInsertStop(editor);
        state.leaveInsertForOneCommand(editor);
        oneCommandReplaying = replaying;
    }

    /**
     * Back to insert mode once CTRL-O's command is over: not while it is
     * still being typed, nor in a visual mode it began. One that began an
     * insert of its own leaves nothing to do.
     */
    private void resumeInsert(Editor editor)
    {
        if (!state.isOneCommand() || replaying != oneCommandReplaying)
            return;
        if (state.getMode().isInsert()) {
            state.forgetOneCommand();
            return;
        }
        if (state.getMode().isVisual() || !builder.isEmpty()
            || pendingSearch != null || typedLine != null || exPromptOpen
            || state.hasPendingRegister())
            return;
        state.resumeInsert(editor);
        if (!replaying) {
            if (heldInsert != null && lastChange == changeBeforeCommand)
                lastChange = heldInsert;
            heldInsert = null;
            clearRecording();
            startSplitRecording();
        }
    }

    /**
     * Types the session's keys again for the count it was started with:
     * {@code 3iab<Esc>} gives ababab, and {@code 3o} three lines.
     */
    private void repeatInsert(Editor editor)
    {
        final int times = state.takeInsertRepeat();
        if (times == 0)
            return;
        final List<String> keys = KeyNotation.tokenize(state.getInsertKeys());
        final boolean wasReplaying = replaying;
        replaying = true;
        try {
            for (int i = 0; i < times; i++) {
                if (state.insertRepeatOpensLine()) {
                    dropUntouchedAutoIndent(editor);
                    VimActions.openLine(editor, state, true);
                }
                for (String key : keys)
                    dispatchReplay(editor, key);
            }
        }
        finally {
            replaying = wasReplaying;
        }
    }

    /**
     * Empties a line o, O or cc indented if nothing was typed after the
     * indent, as vim does: nvim leaves an empty line after o<Esc> under an
     * indented one, not a line of blanks.
     */
    private void dropUntouchedAutoIndent(Editor editor)
    {
        final Position at = editor.getDot();
        if (at == null || !state.isUntouchedAutoIndent(at.getLine()))
            return;
        final Line line = at.getLine();
        editor.deleteRegion(new Position(line, 0),
                            new Position(line, line.length()));
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
        final KeyStrokeTrie<VimCommand> trie =
            (builtInOnly ? keyMap.getBuiltIn() : keyMap).getTrie(mappingMode);
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
        final String visualShape = state.takeVisualRepeat();
        if (replaying)
            return;
        if (edited) {
            edited = false;
            recordingEdit = true;
            // A change from visual mode repeats over as much again.
            if (visualShape != null)
                recording.insert(0, visualShape);
            if (!state.getMode().isInsert()) {
                lastChange = recording.toString();
                clearRecording();
            }
            return;
        }
        // A search has already cleared the builder, so hasOperator no longer
        // shows that a command is still in flight -- without this the keys
        // typed so far are thrown away before the pattern arrives.
        if (builder.hasOperator() || pendingSearch != null
            || state.getMode().isInsert())
            return;
        clearRecording();
    }

    /**
     * Forgets a command typed only in part: an operator or register waiting,
     * or a / or : line still being typed, here or at a prompt.
     */
    private void dropPartialCommand(Editor editor)
    {
        builder.reset();
        fallback = null;
        // A / whose prompt never delivered leaves its operator parked, and
        // it would swallow the next motion typed. Put back what incsearch
        // moved while it was being typed.
        endPreview(editor, pendingSearch);
        pendingSearch = null;
        typedLine = null;
        // "a then Escape means the register was never used; without this it
        // would silently attach itself to some unrelated later command.
        state.clearPendingRegister();
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

    /**
     * Makes the insert being recorded the last change -- unless it is an i an
     * arrow began and nothing followed, which leaves the one before.
     */
    private void commitInsert()
    {
        final String insert = recordedInsert();
        if (insert != null)
            lastChange = insert;
    }

    /** The insert being recorded as a change, or null if it is not one. */
    private String recordedInsert()
    {
        if (insertRestarted && recording.length() == 1)
            return null;
        return recording + "<Esc>";
    }

    private void clearRecording()
    {
        recording.setLength(0);
        recordingEdit = false;
        insertRestarted = false;
    }

    /** Replays the last change. */
    void repeatLastChange(Editor editor, int count, boolean countGiven)
    {
        if (lastChange == null || replaying)
            return;
        // A count given to '.' replaces the one the change was made with.
        final String keys = countGiven ? countGiven(lastChange, count)
                                       : lastChange;
        runKeys(editor, keys);
    }

    /**
     * Runs a string of keys as though they had been typed.
     *
     * Shared by {@code .} and by {@code :normal}. Recording is off for the
     * duration: these keys are a replay of a change, or somebody else's
     * command, and either way they are not the change {@code .} should
     * repeat next.
     */
    void runKeys(Editor editor, String keys)
    {
        if (replaying)
            return;
        replaying = true;
        try {
            for (String key : KeyNotation.tokenize(keys))
                dispatchReplay(editor, key);
            // :normal ends an unfinished command the way vim does, so a
            // trailing "A;" leaves insert mode rather than eating the next
            // key typed.
            if (state.getMode().isInsert())
                escape(editor);
            // So does a command left half typed: :normal /foo abandons the
            // search rather than leave it waiting for the next key.
            dropPartialCommand(editor);
            // And a CTRL-O it began comes back to nothing.
            if (oneCommandReplaying)
                state.forgetOneCommand();
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
        // Text takes a surrogate pair as the two events AWT sends; only a
        // command takes it as one key.
        if ((typedLine != null || state.getMode().isInsert())
            && key.length() == 2
            && Character.isSurrogatePair(key.charAt(0), key.charAt(1))) {
            dispatchReplay(editor, key.substring(0, 1));
            dispatchReplay(editor, key.substring(1));
            return;
        }
        if (typedLine != null) {
            // Mid-pattern: these keys are the search text, not commands.
            final KeyNotation.Stroke stroke = KeyNotation.parseOne(key);
            collectLine(editor, stroke.keyCode, stroke.keyChar);
            return;
        }
        if (state.getMode().isInsert()) {
            state.noteInsertStart(editor);
            final KeyNotation.Stroke stroke = KeyNotation.parseOne(key);
            if (stroke.keyCode == KeyEvent.VK_ESCAPE) {
                escape(editor);
                return;
            }
            state.noteInsertKey(key);
            if ((isChorded(stroke.modifiers) || !insertBindingKeys.isEmpty())
                && runInsertBinding(editor, key))
                return;
            typeInsertKey(editor, stroke);
            return;
        }
        dispatch(editor, key, 0);
        resumeInsert(editor);
    }

    /**
     * Types text in insert mode as though its keys were typed, so that a
     * line break indents as Enter does. Recorded as those keys: '.' types
     * the same text again rather than reading a register a second time.
     */
    void typeText(Editor editor, String text)
    {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final String key = c == '\n' ? "<CR>" : KeyNotation.name(0, c, 0);
            if (!replaying) {
                if (recordingEdit)
                    recording.append(key);
                state.noteInsertKey(key);
            }
            typeInsertKey(editor, KeyNotation.parseOne(key));
        }
    }

    /** What an insert-mode key does when it is not a binding of ours. */
    private void typeInsertKey(Editor editor, KeyNotation.Stroke stroke)
    {
        if (stroke.keyCode == KeyEvent.VK_BACK_SPACE) {
            if (state.getMode() == VimMode.REPLACE)
                VimActions.replaceBackspace(editor, state);
            else
                editor.backspace();
            state.insertDeletedBack(editor);
        } else if (stroke.keyCode == KeyEvent.VK_ENTER) {
            VimActions.insertNewline(editor, state,
                new JEvent(JEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', 0));
        } else if (stroke.keyCode == KeyEvent.VK_TAB
                   && state.getMode() == VimMode.REPLACE) {
            VimActions.replaceTypedCharacter(editor, state, '\t');
        } else if (isNamedKey(stroke.keyCode)) {
            // Delete, Tab, an arrow: whatever j binds it to, as it was
            // when typed. Its character is not text: Delete's is DEL.
            if (stroke.keyCode == KeyEvent.VK_TAB)
                state.forgetAutoIndent();
            editor.handleKeyMapEvent(new JEvent(JEvent.KEY_PRESSED,
                stroke.keyCode, stroke.keyChar, stroke.modifiers));
        } else if (stroke.keyChar != KeyEvent.CHAR_UNDEFINED) {
            if (state.getMode() == VimMode.REPLACE)
                VimActions.replaceTypedCharacter(editor, state,
                                                 stroke.keyChar);
            else
                editor.insertNormalChar(stroke.keyChar);
            state.forgetAutoIndent();
        }
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

        if (command.getKind() == VimCommand.Kind.SEARCH)
            startSearch(editor, operator, command, count, countGiven);
        else if (command.getKind() == VimCommand.Kind.EX)
            runEx(editor, command, character, count);
        else if (command.getKind() == VimCommand.Kind.TEXT_OBJECT)
            runTextObject(editor, operator, command, count, countGiven);
        else if (operator != null)
            runOperator(editor, operator, command, count, countGiven, character);
        else
            run(editor, command, count, countGiven, character, depth);
        return true;
    }

    // ------------------------------------------------------------- search

    /**
     * What a command was in the middle of when {@code /} opened its prompt.
     *
     * Every other command runs to completion inside one keystroke; a search
     * cannot, because the pattern is typed somewhere else entirely and the
     * engine does not see those keys at all. So the half-built command is
     * parked here until the prompt says what was typed.
     */
    private static final class PendingSearch
    {
        final VimCommand operator;
        final int count;
        final boolean countGiven;
        final boolean forward;
        /** Where the caret and the window were, for incsearch to go back to. */
        final Position origin;
        final Line topLine;
        /** Whether incsearch has shown anything, which has to be put back. */
        boolean previewed;
        /** The pattern incsearch last showed, and the match it showed. */
        String typed;
        Position shown;
        /**
         * Where the search runs from once CTRL-G or CTRL-T has moved it:
         * just before the match they went to, so that Enter, and typing on,
         * find that match. Null until then, while the count still counts.
         */
        Position start;

        PendingSearch(VimCommand operator, int count, boolean countGiven,
                      boolean forward, Position origin, Line topLine)
        {
            this.operator = operator;
            this.count = count;
            this.countGiven = countGiven;
            this.forward = forward;
            this.origin = origin;
            this.topLine = topLine;
        }
    }

    private PendingSearch pendingSearch;

    /**
     * The line so far when there is no prompt to type it into, and null when
     * there is. A frameless editor has no location bar, so the keys go here
     * instead of to a text field -- which is also what lets a headless test
     * type {@code /foo<CR>} or {@code :s/a/b<CR>} as one sequence.
     */
    private StringBuilder typedLine;

    /** What {@link #typedLine} will be used for once Enter arrives. */
    private enum LineKind { SEARCH, EX }

    private LineKind typedKind = LineKind.SEARCH;

    /** / and ?: park the command and hand the keyboard to the prompt. */
    private void startSearch(Editor editor, VimCommand operator,
                             VimCommand command, int count, boolean countGiven)
    {
        final boolean forward = command.getBoolean("forward");
        final Position dot = editor.getDot();
        pendingSearch = new PendingSearch(operator, count, countGiven, forward,
            dot == null ? null : new Position(dot), editor.getTopLine());
        // A replay has the pattern in its own keys, so it must not put a
        // prompt on screen and wait for someone to type it again.
        if (replaying || !VimSearchPrompt.open(editor, this, forward))
            collectHere(LineKind.SEARCH);
    }

    /**
     * : takes the keyboard the same way, but parks no command: an ex line is
     * read and run on its own rather than completing something half-typed.
     */
    /**
     * The two EX-kind commands: {@code :} opens the prompt, {@code @} repeats.
     *
     * {@code @} is the macro command, and macros are not built: only the
     * {@code :} register it can name exists, so {@code @a} does nothing,
     * which is also what vim does with an empty register.
     */
    private void runEx(Editor editor, VimCommand command, String character,
                       int count)
    {
        if (command.getCommand().equals("repeatRegister")) {
            if (":".equals(character))
                repeatEx(editor, count);
            return;
        }
        startEx(editor);
    }

    private void startEx(Editor editor)
    {
        // From visual mode vim leaves the selection and fills the line in
        // with its range, so that :d acts on what was selected rather than
        // on the line the caret happens to be on.
        String seed = "";
        if (state.getMode().isVisual()) {
            VimVisual.leave(editor, state);
            seed = "'<,'>";
        }
        exPromptOpen = !replaying && VimExPrompt.open(editor, this, seed);
        if (!exPromptOpen) {
            collectHere(LineKind.EX);
            typedLine.append(seed);
        }
    }

    /** True while the : prompt is open. */
    private boolean exPromptOpen;

    private void collectHere(LineKind kind)
    {
        typedLine = new StringBuilder();
        typedKind = kind;
    }

    /**
     * Takes a key as part of a pattern being typed here rather than at a
     * prompt.
     *
     * @return the result to report, or null if this key is not ours
     */
    private Result collectLine(Editor editor, int keyCode, char keyChar)
    {
        if (typedLine == null)
            return null;
        if (keyCode == KeyEvent.VK_ENTER) {
            final String line = typedLine.toString();
            final LineKind kind = typedKind;
            typedLine = null;
            if (kind == LineKind.EX)
                exEntered(editor, line);
            else
                searchEntered(editor, line);
            return Result.CONSUMED;
        }
        if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (typedLine.length() > 0)
                typedLine.setLength(typedLine.length() - 1);
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            typedLine.append(keyChar);
        }
        // As the prompt does, but not for keys replayed: vim's incsearch is
        // for typing.
        if (typedKind == LineKind.SEARCH && !replaying)
            searchTyped(editor, typedLine.toString());
        return Result.CONSUMED;
    }

    /**
     * A chord typed at the / prompt: what the c map binds it to, as CTRL-G.
     * Public for the prompt, which has the keyboard while a pattern is typed.
     *
     * @param modifiers j's, as {@code Utilities.keyModifiers} gives them
     * @return false when it is not bound, so it goes to the prompt
     */
    public boolean runCommandLineKey(Editor editor, int keyCode, char keyChar,
                                     int modifiers)
    {
        return isChorded(modifiers) && runCommandLineKey(editor,
            KeyNotation.name(keyCode, keyChar, modifiers), 0);
    }

    /**
     * One key in the c map. A key-to-key row, as a vimrc's
     * {@code cmap <C-j> <C-g>} makes, stands for the c map key it names.
     */
    private boolean runCommandLineKey(Editor editor, String key, int depth)
    {
        final KeyStrokeTrie.Match<VimCommand> match = keyMap
            .getTrie(MappingMode.COMMAND_LINE)
            .match(Collections.singletonList(key));
        if (match.status != KeyStrokeTrie.Status.FULL)
            return false;
        final VimCommand command = match.value;
        if (command.getKind() == VimCommand.Kind.KEY_TO_KEY)
            return depth < MAX_KEY_TO_KEY_DEPTH
                && runCommandLineKey(editor, command.getCommand(), depth + 1);
        run(editor, command, 1, false, match.character, 1);
        return true;
    }

    /** True while a / or ? is waiting for its pattern. */
    public boolean isAwaitingSearchPattern()
    {
        return pendingSearch != null;
    }

    /**
     * Supplies the pattern a waiting {@code /} or {@code ?} asked for, and
     * finishes the command that was parked.
     *
     * A search is an exclusive motion, so with an operator waiting this is
     * the same range machinery any other motion would go through. Public
     * because the prompt that calls it is a separate object -- and because it
     * is the seam a test drives, there being no location bar to type into.
     */
    public void searchEntered(Editor editor, String pattern)
    {
        final PendingSearch pending = pendingSearch;
        pendingSearch = null;
        typedLine = null;
        // The search runs from where it was typed, not from the preview.
        endPreview(editor, pending);
        if (pending == null || pattern == null || pattern.isEmpty()) {
            searchCancelled(editor);
            return;
        }

        // The pattern keys never went through dispatch, so add them to the
        // recording by hand or '.' would replay a bare "d/" and hang on a
        // prompt that never closes.
        if (!replaying)
            recording.append(pattern).append("<CR>");

        final VimSearch.Query query =
            new VimSearch.Query(pattern, pending.forward, false);
        state.setLastSearch(editor, query);
        moveToMatch(editor, query, pending.operator, searchCount(pending),
                    pending.countGiven, pending.start);
        // This ran outside dispatch, so finish the command here: otherwise
        // the edited flag stays set and the next key typed is recorded as
        // the last change.
        afterCommand();
        resumeInsert(editor);
    }

    /** True while a : is waiting for its line. */
    public boolean isAwaitingExCommand()
    {
        return typedLine != null && typedKind == LineKind.EX;
    }

    /**
     * Runs a typed {@code :} line.
     *
     * Public for the same two reasons {@code searchEntered} is: the prompt
     * that calls it is a separate object, and it is the seam a test drives
     * when there is no location bar to type into.
     */
    public void exEntered(Editor editor, String line)
    {
        exPromptOpen = false;
        runExLine(editor, line);
        resumeInsert(editor);
    }

    private void runExLine(Editor editor, String line)
    {
        typedLine = null;
        if (line == null || line.isEmpty())
            return;
        // Not added to the recording. Vim's '.' repeats the last *change*,
        // and an ex command is not one: after :s the dot still replays
        // whatever was changed before it, which nvim confirms. @: is what
        // repeats an ex command, and it reads lastEx below.
        try {
            final VimEx.Command command = VimEx.parse(editor, state, line);
            if (!VimExCommands.run(editor, state, command))
                runJCommand(editor, command);
            lastEx = line;
        }
        catch (VimEx.BadCommand e) {
            editor.status(e.getMessage());
        }
        // This ran outside dispatch, so finish the command here: otherwise
        // the edited flag stays set and the next key typed is recorded as
        // the last change.
        afterCommand();
    }

    /**
     * Hands a name j already knows to j's own command table.
     *
     * Only ever a bare name and its parameters, never the typed line:
     * {@code Editor.executeCommand} reads a leading {@code (} as a Lisp form
     * and anything with an {@code =} in it as a property assignment, which
     * would silently eat {@code :s/a=b/c/}.
     */
    private void runJCommand(Editor editor, VimEx.Command command)
        throws VimEx.BadCommand
    {
        if (CommandTable.getCommand(command.name) == null)
            throw new VimEx.BadCommand(
                "E492: Not an editor command: " + command.name);
        // j's commands know nothing of ranges, so one given here would be
        // silently dropped and the command would run somewhere else entirely.
        // Say so rather than do the wrong thing quietly.
        if (command.range.given)
            throw new VimEx.BadCommand(
                "E481: No range allowed: " + command.name);
        if (command.bang)
            throw new VimEx.BadCommand("E477: No ! allowed");
        try {
            editor.execute(command.name,
                           command.args.isEmpty() ? null : command.args);
        }
        catch (NoSuchMethodException e) {
            throw new VimEx.BadCommand(
                "E492: Not an editor command: " + command.name);
        }
    }

    /**
     * The last ex line that ran, for {@code @:}.
     *
     * Vim keeps it in the {@code :} register. Only a line that ran is kept:
     * one that failed to parse is not worth repeating.
     */
    private String lastEx;

    /** {@code @:} -- run the last ex line again. */
    void repeatEx(Editor editor, int count)
    {
        if (lastEx == null)
            return;
        final String line = lastEx;
        for (int i = 0; i < count; i++)
            runExLine(editor, line);
    }

    /** The : prompt was abandoned. */
    public void exCancelled(Editor editor)
    {
        exPromptOpen = false;
        typedLine = null;
        builder.reset();
        resumeInsert(editor);
    }

    /** The prompt was abandoned, so the command it belonged to is too. */
    /**
     * incsearch: the pattern typed so far, shown as the prompt is typed in
     * -- the caret on the match it would find, and with hlsearch every
     * match highlighted. Public as the seam the prompt calls and a test
     * drives. A pattern that is bad or not found puts the caret back.
     */
    public void searchTyped(Editor editor, String pattern)
    {
        final PendingSearch pending = pendingSearch;
        if (pending == null || pending.origin == null
            || !VimKeyMap.getSharedOptions().isOn("incsearch"))
            return;
        pending.typed = pattern;
        final VimSearch.Query query = pattern == null || pattern.isEmpty()
            ? null : new VimSearch.Query(pattern, pending.forward, false);
        Position to = null;
        if (query != null) {
            try {
                to = VimSearch.find(editor, query, searchStart(pending),
                                    searchCount(pending));
            }
            catch (VimSearch.BadPattern e) {
                // Half typed, as \( is on its way to \(a\).
            }
        }
        showPreview(editor, pending, query, to);
    }

    /**
     * CTRL-G and CTRL-T with incsearch: the match after the one shown, or
     * the one before, whichever way the search goes, wrapping as it does.
     */
    public void searchStep(Editor editor, boolean forward)
    {
        final PendingSearch pending = pendingSearch;
        if (pending == null || pending.shown == null)
            return;
        final Position to = VimSearch.find(editor,
            new VimSearch.Query(pending.typed, forward, false), pending.shown,
            1);
        if (to == null)
            return;
        final Position start = new Position(to);
        if (pending.forward)
            start.prev();
        else
            start.next();
        pending.start = start;
        showPreview(editor, pending,
                    new VimSearch.Query(pending.typed, pending.forward, false),
                    to);
    }

    /** Where the search runs from: where it was typed, or where it moved. */
    private static Position searchStart(PendingSearch pending)
    {
        return pending.start != null ? pending.start : pending.origin;
    }

    /** A count goes to the count'th match, until CTRL-G or CTRL-T steps. */
    private static int searchCount(PendingSearch pending)
    {
        return pending.start != null ? 1 : pending.count;
    }

    /** The caret on the match found, or back where it was for none. */
    private void showPreview(Editor editor, PendingSearch pending,
                             VimSearch.Query query, Position to)
    {
        pending.shown = to;
        pending.previewed = true;
        state.setSearchPreview(editor, query, to);
        if (to != null)
            showCaretAt(editor, to);
        else
            putBack(editor, pending);
    }

    /** Takes the incsearch preview away: the caret and window as they were. */
    private void endPreview(Editor editor, PendingSearch pending)
    {
        if (pending == null || !pending.previewed)
            return;
        pending.previewed = false;
        state.setSearchPreview(editor, null, null);
        putBack(editor, pending);
    }

    /** The caret and the window where they were when the search began. */
    private static void putBack(Editor editor, PendingSearch pending)
    {
        showCaretAt(editor, pending.origin);
        editor.setTopLine(pending.topLine);
    }

    /** Moves the caret for incsearch: no jump, the selection kept. */
    private static void showCaretAt(Editor editor, Position pos)
    {
        editor.setDot(pos.getLine(), pos.getOffset());
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    public void searchCancelled(Editor editor)
    {
        dropPartialCommand(editor);
        // Else the d of an abandoned d/ stays recorded, and the next change
        // is appended to it: . would then open a prompt nobody sees.
        if (!replaying)
            clearRecording();
        resumeInsert(editor);
    }

    /**
     * Runs the found match as a motion, with or without an operator.
     *
     * For {@code /} once its prompt closes. The search runs from the caret,
     * or from {@code searchFrom} where CTRL-G or CTRL-T moved it; the motion
     * is from the caret either way.
     */
    private void moveToMatch(Editor editor, VimSearch.Query query,
                             VimCommand operator, int count,
                             boolean countGiven, Position searchFrom)
    {
        final Position from = editor.getDot();
        if (from == null)
            return;
        final Position to;
        try {
            to = VimSearch.find(editor, query,
                                searchFrom != null ? searchFrom : from, count);
        }
        catch (VimSearch.BadPattern e) {
            editor.status("Bad pattern: " + e.getMessage());
            return;
        }
        if (to == null) {
            editor.status("Pattern not found: " + query.pattern);
            return;
        }

        if (operator != null) {
            final VimRange range = RangeNormalizer.normalize(
                new Position(from), to, MotionKind.CHARWISE_EXCLUSIVE,
                query.forward);
            applyOperator(editor, operator, deleteRule(operator, range),
                          count, countGiven, null);
            return;
        }
        state.jumped(editor, from);
        state.clearSelectionUnlessVisual(editor);
        final Line was = from.getLine();
        editor.setDot(to.getLine(), to.getOffset());
        editor.moveCaretToDotCol();
        state.clampCaret(editor);
        state.clearDesiredColumn();
        editor.updateDotLine();
        state.motionChangedSelection(editor, was, editor.getDotLine());
    }

    /**
     * Applies a text object: to the pending operator, or to the selection if
     * we are in visual mode.
     *
     * Bound only in operator-pending and visual, so there is no third case --
     * a bare {@code iw} in normal mode never reaches here.
     */
    private void runTextObject(Editor editor, VimCommand operator,
                               VimCommand command, int count,
                               boolean countGiven)
    {
        final VimTextObjects.TextObject object =
            VimTextObjects.get(command.getCommand());
        if (object == null) {
            Log.error("vim: no text object named " + command.getCommand());
            return;
        }
        final Position from = editor.getDot();
        if (from == null)
            return;
        final MotionContext ctx = new MotionContext(this, editor, state, count,
                                                    countGiven, command, null,
                                                    operator != null);
        final VimRange range = object.range(ctx, from, ctx.arg("inner"));
        if (range == null)
            return;

        if (operator != null) {
            applyOperator(editor, operator, deleteRule(operator, range),
                          count, countGiven, null);
            return;
        }
        selectRange(editor, range);
    }

    /** Makes a text object's span the visual selection. */
    private void selectRange(Editor editor, VimRange range)
    {
        // What the selection covered before, so that lines it no longer
        // covers get painted back. Nothing below sets an update flag, and a
        // text object can shrink a selection as easily as grow it.
        final Position wasMark = editor.getMark();
        final Line wasFrom = wasMark != null ? wasMark.getLine()
                                             : editor.getDotLine();
        final Line wasTo = editor.getDotLine();

        editor.setDot(new Position(range.start));
        editor.setMarkAtDot();
        // The selection is mark..dot and vim's includes the character under
        // the caret, so the caret sits one short of the range's open end.
        final Position last = range.linewise
            ? new Position(range.last, range.last.length())
            : new Position(range.end);
        if (!range.linewise && !range.start.equals(last))
            last.prev();
        editor.setDot(last);
        editor.moveCaretToDotCol();
        // A linewise object -- ip, or a block alone on its lines -- selects
        // whole lines, which is visual line mode rather than a selection that
        // happens to span them.
        if (range.linewise)
            state.setMode(editor, VimMode.VISUAL_LINE);
        state.clampCaret(editor);
        editor.updateDotLine();
        if (wasFrom != range.start.getLine() || wasTo != editor.getDotLine()
            || range.start.getLine() != editor.getDotLine())
            state.selectionReshaped(editor);
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
            // D, C, S, R, X and Y take whole lines even from a charwise
            // selection; from a block, D and C take to the end of each line
            // and X and Y the block itself, as nvim does.
            final boolean block = state.getMode() == VimMode.VISUAL_BLOCK;
            if (block && operator.getBoolean("blockToEol"))
                state.setDesiredColumn(VimState.STICKY_EOL);
            else if (operator.getBoolean("linewise")
                     && !(block && operator.getBoolean("blockAsIs")))
                state.setMode(editor, VimMode.VISUAL_LINE);
            state.setOperatorStart(VimVisual.operatorStart(editor, state));
            final VimRange range = VimVisual.take(editor, state);
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
        boolean forceInclusive = false;

        // cw and cW change to the end of the word rather than to the start of
        // the next one, so that the space after the word survives.
        if (isChangeWord(operator, motionCommand, editor)) {
            effective = VimKeyMap.parse(
                "o w motion moveByWords forward,wordEnd,inclusive"
                + (motionCommand.getBoolean("bigWord") ? ",bigWord" : ""));
            forceInclusive = true;
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
        // Asked before the move, since a motion may update the state its kind
        // depends on -- f sets the search that a later ';' reads.
        final MotionKind kind = forceInclusive ? MotionKind.CHARWISE_INCLUSIVE
                                               : motion.kindOf(ctx);
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
            // Not when the motion ran out and clamped: the clip is for a w
            // that reached the next word's line, and there was no such word.
            if (!ctx.clampedToBufferEnd)
                RangeNormalizer.clipWordMotionAtLineEnd(from, to);
        }

        final VimRange range = RangeNormalizer.normalize(
            new Position(from), to, kind, effective.getBoolean("forward"));
        applyOperator(editor, operator, deleteRule(operator, range),
                      count, countGiven, character);
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

    /** An operator-pending d, not a visual one, may take whole lines. */
    private static VimRange deleteRule(VimCommand operator, VimRange range)
    {
        return operator.getCommand().equals("delete")
            ? RangeNormalizer.deleteRange(range) : range;
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
        if (isEdit(operator) && !editor.checkReadOnly())
            return;
        if (isEdit(operator) && !replaying)
            edited = true;
        op.apply(new MotionContext(this, editor, state, count, countGiven,
                                   operator, character),
                 range);
        // Not every operator asks for it: y changes nothing to undo.
        state.takeOperatorStart();
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
                // Whatever was bound: one of j's own named commands, which
                // the modal layer knows nothing about. Not through
                // executeCommand, which reads a leading ( as a Lisp form and
                // an = as a property assignment -- the same trap the ex
                // parser avoids.
                // A row may pass its command an argument -- param=vim, for
                // pageDown -- and a count runs it that many times, which is
                // what 2 CTRL-F means -- unless the row says once, as
                // CTRL-^ does: twice there would be back where it started.
                final String param = command.getString("param", null);
                final int times =
                    command.getBoolean("once") ? 1 : Math.max(1, count);
                boolean ran = true;
                try {
                    for (int i = 0; i < times && ran; i++)
                        ran = editor.execute(command.getCommand(), param);
                }
                catch (NoSuchMethodException e) {
                    ran = false;
                }
                // execute() throws for some unknown names and quietly
                // returns false for others; either way, say so.
                // A mapping from a vimrc may name a vim ex command instead.
                if (!ran && command.getString("ex", null) != null) {
                    exEntered(editor, command.getString("ex", null));
                    break;
                }
                if (!ran)
                    editor.status("E492: Not an editor command: "
                                  + command.getCommand());
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
        // G, /, 'a and the rest: vim's jumps. After the move is worked out,
        // since '' goes to the jump before this one.
        if (command.getBoolean("jump"))
            state.jumped(editor, from);
        final Line was = from.getLine();
        state.clearSelectionUnlessVisual(editor);
        editor.setDot(to.getLine(), to.getOffset());
        editor.moveCaretToDotCol();
        state.clampCaret(editor);
        rememberColumn(editor, command);
        editor.updateDotLine();
        state.motionChangedSelection(editor, was, editor.getDotLine());
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
        // A read-only buffer is not changed at all, as vim's nomodifiable.
        if (isEdit(command) && !editor.checkReadOnly())
            return;
        if (isEdit(command) && !replaying)
            edited = true;
        action.run(ctx);
        // An action that sets the column itself says so, as o does after $.
        if (!command.getBoolean("keepColumn"))
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
        // A noremap's keys, and the table's own rows, mean what they do built
        // in: vnoremap < <gv shifts rather than finding itself again.
        final boolean wasBuiltInOnly = builtInOnly;
        builtInOnly = wasBuiltInOnly || !command.getBoolean("remap");
        try {
            for (int i = 0; i < keys.size(); i++) {
                if (i == 0 && countGiven)
                    for (char digit : Integer.toString(count).toCharArray())
                        builder.acceptCountDigit(String.valueOf(digit));
                dispatch(editor, keys.get(i), depth + 1);
            }
        }
        finally {
            builtInOnly = wasBuiltInOnly;
        }
    }

    /** While keys a mapping stands for are dispatched without remapping. */
    private boolean builtInOnly;

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
