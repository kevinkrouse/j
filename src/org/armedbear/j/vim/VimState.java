/*
 * VimState.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Everything modal editing has to remember between keystrokes.
 *
 * One per {@link Editor}, not per buffer: a mode belongs to the window you are
 * typing in, the way it does in vim. It cannot live on the {@code Mode} either,
 * since j shares one {@code Mode} instance across every buffer of that
 * language, in every frame.
 */
public final class VimState
{
    private VimMode mode = VimMode.NORMAL;

    /**
     * The undo step covering the current insert session, and the buffer whose
     * undo manager holds it.
     *
     * Vim undoes a whole insert -- from {@code i} to {@code <Esc>}, newlines
     * and autoindent included -- in one step, which j expresses as a compound
     * edit. The buffer is kept alongside because a compound edit belongs to one
     * buffer's undo manager, and leaving one open across a buffer switch would
     * silently swallow every later edit in that buffer into the same step.
     */
    private CompoundEdit insertEdit;
    private Buffer insertEditBuffer;

    public VimMode getMode()
    {
        return mode;
    }

    /** Enters a mode, leaving the previous one cleanly. */
    public void setMode(Editor editor, VimMode newMode)
    {
        if (mode == newMode)
            return;
        if (mode.isInsert() && !newMode.isInsert())
            endInsert(editor);
        mode = newMode;
    }

    // ------------------------------------------------------------ insert

    /**
     * Switches to insert mode, opening one undo step for the whole session.
     */
    public void beginInsert(Editor editor, VimMode insertMode)
    {
        endInsert(editor);
        final Buffer buffer = editor.getBuffer();
        if (buffer != null) {
            insertEdit = buffer.beginCompoundEdit();
            insertEditBuffer = buffer;
        }
        mode = insertMode;
    }

    /**
     * Closes the insert session's undo step. Doing it twice is harmless, which
     * matters because it has to be called from every way out of insert mode.
     */
    public void endInsert(Editor editor)
    {
        if (insertEdit == null)
            return;
        final CompoundEdit edit = insertEdit;
        final Buffer buffer = insertEditBuffer;
        insertEdit = null;
        insertEditBuffer = null;
        if (buffer != null)
            buffer.endCompoundEdit(edit);
    }

    /** True while an insert session's undo step is open. */
    public boolean isInsertEditOpen()
    {
        return insertEdit != null;
    }

    /**
     * Ends an insert session that the editor has moved away from.
     *
     * Switching buffers with {@code i} still active would otherwise leave the
     * compound edit open forever, and every subsequent edit to that buffer
     * would join the same undo step.
     */
    public void editorLeftBuffer(Editor editor)
    {
        endInsert(editor);
        mode = VimMode.NORMAL;
    }

    // ------------------------------------------------------------- caret

    /**
     * Puts the caret where normal mode allows it to be.
     *
     * In normal mode the caret sits <em>on</em> a character, so it cannot rest
     * past the last one; in insert mode it sits <em>between</em> characters, so
     * it can. j's own {@code restrictCaret} already stops the caret going past
     * the end of the line, which is the insert-mode rule -- normal mode needs
     * one column less than that, and nothing in j expresses it.
     */
    public void clampCaret(Editor editor)
    {
        if (!mode.isCommandMode())
            return;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final int last = Math.max(0, dot.getLineLength() - 1);
        if (dot.getOffset() > last) {
            // No undo record: this corrects where the caret may legally rest,
            // it is not a move the user asked for.
            editor.setDot(dot.getLine(), last);
            editor.moveCaretToDotCol();
        }
    }
}
