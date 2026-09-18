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

    // ----------------------------------------------------------- marks

    private final VimMarks marks = new VimMarks();

    public VimMarks getMarks()
    {
        return marks;
    }

    // ------------------------------------------------------- registers

    private char pendingRegister;

    /** Names the register the next yank, delete or put should use. */
    public void setPendingRegister(char name)
    {
        pendingRegister = name;
    }

    /**
     * The register named for this command, and forgets it.
     *
     * Returns 0 when none was named, which every caller reads as "the usual
     * ones": the unnamed register plus whichever of 0, 1-9 or - applies.
     */
    public char takePendingRegister()
    {
        final char name = pendingRegister;
        pendingRegister = 0;
        return name;
    }

    // ------------------------------------------------- character search

    /** The f, F, t or T that ';' and ',' repeat. */
    public static final class CharacterSearch
    {
        public final char target;
        public final boolean forward;
        public final boolean till;

        CharacterSearch(char target, boolean forward, boolean till)
        {
            this.target = target;
            this.forward = forward;
            this.till = till;
        }
    }

    private CharacterSearch lastCharacterSearch;

    public CharacterSearch getLastCharacterSearch()
    {
        return lastCharacterSearch;
    }

    public void setLastCharacterSearch(char target, boolean forward,
                                       boolean till)
    {
        lastCharacterSearch = new CharacterSearch(target, forward, till);
    }

    // ---------------------------------------------------- desired column

    /**
     * The screen column j and k are trying to get back to.
     *
     * Moving down through a short line and on to a long one returns to the
     * column you started from, rather than to wherever the short line ended.
     * {@link #STICKY_EOL} is vim's curswant=MAXCOL, which is what $ sets so
     * that j and k keep following the end of each line.
     *
     * Negative means "not set": the next vertical motion takes it from
     * wherever the caret is.
     */
    public static final int STICKY_EOL = Integer.MAX_VALUE;

    private int desiredColumn = -1;

    public int getDesiredColumn(Editor editor, Position from)
    {
        if (desiredColumn < 0)
            desiredColumn = Buffer.getCol(from.getLine(), from.getOffset(),
                                          editor.getBuffer().getTabWidth());
        return desiredColumn;
    }

    public void setDesiredColumn(int column)
    {
        desiredColumn = column;
    }

    /** Forgets the column, so the next j or k takes it from the caret. */
    public void clearDesiredColumn()
    {
        desiredColumn = -1;
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
