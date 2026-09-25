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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Lines;
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
        if (command.bang && !name.isEmpty() && !matches(name, "sor", "sort")
            && !matches(name, "g", "global") && !matches(name, "j", "join"))
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
        if (matches(name, "j", "join")) {
            join(editor, state, command);
            return true;
        }
        if (matches(name, "m", "move")) {
            moveOrCopy(editor, state, command, true);
            return true;
        }
        if (matches(name, "co", "copy") || name.equals("t")) {
            moveOrCopy(editor, state, command, false);
            return true;
        }
        if (matches(name, "norm", "normal")) {
            normal(editor, state, command);
            return true;
        }
        if (matches(name, "g", "global") || matches(name, "v", "vglobal")) {
            global(editor, state, command,
                   command.bang || matches(name, "v", "vglobal"));
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- join

    /** {@code :j} -- one join per line the range covers past the first. */
    private static void join(Editor editor, VimState state,
                             VimEx.Command command) throws VimEx.BadCommand
    {
        final Line first = VimEx.lineAt(editor, command.range.first);
        if (first == null)
            throw new VimEx.BadCommand("E16: Invalid range");
        // With one line named, :j still joins it to the next, so there is
        // always at least one join to do.
        final int joins = Math.max(1, command.range.last - command.range.first);
        editor.setDot(first, 0);
        editor.moveCaretToDotCol();
        VimActions.joinAt(editor, state, joins, command.bang);
        // J leaves the caret at the join; :join leaves it at the start of
        // the line it made.
        final Position dot = editor.getDot();
        if (dot != null) {
            editor.setDot(dot.getLine(),
                          VimMotions.firstNonBlank(dot.getLine()));
            editor.moveCaretToDotCol();
        }
        state.clampCaret(editor);
    }

    // --------------------------------------------------------- move, copy

    /**
     * {@code :m} and {@code :t} -- put the range after another line.
     *
     * The destination is parsed here rather than by the range parser because
     * it is the one address that may legitimately be zero: {@code :2,3m0}
     * means "above the first line".
     */
    private static void moveOrCopy(Editor editor, VimState state,
                                   VimEx.Command command, boolean move)
        throws VimEx.BadCommand
    {
        final String where = command.args.trim();
        if (where.isEmpty())
            throw new VimEx.BadCommand("E14: Invalid address");
        // Parsed here rather than by the range parser because it is the one
        // address that may legitimately be zero: :2,3m0 means "above the
        // first line".
        final int target = where.equals("0") ? 0
            : VimEx.parse(editor, state, where).range.last;
        if (VimEx.lineAt(editor, command.range.first) == null
            || VimEx.lineAt(editor, command.range.last) == null)
            throw new VimEx.BadCommand("E16: Invalid range");

        if (move)
            Lines.moveLines(editor, command.range.first, command.range.last,
                            target);
        else
            Lines.copyLines(editor, command.range.first, command.range.last,
                            target);
        final Position dot = editor.getDot();
        if (dot != null) {
            editor.setDot(dot.getLine(), VimMotions.firstNonBlank(dot.getLine()));
            editor.moveCaretToDotCol();
        }
        state.clampCaret(editor);
    }

    // -------------------------------------------------------------- normal"

    /**
     * {@code :normal} -- run the rest of the line as normal-mode keys.
     *
     * Once per line when a range is given, which is what makes
     * {@code :%norm A;} useful. The argument is taken exactly as typed,
     * spaces included, since a space is a motion.
     */
    private static void normal(Editor editor, VimState state,
                               VimEx.Command command) throws VimEx.BadCommand
    {
        final String keys = command.args;
        if (keys.isEmpty())
            return;
        if (!command.range.given) {
            state.getHandler().runKeys(editor, keys);
            return;
        }
        for (Line line : linesIn(editor, command.range)) {
            editor.setDot(line, 0);
            editor.moveCaretToDotCol();
            state.getHandler().runKeys(editor, keys);
        }
    }

    // -------------------------------------------------------------- global

    /**
     * {@code :g} and {@code :v} -- run a command on every matching line.
     *
     * Two passes, as vim does: the lines are picked out first and the command
     * runs over them afterwards. One pass would let the command's own output
     * be matched -- a {@code :g/e/s/x/\n/} that splits lines would go back
     * over the halves it had just made.
     */
    private static void global(Editor editor, VimState state,
                               VimEx.Command command, boolean invert)
        throws VimEx.BadCommand
    {
        final String args = command.args;
        if (args.isEmpty())
            throw new VimEx.BadCommand("E35: No previous regular expression");
        final char separator = args.charAt(0);
        final int close = indexOfUnescaped(args, separator, 1);
        final String pattern = close < 0 ? args.substring(1)
                                         : args.substring(1, close);
        final String rest = close < 0 ? "" : args.substring(close + 1).trim();
        // A bare :g/pat/ prints the matching lines, which has nowhere to go
        // here; vim's own default is :p, so do the nearest useful thing and
        // treat it as "no command".
        final String line = rest.isEmpty() ? "" : rest;

        final Pattern regex;
        try {
            regex = Pattern.compile(VimSearch.toJavaRegex(pattern));
        }
        catch (PatternSyntaxException e) {
            throw new VimEx.BadCommand("E486: Pattern not found: " + pattern);
        }
        state.setLastSearch(new VimSearch.Query(pattern, true, false));

        final VimEx.Range range = command.range.given
            ? command.range
            : new VimEx.Range(1, Math.max(1, editor.getBuffer().getLineCount()),
                              true);
        // The matching lines are picked out first and the command runs over
        // them afterwards, as vim does. One pass would let the command's own
        // output be matched -- a :g/e/s/x/\n/ that splits lines would go back
        // over the halves it had just made.
        //
        // They are remembered by number rather than by Line, because j's
        // region delete merges the first and last line of what it removes:
        // the object that survives is the one above, so a Line captured here
        // is not the line that was on that row afterwards.
        //
        // And they are run from the bottom up, so that the numbers of the
        // ones still to come cannot be shifted by an edit above them. Vim
        // works top down; for the commands that make sense here -- d, s and
        // another g -- each line is independent and the result is the same.
        final List<Integer> targets = new ArrayList<Integer>();
        editor.getBuffer().renumber();
        for (int n = range.first; n <= range.last; n++) {
            final Line l = VimEx.lineAt(editor, n);
            if (l == null)
                break;
            final String text = l.getText() == null ? "" : l.getText();
            if (regex.matcher(text).find() != invert)
                targets.add(Integer.valueOf(n));
        }
        if (line.isEmpty())
            return;

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            for (int i = targets.size() - 1; i >= 0; i--) {
                final Line l = VimEx.lineAt(editor, targets.get(i).intValue());
                if (l == null)
                    continue;
                editor.setDot(l, 0);
                editor.moveCaretToDotCol();
                // j renumbers lazily, and the inner command with no range of
                // its own asks for the line the caret is on by number.
                editor.getBuffer().renumber();
                final VimEx.Command inner = VimEx.parse(editor, state, line);
                if (!VimExCommands.run(editor, state, inner))
                    throw new VimEx.BadCommand(
                        "E492: Not an editor command: " + inner.name);
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        state.clampCaret(editor);
    }

    /** The first unescaped occurrence of a character, or -1. */
    private static int indexOfUnescaped(String s, char c, int from)
    {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                ++i;
                continue;
            }
            if (s.charAt(i) == c)
                return i;
        }
        return -1;
    }

    /** The lines a range covers, as they stand now. */
    private static List<Line> linesIn(Editor editor, VimEx.Range range)
        throws VimEx.BadCommand
    {
        final List<Line> lines = new ArrayList<Line>();
        for (int n = range.first; n <= range.last; n++) {
            final Line line = VimEx.lineAt(editor, n);
            if (line == null)
                break;
            lines.add(line);
        }
        if (lines.isEmpty())
            throw new VimEx.BadCommand("E16: Invalid range");
        return lines;
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
