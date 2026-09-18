/*
 * VimOperators.java
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
import java.util.Map;

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * The operators, by the names the key map table uses.
 *
 * An operator is handed a settled range, so it never has to know which motion
 * produced it or whether that motion was inclusive.
 */
public final class VimOperators
{
    public interface Operator
    {
        void apply(MotionContext ctx, VimRange range);
    }

    private static final Map<String, Operator> OPERATORS =
        new HashMap<String, Operator>();

    private VimOperators()
    {
    }

    public static Operator get(String name)
    {
        return OPERATORS.get(name);
    }

    public static void register(String name, Operator operator)
    {
        OPERATORS.put(name, operator);
    }

    static {
        register("delete", VimOperators::delete);
        register("change", VimOperators::change);
    }

    /** d, and the operators that are d with a motion built in. */
    private static void delete(MotionContext ctx, VimRange range)
    {
        deleteRange(ctx.editor, range);
        if (range.linewise) {
            // Vim leaves the caret on the first non-blank of the line that
            // moved up into the gap.
            final Position dot = ctx.editor.getDot();
            if (dot != null) {
                ctx.editor.setDot(dot.getLine(),
                                  VimMotions.firstNonBlank(dot.getLine()));
                ctx.editor.moveCaretToDotCol();
            }
        }
        ctx.state.clampCaret(ctx.editor);
    }

    /**
     * c: delete, then insert in the gap.
     *
     * The undo step is opened before the delete so that one u puts back both
     * the text that went and the text that came.
     */
    private static void change(MotionContext ctx, VimRange range)
    {
        final Editor editor = ctx.editor;
        ctx.state.beginInsert(editor, VimMode.INSERT);
        if (range.linewise) {
            // cc keeps the line, empties it, and keeps its indent.
            changeLinewise(ctx, range);
            return;
        }
        deleteRange(editor, range);
    }

    private static void changeLinewise(MotionContext ctx, VimRange range)
    {
        final Editor editor = ctx.editor;
        final Line first = range.start.getLine();
        // Everything from the first line to the last, but leave one line
        // behind for the new text.
        Line last = first;
        while (last.next() != null
               && last.next() != range.end.getLine()
               && last != range.end.getLine())
            last = last.next();

        editor.setMark(new Position(first, 0));
        editor.setDot(last, last.length());
        editor.deleteRegion();
        editor.setMark(null);
        final Position dot = editor.getDot();
        if (dot != null) {
            editor.setDot(dot.getLine(), 0);
            editor.moveCaretToDotCol();
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * Deletes a span through j's own region delete, which already handles the
     * undo record, the modified flag and every marker that pointed into it.
     */
    static void deleteRange(Editor editor, VimRange range)
    {
        if (range.isEmpty())
            return;
        editor.setMark(new Position(range.start));
        editor.setDot(new Position(range.end));
        editor.deleteRegion();
        editor.setMark(null);
        editor.moveCaretToDotCol();
    }
}
