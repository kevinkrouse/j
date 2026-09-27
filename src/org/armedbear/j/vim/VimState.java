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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.Search;

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
        insertRepeat = 0;
        insertKeys.setLength(0);
        insertStartLine = -1;
        insertSplit = false;
        if (buffer != null) {
            insertModCount = buffer.getModCount();
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
    public void pushReplaced(int c, Line line, int caret)
    {
        if (replaced == null)
            return;
        replaced.appendCodePoint(c);
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
    public int popReplaced(Line line, int caret)
    {
        if (replaced == null || replaced.length() == 0)
            return 0;
        if (line != replacedLine || caret != replacedCaret) {
            replaced.setLength(0);
            return 0;
        }
        // A whole character, which may be a surrogate pair.
        final int c = replaced.codePointBefore(replaced.length());
        replaced.setLength(replaced.length() - Character.charCount(c));
        replacedCaret = caret - 1;
        return c;
    }

    /**
     * The line o, O, cc or S opened this insert session on. Vim takes the
     * indent it put there away again if Escape comes with nothing typed on
     * it -- CTRL-T and CTRL-D do not count -- so that o then Escape leaves an
     * empty line rather than one of blanks.
     */
    private Line autoIndentLine;

    /** Records the line the session opened with an indent of its own. */
    public void noteAutoIndent(Line line)
    {
        autoIndentLine = line;
    }

    /** Something was typed: the indent is the user's now. */
    public void forgetAutoIndent()
    {
        autoIndentLine = null;
    }

    /**
     * True when Escape should empty this line: the session opened it, nothing
     * was typed on it, and it holds only blanks.
     */
    public boolean isUntouchedAutoIndent(Line line)
    {
        if (line == null || line != autoIndentLine)
            return false;
        final String text = line.getText() == null ? "" : line.getText();
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) != ' ' && text.charAt(i) != '\t')
                return false;
        return true;
    }

    /**
     * How many more times Escape types what this session typed, and whether
     * each time opens a line first, as {@code 3o} does.
     */
    private int insertRepeat;
    private boolean insertRepeatOpensLine;
    /** The keys typed in this session, in key notation, for the repeat. */
    private final StringBuilder insertKeys = new StringBuilder();

    public void setInsertRepeat(int times, boolean opensLine)
    {
        insertRepeat = Math.max(0, times);
        insertRepeatOpensLine = opensLine;
    }

    /** The repeat count, which is spent by asking for it. */
    public int takeInsertRepeat()
    {
        final int times = insertRepeat;
        insertRepeat = 0;
        return times;
    }

    public boolean insertRepeatOpensLine()
    {
        return insertRepeatOpensLine;
    }

    public void noteInsertKey(String key)
    {
        insertKeys.append(key);
    }

    public String getInsertKeys()
    {
        return insertKeys.toString();
    }

    public int insertKeysLength()
    {
        return insertKeys.length();
    }

    /** Forgets the keys noted after the first length characters. */
    public void truncateInsertKeys(int length)
    {
        insertKeys.setLength(Math.min(length, insertKeys.length()));
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
        if (insertEdit == null)
            return;
        final CompoundEdit edit = insertEdit;
        final Buffer buffer = insertEditBuffer;
        insertEdit = null;
        insertEditBuffer = null;
        if (buffer != null)
            buffer.endCompoundEdit(edit);
    }

    /**
     * An arrow moved the caret in insert mode: close this undo step and open
     * another, and forget the keys and count, which no longer describe one
     * insert.
     */
    public void restartInsert()
    {
        insertRepeat = 0;
        insertKeys.setLength(0);
        insertStartLine = -1;
        insertSplit = true;
        if (insertEdit == null)
            return;
        insertModCount = insertEditBuffer.getModCount();
        insertEditBuffer.endCompoundEdit(insertEdit);
        insertEdit = insertEditBuffer.beginCompoundEdit();
    }

    /**
     * Where typing began, for '[ -- by number, since a Line does not survive
     * a Backspace that joins it to the one before. -1 until the first key.
     */
    private int insertStartLine = -1;
    private int insertStartOffset;
    /** The buffer's modification count when the session began. */
    private int insertModCount;
    /** True after an arrow or CTRL-O split the session. */
    private boolean insertSplit;

    /**
     * Notes where the caret is as where typing began, unless that is known.
     * Called with each insert-mode key: the command that entered insert mode
     * has finished moving the caret by then, whichever command it was.
     */
    public void noteInsertStart(Editor editor)
    {
        final Position dot = editor.getDot();
        if (insertStartLine >= 0 || dot == null)
            return;
        editor.getBuffer().renumber();
        insertStartLine = dot.lineNumber();
        insertStartOffset = dot.getOffset();
    }

    /**
     * A Backspace that joined the line where typing began to the one before
     * moves the start to the join, as in vim. One within the line does not.
     */
    public void insertDeletedBack(Editor editor)
    {
        final Position dot = editor.getDot();
        if (insertStartLine < 0 || dot == null)
            return;
        editor.getBuffer().renumber();
        final int line = dot.lineNumber();
        if (line < insertStartLine) {
            insertStartLine = line;
            insertStartOffset = dot.getOffset();
        }
    }

    /**
     * Where CTRL-W and CTRL-U stop on the way back from the caret: where
     * typing began, when that is earlier on the caret's line, else 0.
     */
    public int backStop(Editor editor)
    {
        final Position dot = editor.getDot();
        if (insertStartLine < 0 || dot == null)
            return 0;
        editor.getBuffer().renumber();
        if (dot.lineNumber() != insertStartLine
            || insertStartOffset >= dot.getOffset())
            return 0;
        return insertStartOffset;
    }

    /** Escape or CTRL-O: '^ where insert mode stopped, and the rest. */
    public void markInsertStop(Editor editor)
    {
        final Position stop = editor.getDot();
        if (stop != null)
            marks.set('^', editor.getBuffer(), stop);
        markInsert(editor, stop);
    }

    /**
     * '] where typing stopped, '[ where it began, and '. at the start -- or
     * at the start of the last line, if the insert made new ones -- when
     * the session changed anything. After a split with nothing typed since,
     * they are still those of the part before it.
     */
    public void markInsert(Editor editor, Position stop)
    {
        final Buffer buffer = editor.getBuffer();
        if (stop == null
            || (insertSplit && buffer.getModCount() == insertModCount))
            return;
        noteInsertStart(editor);
        Line line = VimEx.lineAt(editor, insertStartLine + 1);
        if (line == null)
            line = stop.getLine();
        final Position start =
            new Position(line, Math.min(insertStartOffset, line.length()));
        marks.set('[', buffer, start);
        marks.set(']', buffer, stop);
        if (buffer.getModCount() != insertModCount)
            marks.set('.', buffer, stop.getLine() == line ? start
                                    : new Position(stop.getLine(), 0));
    }

    /** True while an insert session's undo step is open. */
    public boolean isInsertEditOpen()
    {
        return insertEdit != null;
    }

    // ------------------------------------------------------------ CTRL-O

    /** The insert mode CTRL-O comes back to, or null. */
    private VimMode insertReturn;
    /** Vim's ins_at_eol: CTRL-O was typed at the end of this line. */
    private int insertReturnEolLine = -1;

    /**
     * CTRL-O: out of insert mode for one command. At the end of the line the
     * caret steps back onto the last character, as for Escape, but j and k
     * still aim past it.
     */
    public void leaveInsertForOneCommand(Editor editor)
    {
        insertReturn = mode;
        insertReturnEolLine = -1;
        desiredColumn = -1;
        setMode(editor, VimMode.NORMAL);
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Line line = dot.getLine();
        if (line.length() == 0 || dot.getOffset() < line.length())
            return;
        editor.getBuffer().renumber();
        insertReturnEolLine = dot.lineNumber();
        desiredColumn = Buffer.getCol(line, line.length(),
                                      editor.getBuffer().getTabWidth());
        editor.setDot(line, CodePoints.previous(line, line.length()));
        editor.moveCaretToDotCol();
    }

    public boolean isOneCommand()
    {
        return insertReturn != null;
    }

    /**
     * CTRL-O's command is over: back to insert mode, and past the end of the
     * line if the caret is on its last character and was at the end of this
     * line before, or j and k aim past it -- vim's ins_at_eol and curswant.
     * Split from what came before, as an arrow splits it.
     */
    public void resumeInsert(Editor editor)
    {
        final VimMode back = insertReturn;
        insertReturn = null;
        final Position dot = editor.getDot();
        if (dot != null) {
            final Line line = dot.getLine();
            editor.getBuffer().renumber();
            if (line.length() > 0
                && CodePoints.next(line, dot.getOffset()) == line.length()
                && (dot.lineNumber() == insertReturnEolLine
                    || desiredColumn > Buffer.getCol(line, dot.getOffset(),
                           editor.getBuffer().getTabWidth()))) {
                editor.setDot(line, line.length());
                editor.moveCaretToDotCol();
            }
        }
        beginInsert(editor, back);
        insertSplit = true;
    }

    /** CTRL-O's command began an insert of its own. */
    public void forgetOneCommand()
    {
        insertReturn = null;
    }

    /** As vim shows it: -- (insert) VISUAL -- during CTRL-O. */
    public String getModeIndicator()
    {
        if (insertReturn == null)
            return mode.getIndicator();
        final String back =
            "(" + insertReturn.getIndicator().toLowerCase(Locale.ROOT) + ")";
        return mode.getIndicator() == null ? back
                                           : back + " " + mode.getIndicator();
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
        insertReturn = null;
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

    /** Jump lists, one per buffer. */
    private final Map<Buffer, VimJumps> jumps = new HashMap<Buffer, VimJumps>();

    private VimJumps jumpsFor(Buffer buffer)
    {
        // A list holds its buffer, so a closed buffer's goes here, not by
        // weak reference.
        jumps.keySet().removeIf(
            b -> b != buffer && !Editor.getBufferList().contains(b));
        VimJumps list = jumps.get(buffer);
        if (list == null) {
            list = new VimJumps(buffer);
            jumps.put(buffer, list);
        }
        return list;
    }

    /**
     * A jump is leaving from: onto the jump list, and the previous context
     * mark, which '' and `` go back to.
     */
    public void jumped(Editor editor, Position from)
    {
        if (jumpsHeld > 0)
            return;
        jumpsFor(editor.getBuffer()).push(from);
        marks.set('\'', editor.getBuffer(), from);
    }

    /** While :g runs, which is one jump however many lines it visits. */
    private int jumpsHeld;

    public void holdJumps(boolean hold)
    {
        jumpsHeld += hold ? 1 : -1;
    }

    /**
     * CTRL-O and CTRL-I: the jump count entries away, or null. Leaving the
     * end of the list is a jump of its own, so '' comes back.
     */
    public Position travel(Editor editor, Position from, int count)
    {
        final VimJumps list = jumpsFor(editor.getBuffer());
        final boolean leaving = list.isAtEnd();
        final Position to = list.travel(from, count);
        if (to != null && leaving)
            marks.set('\'', editor.getBuffer(), from);
        return to;
    }

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

    public boolean hasPendingRegister()
    {
        return pendingRegister != 0;
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
        /** A code point: f can look for an emoji. */
        public final int target;
        public final boolean forward;
        public final boolean till;

        CharacterSearch(int target, boolean forward, boolean till)
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

    public void setLastCharacterSearch(int target, boolean forward,
                                       boolean till)
    {
        lastCharacterSearch = new CharacterSearch(target, forward, till);
    }

    // ------------------------------------------------------------ search

    /**
     * The pattern n and N repeat: j's last search
     * ({@link Editor#getLastSearch}), which its findNext repeats too, one for
     * every window unless the shareSearch preference says otherwise. A vim
     * search is kept there as the query it was; a find of j's own stands
     * for itself.
     */
    public VimSearch.Query getLastSearch(Editor editor)
    {
        return VimSearch.queryOf(editor.getLastSearch());
    }

    /**
     * A new pattern for n and N, whose matches hlsearch paints -- again after
     * a {@code :noh}, as any search shows them again in vim.
     */
    public void setLastSearch(Editor editor, VimSearch.Query query)
    {
        editor.setLastSearch(VimSearch.compileToKeep(query, editor));
    }

    /**
     * The last pattern compiled again, after {@code :set} changes what its
     * translation reads -- ignorecase, smartcase -- leaving it hidden if
     * {@code :noh} had hidden it.
     */
    public void recompileLastSearch(Editor editor)
    {
        final VimSearch.Query query = getLastSearch(editor);
        if (query == null || query.own != null)
            return;
        final boolean hidden = editor.isSearchHighlightHidden();
        setLastSearch(editor, query);
        editor.setSearchHighlightHidden(hidden);
    }

    // ---------------------------------------------------------- hlsearch

    /**
     * With hlsearch, the matches to paint on a line, as offset pairs, or
     * null for none: of the pattern being typed, or of the last one unless
     * {@code :noh} has hidden them.
     */
    public int[] searchMatches(Editor editor, Line line)
    {
        if (!VimKeyMap.getSharedOptions().isOn("hlsearch"))
            return null;
        return preview != null ? previewMatches(editor, line)
                               : editor.lastSearchMatches(line);
    }

    /** The match incsearch has the caret on, if it is on this line. */
    public int[] currentSearchMatch(Editor editor, Line line)
    {
        if (preview == null || line != previewAt.getLine())
            return null;
        final int[] spans = previewMatches(editor, line);
        if (spans != null)
            for (int i = 0; i < spans.length; i += 2)
                if (spans[i] == previewAt.getOffset())
                    return new int[] {spans[i], spans[i + 1]};
        return null;
    }

    /** The pattern being typed, compiled once for all the lines painted. */
    private Search previewSearch;
    private String previewKey;

    private int[] previewMatches(Editor editor, Line line)
    {
        final String key = VimSearch.compiledKey(preview);
        if (!key.equals(previewKey)) {
            previewKey = key;
            previewSearch = VimSearch.compileQuietly(preview, editor);
        }
        return previewSearch == null ? null
            : previewSearch.matchesOnLine(editor.getBuffer().getMode(), line);
    }

    // --------------------------------------------------------- incsearch

    /** The pattern incsearch is showing while it is typed, and its match. */
    private VimSearch.Query preview;
    private Position previewAt;

    /** Shows a pattern being typed, or with null stops showing one. */
    public void setSearchPreview(Editor editor, VimSearch.Query query,
                                 Position at)
    {
        preview = at == null ? null : query;
        previewAt = at;
        editor.repaintDisplay();
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

    /** True after $, while the caret stands for the end of the line. */
    public boolean isStickyEol()
    {
        return desiredColumn == STICKY_EOL;
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
        final int last = CodePoints.snap(dot.getLine(),
                                         Math.max(0, dot.getLineLength() - 1));
        final int at = CodePoints.snap(dot.getLine(), dot.getOffset());
        if (dot.getOffset() > last || at != dot.getOffset()) {
            // No undo record: this corrects where the caret may legally rest,
            // it is not a move the user asked for.
            editor.setDot(dot.getLine(), Math.min(at, last));
            editor.moveCaretToDotCol();
        }
    }
}
