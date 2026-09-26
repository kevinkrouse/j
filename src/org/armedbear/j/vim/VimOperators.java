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
import org.armedbear.j.Lines;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.RegionCommands;
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
        if (range.isEmpty())
            return;
        final RegionCommands.Case which = caseOf(ctx.arg("to", "toggle"));
        // j's own upperCaseRegion and lowerCaseRegion do this, between mark
        // and dot, so set the region and let them. The caret comes back to
        // the start of the range either way, which is where vim leaves it.
        final int line = range.start.lineNumber();
        final int offset = range.start.getOffset();
        editor.setMark(new Position(range.end));
        editor.setDot(new Position(range.start));
        editor.moveCaretToDotCol();
        RegionCommands.changeCaseRegion(editor, which);
        final Line target = lineNumbered(editor, line);
        if (target != null) {
            editor.setDot(target, Math.min(offset, target.length()));
            editor.moveCaretToDotCol();
        }
        ctx.state.clampCaret(editor);
    }

    private static RegionCommands.Case caseOf(String to)
    {
        if (to.equals("upper"))
            return RegionCommands.Case.UPPER;
        if (to.equals("lower"))
            return RegionCommands.Case.LOWER;
        return RegionCommands.Case.TOGGLE;
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

        // j's own shiftLinesRight and shiftLinesLeft do this.
        final Line from = range.start.getLine();
        final Line to = range.end.getOffset() == 0
            && range.end.getLine() != from
            ? range.end.getLine().previous()
            : range.end.getLine();
        Lines.shift(editor, from, to, sign * width);
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
        final Editor editor = ctx.editor;
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            recordCaret(editor);
            deleteRange(editor, range);
            if (range.linewise) {
                // Vim leaves the caret on the first non-blank of the line that
                // moved up into the gap.
                final Position dot = editor.getDot();
                if (dot != null) {
                    editor.setDot(dot.getLine(),
                                  VimMotions.firstNonBlank(dot.getLine()));
                    editor.moveCaretToDotCol();
                }
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    /** The spaces and tabs a line starts with. */
    static String leadingBlanks(Line line)
    {
        final String text = line.getText() == null ? "" : line.getText();
        return text.substring(0, Lines.leadingBlanks(line));
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
        // No recordCaret here. Vim is not uniform about where undo leaves the
        // caret: J and p give back where it was, but c leaves it at the start
        // of what changed -- which is what falling through to j's own records
        // already does.
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
        // behind for the new text. RangeNormalizer.linewise() marks the end
        // of a non-final range with the line *after* the last affected one,
        // at offset 0 -- back up to its previous line to find the last
        // affected line. At end of buffer there is no such line, so the
        // range's own last line is given directly instead, at its length.
        final Line last = range.end.getOffset() == 0
            ? range.end.getLine().previous()
            : range.end.getLine();

        // The first line's indent stays, as it does in vim with 'autoindent'
        // on, which is nvim's default: cc on "  bb" then x gives "  x". If
        // nothing is typed after it, Escape takes it away again.
        final String indent = leadingBlanks(first);

        editor.setMark(new Position(first, 0));
        editor.setDot(last, last.length());
        editor.moveCaretToDotCol();
        editor.deleteRegion();
        editor.setMark(null);
        final Position dot = editor.getDot();
        if (dot != null) {
            editor.setDot(dot.getLine(), 0);
            editor.moveCaretToDotCol();
            if (!indent.isEmpty())
                editor.insertString(indent);
            ctx.state.noteAutoIndent(dot.getLine());
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * Notes where the caret is, so that undo gives it back.
     *
     * A command that edits usually moves the caret to the place it is about
     * to change, and j's undo records only restore what the edits themselves
     * captured. Without this, undo leaves the caret wherever the command put
     * it rather than where the user had it. Must be inside the command's
     * compound edit, or it becomes an undo step of its own.
     */
    static void recordCaret(Editor editor)
    {
        editor.addUndo(SimpleEdit.MOVE);
    }

    /**
     * Deletes a span through j's own region delete, which already handles the
     * undo record, the modified flag and every marker that pointed into it.
     */
    static void deleteRange(Editor editor, VimRange range)
    {
        if (range.isEmpty())
            return;
        Position start = new Position(range.start);
        // A linewise range normally ends at offset 0 of the line after it, so
        // deleting it takes the newlines with the lines. The last line of the
        // buffer has no line after, so the range ends part way along it and
        // one newline too few is taken -- leaving an empty line behind. Take
        // the newline before the range instead.
        // The end offset says which: 0 means the line after the range, so the
        // range stops short of the buffer end after all.
        if (range.linewise && range.end.getLine().next() == null
            && range.end.getOffset() > 0 && start.getOffset() == 0) {
            final Line before = start.getLine().previous();
            if (before != null)
                start = new Position(before, before.length());
        }
        // The caret goes at the start and the mark at the end, not the other
        // way round: j's undo records where the caret was when the edit was
        // made, so this is what puts it back at the start of the restored
        // text, which is where vim leaves it.
        editor.setMark(new Position(range.end));
        editor.setDot(start);
        editor.deleteRegion();
        editor.setMark(null);
        editor.moveCaretToDotCol();
    }
}
