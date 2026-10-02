/*
 * VimVisual.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.Block;
import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Visual mode, expressed with j's own selection.
 *
 * The anchor is j's mark and the moving end is j's dot, so the selection j
 * paints and the selection vim thinks it has cannot drift apart, and every
 * motion extends it without knowing anything about visual mode.
 *
 * <p>One difference has to be bridged: vim's characterwise selection includes
 * the character under the caret, and j's region from mark to dot does not. So
 * a selection covering one character has mark and dot equal, and turning the
 * selection into a range for an operator adds that character back.
 */
final class VimVisual
{
    private VimVisual()
    {
    }

    static void enter(Editor editor, VimState state, VimMode mode)
    {
        if (!state.getMode().isVisual())
            editor.setMarkAtDot();
        state.setMode(editor, mode);
        // Switching between v and V changes how much of every selected line is
        // covered, not only the caret's.
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor != null && head != null)
            state.selectionCrossedLines(editor, anchor.getLine(),
                                        head.getLine());
    }

    static void leave(Editor editor, VimState state)
    {
        remember(editor, state);
        // unmark, not setMark(null): clearing the mark changes the model but
        // paints nothing, so the highlight would stay on screen until
        // something else happened to force a repaint.
        editor.unmark();
        state.setMode(editor, VimMode.NORMAL);
        state.clampCaret(editor);
    }

    /** Records the selection for gv, and for the '&lt; and '&gt; marks. */
    static void remember(Editor editor, VimState state)
    {
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return;
        state.rememberSelection(anchor.lineNumber(), anchor.getOffset(),
                                head.lineNumber(), head.getOffset(),
                                state.getMode());
        final Position start = anchor.isBefore(head) ? anchor : head;
        final Position end = anchor.isBefore(head) ? head : anchor;
        state.getMarks().set('<', editor.getBuffer(), start);
        state.getMarks().set('>', editor.getBuffer(), end);
    }

    /** gv -- select what was selected last time. */
    static void reselect(Editor editor, VimState state)
    {
        select(editor, state, state.getLastSelection());
    }

    /**
     * gv in visual mode: swap this selection with the previous one, so a
     * second gv comes back.
     */
    static void swapWithLast(Editor editor, VimState state)
    {
        final VimState.Selection last = state.getLastSelection();
        if (last == null)
            return;
        remember(editor, state);
        select(editor, state, last);
    }

    /**
     * The selection as an operator's range, leaving visual mode: what every
     * command that acts on the selection starts with.
     */
    static VimRange take(Editor editor, VimState state)
    {
        final VimRange range = toRange(editor, state);
        state.setVisualRepeat(repeatKeys(editor, state));
        remember(editor, state);
        editor.unmark();
        state.setMode(editor, VimMode.NORMAL);
        return range;
    }

    private static void select(Editor editor, VimState state,
                               VimState.Selection last)
    {
        if (last == null)
            return;
        final Line anchorLine = lineAt(editor, last.anchorLine);
        final Line headLine = lineAt(editor, last.headLine);
        if (anchorLine == null || headLine == null)
            return;
        editor.setDot(anchorLine, Math.min(last.anchorOffset, anchorLine.length()));
        editor.setMarkAtDot();
        editor.setDot(headLine, Math.min(last.headOffset, headLine.length()));
        editor.moveCaretToDotCol();
        state.setMode(editor, last.mode);
        state.selectionCrossedLines(editor, anchorLine, headLine);
    }

    /**
     * o -- put the caret on the other end of the selection.
     *
     * After $ the caret stands for the end of its line, newline and all, so
     * that end goes to the anchor as the line's end itself; and an anchor
     * there makes the caret stand for it again when o brings it back.
     */
    static void swapEnds(Editor editor, VimState state, boolean sideways)
    {
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return;
        if (sideways && state.getMode() == VimMode.VISUAL_BLOCK) {
            swapColumns(editor, state, anchor, head);
            return;
        }
        final Position wasAnchor = new Position(anchor);
        final boolean anchorAtEol = wasAnchor.getLineLength() > 0
            && wasAnchor.getOffset() >= wasAnchor.getLineLength();
        editor.setDot(head.getLine(), state.isStickyEol()
                                          ? head.getLineLength()
                                          : head.getOffset());
        editor.setMarkAtDot();
        editor.setDot(wasAnchor.getLine(), wasAnchor.getOffset());
        editor.moveCaretToDotCol();
        if (anchorAtEol)
            state.setDesiredColumn(VimState.STICKY_EOL);
        else
            state.clearDesiredColumn();
        state.clampCaret(editor);
    }

    /**
     * O in a block: the caret to the other corner on its own line, the
     * anchor to the other on its line, so the block stays the same.
     */
    private static void swapColumns(Editor editor, VimState state,
                                    Position anchor, Position head)
    {
        final Buffer buffer = editor.getBuffer();
        final int anchorCol = buffer.getCol(anchor);
        final int headCol = buffer.getCol(head);
        final Line anchorLine = anchor.getLine();
        final Line headLine = head.getLine();
        editor.setDot(Block.positionAt(buffer, anchorLine, headCol));
        editor.setMarkAtDot();
        editor.setDot(Block.positionAt(buffer, headLine, anchorCol));
        editor.moveCaretToDotCol();
        state.clearDesiredColumn();
        state.clampCaret(editor);
        editor.setUpdateFlag(Constants.REPAINT);
    }

    /**
     * Where vim's cursor stands when an operator on the selection starts, as
     * {@link VimState#setOperatorStart} wants it: the start of a characterwise
     * selection; for a linewise one its top line, at the caret's column if
     * the caret is the top end, else at 0 -- nvim gives back (1,4) after Vkd
     * from (1,4), but (0,0) after Vjd from (0,4) and after Vd. Null for a
     * block, whose operators leave the caret at its corner themselves.
     */
    static Position operatorStart(Editor editor, VimState state)
    {
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return null;
        switch (state.getMode()) {
            case VISUAL_BLOCK:
                return null;
            case VISUAL_LINE:
                if (head.getLine() != anchor.getLine() && head.isBefore(anchor))
                    return new Position(head);
                return new Position(anchor.isBefore(head) ? anchor.getLine()
                                                          : head.getLine(), 0);
            default:
                return new Position(anchor.isBefore(head) ? anchor : head);
        }
    }

    /**
     * The selection as an operator's range.
     *
     * Characterwise takes in the character the caret is on, which is the one
     * place vim's selection and j's region disagree.
     */
    static VimRange toRange(Editor editor, VimState state)
    {
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return null;
        if (state.getMode() == VimMode.VISUAL_BLOCK)
            return VimRange.of(block(editor, state), editor.getBuffer());
        final Position start = anchor.isBefore(head) ? anchor : head;
        final Position end = anchor.isBefore(head) ? head : anchor;
        // After $ the caret stands for the line end itself, so v$ takes in
        // the newline: v$d joins the next line on. So does an end on an
        // empty line, which has nothing else to take.
        if (state.getMode() == VimMode.VISUAL && end.getLine().next() != null
            && (end.getOffset() >= end.getLineLength()
                || !head.isBefore(anchor) && state.isStickyEol()))
            return new VimRange(new Position(start),
                                new Position(end.getLine().next(), 0));
        return RangeNormalizer.normalize(
            new Position(start), new Position(end),
            state.getMode() == VimMode.VISUAL_LINE ? MotionKind.LINEWISE
                                                   : MotionKind.CHARWISE_INCLUSIVE,
            true);
    }

    /**
     * Keys that select as much again from the caret, which . runs before
     * a change made from a selection, as vim repeats one over the same
     * shape: lines for V, lines and columns for CTRL-V -- to the ends of the
     * lines after $ -- and characters for v on one line. Null for v over
     * several lines, which . still does at the caret alone.
     */
    private static String repeatKeys(Editor editor, VimState state)
    {
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return null;
        final int lines = Math.abs(head.lineNumber() - anchor.lineNumber());
        final String down = lines > 0 ? lines + "j" : "";
        switch (state.getMode()) {
            case VISUAL_LINE:
                return "V" + down;
            case VISUAL_BLOCK: {
                final int cols = Math.abs(editor.getBuffer().getCol(head)
                                          - editor.getBuffer().getCol(anchor));
                return "<C-v>" + down + (state.isStickyEol() ? "$"
                                         : cols > 0 ? cols + "l" : "");
            }
            default: {
                if (lines > 0)
                    return null;
                final int chars = Math.abs(head.getOffset() - anchor.getOffset());
                return "v" + (chars > 0 ? chars + "l" : "");
            }
        }
    }

    /**
     * The block selected: anchor to caret, each taking in the character it
     * is on, and after $ ragged to the end of each line.
     */
    static Block block(Editor editor, VimState state)
    {
        return Block.between(editor.getBuffer(), editor.getMark(),
                             editor.getDot(), state.isStickyEol());
    }

    private static Line lineAt(Editor editor, int lineNumber)
    {
        Line line = editor.getBuffer().getFirstLine();
        for (int i = 0; i < lineNumber && line != null; i++)
            line = line.next();
        return line;
    }
}
