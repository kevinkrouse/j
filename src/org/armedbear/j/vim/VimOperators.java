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

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.SimpleEdit;

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
        register("yank", VimOperators::yank);
        register("changeCase", VimOperators::changeCase);
        register("indent", VimOperators::indent);
    }

    /**
     * gu, gU and g~.
     *
     * The text keeps its place and its length, so the caret goes back to the
     * start of the range rather than following the replacement.
     */
    private static void changeCase(MotionContext ctx, VimRange range)
    {
        final Editor editor = ctx.editor;
        final String text = textOf(editor, range);
        if (text.isEmpty())
            return;
        final String changed = applyCase(text, ctx.arg("to", "toggle"));
        if (changed.equals(text)) {
            editor.setDot(new Position(range.start));
            editor.moveCaretToDotCol();
            return;
        }
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            final int line = range.start.lineNumber();
            final int offset = range.start.getOffset();
            deleteRange(editor, range);
            editor.insertString(changed);
            final Line target = lineNumbered(editor, line);
            if (target != null) {
                editor.setDot(target, Math.min(offset, target.length()));
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    private static String applyCase(String text, String to)
    {
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (to) {
                case "lower": sb.append(Character.toLowerCase(c)); break;
                case "upper": sb.append(Character.toUpperCase(c)); break;
                default:
                    sb.append(Character.isUpperCase(c)
                              ? Character.toLowerCase(c)
                              : Character.toUpperCase(c));
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * &gt; and &lt;.
     *
     * A shift by 'shiftwidth', not a re-indent: vim moves the line, it does
     * not work out where the line ought to go. j's own indentRegion does the
     * latter, which is a different command.
     */
    private static void indent(MotionContext ctx, VimRange range)
    {
        final Editor editor = ctx.editor;
        final Buffer buffer = editor.getBuffer();
        final int width = buffer.getIndentSize();
        final int sign = ctx.arg("right") ? 1 : -1;

        final CompoundEdit edit = buffer.beginCompoundEdit();
        try {
            Line line = range.start.getLine();
            final Line stop = range.end.getOffset() == 0
                ? range.end.getLine()
                : range.end.getLine().next();
            while (line != null && line != stop) {
                if (line.length() > 0 || sign > 0) {
                    editor.setDot(line, 0);
                    editor.addUndo(SimpleEdit.LINE_EDIT);
                    final int was = buffer.getIndentation(line);
                    buffer.setIndentation(line, Math.max(0, was + sign * width));
                    editor.updateDotLine();
                }
                line = line.next();
            }
        }
        finally {
            buffer.endCompoundEdit(edit);
        }
        // Vim leaves the caret on the first non-blank of the first line.
        final Line first = range.start.getLine();
        editor.setDot(first, VimMotions.firstNonBlank(first));
        editor.moveCaretToDotCol();
        ctx.state.clampCaret(editor);
    }

    private static Line lineNumbered(Editor editor, int number)
    {
        Line line = editor.getBuffer().getFirstLine();
        for (int i = 0; i < number && line != null; i++)
            line = line.next();
        return line;
    }

    /** y: take a copy and leave the text alone. */
    private static void yank(MotionContext ctx, VimRange range)
    {
        final String text = textOf(ctx.editor, range);
        VimRegisters.getInstance().yanked(ctx.state.takePendingRegister(), text,
                                          range.linewise
                                              ? VimRegisters.Type.LINEWISE
                                              : VimRegisters.Type.CHARWISE);
        // The caret only ever moves back to the start of what was yanked,
        // never forward: yw leaves it where it was, yb pulls it back. A
        // linewise yank keeps its column, so yy and y} do not move it at all.
        final Position dot = ctx.editor.getDot();
        if (dot != null && range.start.isBefore(dot)) {
            if (!range.linewise) {
                ctx.editor.setDot(new Position(range.start));
                ctx.editor.moveCaretToDotCol();
            } else if (range.start.lineNumber() < dot.lineNumber()) {
                final Line line = range.start.getLine();
                ctx.editor.setDot(line, Math.min(dot.getOffset(), line.length()));
                ctx.editor.moveCaretToDotCol();
            }
        }
        ctx.state.clampCaret(ctx.editor);
    }

    /**
     * The text a range covers.
     *
     * A linewise range ends at the start of the line after the last one, so
     * the text it covers ends with a newline -- which is what tells a later
     * put to make new lines rather than splice into one.
     */
    static String textOf(Editor editor, VimRange range)
    {
        if (range.isEmpty())
            return "";
        return new Region(editor.getBuffer(), range.start, range.end).toString();
    }

    /** d, and the operators that are d with a motion built in. */
    private static void delete(MotionContext ctx, VimRange range)
    {
        VimRegisters.getInstance().deleted(ctx.state.takePendingRegister(),
                                           textOf(ctx.editor, range),
                                           range.linewise
                                               ? VimRegisters.Type.LINEWISE
                                               : VimRegisters.Type.CHARWISE);
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
        VimRegisters.getInstance().deleted(ctx.state.takePendingRegister(),
                                           textOf(editor, range),
                                           range.linewise
                                               ? VimRegisters.Type.LINEWISE
                                               : VimRegisters.Type.CHARWISE);
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
