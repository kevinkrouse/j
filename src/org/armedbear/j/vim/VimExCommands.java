/*
 * VimExCommands.java
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

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * What the {@code :} commands do.
 *
 * Names are matched the way vim documents them, as a required prefix and an
 * optional tail: {@code d}, {@code de}, {@code del} and {@code delete} are all
 * the same command, and {@code dp} is not. Anything not recognised here falls
 * through to j's own {@code CommandTable}, so {@code :findTagAtDot} works
 * without being listed.
 */
public final class VimExCommands
{
    private VimExCommands()
    {
    }

    /**
     * Runs a parsed ex line.
     *
     * @return false when the name is not one of ours and the caller should try
     *         j's own commands
     */
    static boolean run(Editor editor, VimState state, VimEx.Command command)
        throws VimEx.BadCommand
    {
        final String name = command.name;
        // :sort! is the only one of these that means anything by it.
        if (command.bang && !name.isEmpty() && !matches(name, "sor", "sort"))
            throw new VimEx.BadCommand("E477: No ! allowed");
        if (name.isEmpty()) {
            // A bare range means "go to that line", which is what :42 is.
            if (command.range.given)
                goToLine(editor, state, command.range.last);
            return true;
        }
        if (matches(name, "d", "delete")) {
            delete(editor, state, command);
            return true;
        }
        if (matches(name, "y", "yank")) {
            yank(editor, state, command);
            return true;
        }
        if (matches(name, "sor", "sort")) {
            VimExSort.run(editor, state, command);
            return true;
        }
        if (matches(name, "s", "substitute")) {
            VimExSubstitute.run(editor, state, command);
            return true;
        }
        return false;
    }

    /**
     * True when a typed name is an allowed abbreviation.
     *
     * Vim writes this as "d[elete]": the required part, then a tail of which
     * the user may type any prefix.
     */
    private static boolean matches(String typed, String required, String full)
    {
        return typed.length() >= required.length()
            && full.startsWith(typed)
            && typed.startsWith(required);
    }

    // --------------------------------------------------------------- go to

    /** {@code :42} -- the first non-blank of that line. */
    private static void goToLine(Editor editor, VimState state, int number)
        throws VimEx.BadCommand
    {
        final Line line = number < 1 ? null : VimEx.lineAt(editor, number);
        if (line == null)
            throw new VimEx.BadCommand("E16: Invalid range");
        editor.setDot(line, VimMotions.firstNonBlank(line));
        editor.moveCaretToDotCol();
        state.clampCaret(editor);
    }

    // ------------------------------------------------------- delete, yank

    /**
     * The range as a linewise {@link VimRange}.
     *
     * A linewise range ends at offset 0 of the line after the last one, so the
     * text it covers takes the newlines with it. At the end of the buffer
     * there is no such line and it ends at the last line's length instead,
     * which is the shape {@link VimOperators#deleteRange} already knows.
     */
    static VimRange linesOf(Editor editor, VimEx.Range range)
        throws VimEx.BadCommand
    {
        if (range.first < 1 || range.last < 1)
            throw new VimEx.BadCommand("E16: Invalid range");
        final Line first = VimEx.lineAt(editor, range.first);
        final Line last = VimEx.lineAt(editor, range.last);
        if (first == null || last == null)
            throw new VimEx.BadCommand("E16: Invalid range");
        final Line after = last.next();
        final Position end = after != null ? new Position(after, 0)
                                           : new Position(last, last.length());
        return new VimRange(new Position(first, 0), end, true);
    }

    /** {@code :d}, with the register it goes to if one was named. */
    private static void delete(Editor editor, VimState state,
                               VimEx.Command command) throws VimEx.BadCommand
    {
        final VimRange range = linesOf(editor, countedRange(editor, command));
        registerFrom(state, command);
        VimRegisters.getInstance().deleted(state.takePendingRegister(),
                                           VimOperators.textOf(editor, range),
                                           VimRegisters.Type.LINEWISE);
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            VimOperators.deleteRange(editor, range);
            // Vim leaves the caret on the first non-blank of the line that
            // moved up into the gap, as dd does.
            final Position dot = editor.getDot();
            if (dot != null) {
                editor.setDot(dot.getLine(),
                              VimMotions.firstNonBlank(dot.getLine()));
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        state.clampCaret(editor);
    }

    /** {@code :y} -- the same range, copied rather than taken. */
    private static void yank(Editor editor, VimState state,
                             VimEx.Command command) throws VimEx.BadCommand
    {
        final VimRange range = linesOf(editor, countedRange(editor, command));
        registerFrom(state, command);
        VimRegisters.getInstance().yanked(state.takePendingRegister(),
                                          VimOperators.textOf(editor, range),
                                          VimRegisters.Type.LINEWISE);
    }

    /**
     * Applies the trailing {@code [register] [count]} that {@code :d} and
     * {@code :y} accept.
     *
     * A count there does not mean "repeat": it replaces the range with that
     * many lines starting at its <em>last</em> one, so {@code :1,3d 2} deletes
     * lines 3 and 4.
     */
    private static VimEx.Range countedRange(Editor editor,
                                            VimEx.Command command)
    {
        final String count = trailingCount(command.args);
        if (count.isEmpty())
            return command.range;
        final int n = Integer.parseInt(count);
        // A count past the end of the buffer clamps, where an address past
        // the end is an error. Vim really is asymmetric here: :1,3d 100 takes
        // what there is, and :100d takes nothing and complains.
        final int lines = Math.max(1, editor.getBuffer().getLineCount());
        return new VimEx.Range(command.range.last,
                               Math.min(command.range.last + n - 1, lines),
                               true);
    }

    /**
     * The count in the {@code [register] [count]} tail.
     *
     * The two need no space between them: {@code :d a2} and {@code :d a 2}
     * are the same, so the digits are taken from the end of the last word
     * rather than from a word of their own.
     */
    private static String trailingCount(String args)
    {
        final String s = args.trim();
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1)))
            --i;
        final String digits = s.substring(i);
        // What comes before must be a register name or nothing; digits in the
        // middle of a word are not a count.
        final String head = s.substring(0, i);
        if (head.isEmpty() || (head.length() == 1
                               && VimRegisters.isValidName(head.charAt(0))))
            return digits;
        return "";
    }

    private static void registerFrom(VimState state, VimEx.Command command)
    {
        final String s = command.args.trim();
        if (!s.isEmpty() && !Character.isDigit(s.charAt(0))
            && VimRegisters.isValidName(s.charAt(0)))
            state.setPendingRegister(s.charAt(0));
    }
}
