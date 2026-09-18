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
    }

    static void leave(Editor editor, VimState state)
    {
        remember(editor, state);
        editor.setMark(null);
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
        final VimState.Selection last = state.getLastSelection();
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
