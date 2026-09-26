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
import org.armedbear.j.Constants;
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
     * The handler this state belongs to.
     *
     * One per editor, as this is. Commands that have to feed keys back
     * through the engine -- {@code :normal} is the first -- need a way back
     * to it from wherever they are.
     */
    private VimInputHandler handler;

    void setHandler(VimInputHandler handler)
    {
        this.handler = handler;
    }

    VimInputHandler getHandler()
    {
        return handler;
    }

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

    /**
     * What R has typed over, most recent last, so that BS can put it back.
     *
     * {@link #APPENDED} stands for a keystroke that landed past the end of
     * the line and so overwrote nothing; BS takes that one away instead of
     * restoring anything.
     */
    private StringBuilder replaced;

    /**
     * Where the last replace keystroke left the caret.
     *
     * The record of what R typed over only lines up with the text while the
     * caret is still where R left it. An arrow key, a mouse click or one of
     * j's own commands bound to a modified key can move it without this
     * layer seeing anything, so BS checks rather than assumes.
     */
    private Line replacedLine;
    private int replacedCaret;

    /**
     * Marks a replace keystroke that added a character rather than typing
     * over one. Not a character anyone can type: U+FFFF is a noncharacter,
     * and it is also what AWT means by "this key produced none".
     */
    public static final char APPENDED = '￿';

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
        caretShapeChanged(editor);
    }

    /**
     * Asks for the caret's line to be repainted.
     *
     * The caret changes shape with the mode -- a block for commands, a bar for
     * typing -- so a mode change has to redraw it even though nothing moved
     * and no text changed.
     */
    private static void caretShapeChanged(Editor editor)
    {
        if (editor.getDot() != null)
            editor.updateDotLine();
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
        replaced = insertMode == VimMode.REPLACE ? new StringBuilder() : null;
        replacedLine = null;
        caretShapeChanged(editor);
    }

    /**
     * Notes what a replace keystroke typed over, or {@link #APPENDED}, and
     * where it left the caret.
     */
    public void pushReplaced(char c, Line line, int caret)
    {
        if (replaced == null)
            return;
        replaced.append(c);
        replacedLine = line;
        replacedCaret = caret;
    }

    /**
     * Takes back the last replace keystroke, or 0 if there is none to take.
     *
     * None to take means BS has reached where R started, or the caret is no
     * longer where the last keystroke left it -- an arrow, a click, a
     * mapping that ran one of j's own commands. Vim has nothing of its own
     * to restore in either case, and a remembered character written at a
     * column R never visited would silently corrupt the line. BS always
     * lands one column left, so that is where the next one expects to be.
     */
    public char popReplaced(Line line, int caret)
    {
        if (replaced == null || replaced.length() == 0)
            return 0;
        if (line != replacedLine || caret != replacedCaret) {
            replaced.setLength(0);
            return 0;
        }
        final char c = replaced.charAt(replaced.length() - 1);
        replaced.setLength(replaced.length() - 1);
        replacedCaret = caret - 1;
        return c;
    }

    /**
     * An indent this insert session put on a line without the user typing it
     * -- by o, O, cc or S -- and the line it is on. Vim takes such an indent
     * away again if Escape comes with nothing typed after it, so that o then
     * Escape leaves an empty line rather than one of blanks.
     */
    private Line autoIndentLine;
    private String autoIndentText;

    /** Records an indent the session put there itself. */
    public void noteAutoIndent(Line line, String indent)
    {
        if (line != null && indent != null && !indent.isEmpty()) {
            autoIndentLine = line;
            autoIndentText = indent;
        }
    }

    /**
     * True when this line holds nothing but the indent the session put on it:
     * nothing was typed after it, so Escape should take it away.
     */
    public boolean isUntouchedAutoIndent(Line line)
    {
        return line != null && line == autoIndentLine
            && autoIndentText.equals(line.getText());
    }

    /**
     * Closes the insert session's undo step. Doing it twice is harmless, which
     * matters because it has to be called from every way out of insert mode.
     */
    public void endInsert(Editor editor)
    {
        replaced = null;
        replacedLine = null;
        autoIndentLine = null;
        autoIndentText = null;
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
        caretShapeChanged(editor);
    }

    // ---------------------------------------------------------- visual

    /** Where a visual selection was, so that gv can put it back. */
    public static final class Selection
    {
        public final int anchorLine;
        public final int anchorOffset;
        public final int headLine;
        public final int headOffset;
        public final VimMode mode;

        Selection(int anchorLine, int anchorOffset, int headLine,
                  int headOffset, VimMode mode)
        {
            this.anchorLine = anchorLine;
            this.anchorOffset = anchorOffset;
            this.headLine = headLine;
            this.headOffset = headOffset;
            this.mode = mode;
        }
    }

    private Selection lastSelection;

    public Selection getLastSelection()
    {
        return lastSelection;
    }

    public void rememberSelection(int anchorLine, int anchorOffset,
                                  int headLine, int headOffset, VimMode mode)
    {
        lastSelection = new Selection(anchorLine, anchorOffset, headLine,
                                      headOffset, mode);
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

    /** Drops a register name named but never used, e.g. by Escape. */
    public void clearPendingRegister()
    {
        pendingRegister = 0;
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

    // ------------------------------------------------------------ search

    /**
     * The pattern n and N repeat.
     *
     * Its own field rather than {@code Editor.lastSearch}: that one has no
     * direction, is shared with j's own find commands, and may hold a
     * {@code FindInFiles} rather than a plain search.
     */
    private VimSearch.Query lastSearch;

    public VimSearch.Query getLastSearch()
    {
        return lastSearch;
    }

    public void setLastSearch(VimSearch.Query query)
    {
        lastSearch = query;
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

    // --------------------------------------------------------- selection

    /**
     * Drops any selection, unless one is meant to be there.
     *
     * Outside visual mode vim has no selection, but j does: it is one mark and
     * one dot, and several things set a mark without meaning to start a
     * selection. An operator sets one so that it can reuse j's region delete,
     * and j's undo records the mark along with the text -- so undoing a delete
     * restores the operator's scratch mark and leaves a selection nobody asked
     * for, which then grows with every motion.
     *
     * <p>Through {@code unmark} rather than {@code setMark(null)}, because
     * only the former asks for the highlight to be painted out.
     */
    public void clearSelectionUnlessVisual(Editor editor)
    {
        if (!mode.isVisual())
            editor.unmark();
    }

    /**
     * Asks for the whole display when a selection has changed shape across
     * lines.
     *
     * j repaints by line, and a motion marks only the line the caret left and
     * the one it arrived on. That is enough for a selection grown a step at a
     * time, but not for one that jumps: {@code v3j} covers four lines and
     * would repaint the first and the last, leaving the two in between with no
     * highlight on them. Which lines a selection covers now is not something
     * the motion knows line by line, so the window is the unit.
     *
     * <p>Also what a shape change without any motion needs -- switching
     * between {@code v} and {@code V}, or {@code gv} -- since every line the
     * selection spans can change how much of itself is covered, not only the
     * two the caret and anchor sit on.
     */
    public void selectionCrossedLines(Editor editor, Line before, Line after)
    {
        if (before != after)
            selectionReshaped(editor);
    }

    /** The same, for a caller that has already decided the shape changed. */
    public void selectionReshaped(Editor editor)
    {
        if (mode.isVisual())
            editor.setUpdateFlag(Constants.REPAINT);
    }

    /**
     * The motion-specific version of {@link #selectionCrossedLines}.
     *
     * Called on every motion in visual mode, so it is worth sparing the whole
     * window for the common case: an ordinary single-line step (j, k, an
     * adjacent word motion) marks just the line the caret left -- the one it
     * arrived on is already marked by {@code updateDotLine()} -- instead of
     * repainting everything on screen. A motion that jumps further than one
     * line falls back to {@link #selectionCrossedLines}, for the same reason
     * given there.
     */
    public void motionChangedSelection(Editor editor, Line before, Line after)
    {
        if (!mode.isVisual() || before == after)
            return;
        if (before.next() == after || before.previous() == after) {
            editor.update(before);
            return;
        }
        editor.setUpdateFlag(Constants.REPAINT);
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
