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

    /** o -- put the caret on the other end of the selection. */
    static void swapEnds(Editor editor)
    {
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return;
        final Position wasAnchor = new Position(anchor);
        editor.setDot(head.getLine(), head.getOffset());
        editor.setMarkAtDot();
        editor.setDot(wasAnchor.getLine(), wasAnchor.getOffset());
        editor.moveCaretToDotCol();
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
        final Position start = anchor.isBefore(head) ? anchor : head;
        final Position end = anchor.isBefore(head) ? head : anchor;
        // After $ the caret stands for the line end itself, so v$ takes in
        // the newline: v$d joins the next line on.
        if (state.getMode() == VimMode.VISUAL && !head.isBefore(anchor)
            && state.isStickyEol() && head.getLine().next() != null)
            return new VimRange(new Position(start),
                                new Position(head.getLine().next(), 0));
        return RangeNormalizer.normalize(
            new Position(start), new Position(end),
            state.getMode() == VimMode.VISUAL_LINE ? MotionKind.LINEWISE
                                                   : MotionKind.CHARWISE_INCLUSIVE,
            true);
    }

    private static Line lineAt(Editor editor, int lineNumber)
    {
        Line line = editor.getBuffer().getFirstLine();
        for (int i = 0; i < lineNumber && line != null; i++)
            line = line.next();
        return line;
    }
}
