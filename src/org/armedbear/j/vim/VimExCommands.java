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
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.BufferCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.FileCommands;
import org.armedbear.j.Finders;
import org.armedbear.j.Line;
import org.armedbear.j.Lines;
import org.armedbear.j.Position;
import org.armedbear.j.WindowCommands;

/**
 * What the {@code :} commands do.
 *
 * Names are matched the way vim documents them, as a required prefix and an
 * optional tail: {@code d}, {@code de}, {@code del} and {@code delete} are all
 * the same command, and {@code dp} is not. Anything not recognised here falls
 * through to j's own {@code CommandTable}, so {@code :findTagAtDot} works
 * without being listed.
 */
public final class VimExCommands {
    private VimExCommands() {}

    /**
     * Runs a parsed ex line.
     *
     * @return false when the name is not one of ours and the caller should try
     *         j's own commands
     */
    static boolean run(Editor editor, VimState state, VimEx.Command command) throws VimEx.BadCommand {
        final String name = command.name;
        // The commands here that take a bang. :normal! is accepted but runs
        // the keys through your mappings as :normal does: j merges mappings
        // into the same table as the built-ins, so there is no unmapped
        // table left to fall back on. Documented.
        if (command.bang
                && !name.isEmpty()
                && !matches(name, "sor", "sort")
                && !matches(name, "g", "global")
                && !matches(name, "j", "join")
                && !matches(name, "delm", "delmarks")
                && !matches(name, "norm", "normal")
                && !matches(name, "w", "write")
                && !name.equals("wq")
                && !matches(name, "q", "quit")
                && !matches(name, "clo", "close")
                && !matches(name, "on", "only"))
            throw new VimEx.BadCommand("E477: No ! allowed");
        // j's read-only is vim's nomodifiable: nothing may change the text.
        // :g and :normal get there through the commands they run.
        if ((matches(name, "d", "delete")
                || matches(name, "sor", "sort")
                || matches(name, "s", "substitute")
                || matches(name, "j", "join")
                || matches(name, "m", "move")
                || matches(name, "co", "copy")
                || name.equals("t")) && !editor.checkReadOnly())
            throw new VimEx.BadCommand("E21: Cannot make changes");
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
        if (matches(name, "w", "write")) {
            write(editor, command);
            return true;
        }
        // j's one panel is vim's quickfix, preview and help windows.
        if (matches(name, "ccl", "cclose") || matches(name, "pc", "pclose") || matches(name, "helpc", "helpclose")) {
            BufferCommands.closePanel(editor);
            return true;
        }
        if (name.equals("wq")) {
            if (write(editor, command))
                closeWindow(editor, true);
            return true;
        }
        if (matches(name, "sp", "split")) {
            split(editor, command, false);
            return true;
        }
        if (matches(name, "vs", "vsplit")) {
            split(editor, command, true);
            return true;
        }
        if (matches(name, "q", "quit")) {
            closeWindow(editor, true);
            return true;
        }
        if (matches(name, "clo", "close")) {
            closeWindow(editor, false);
            return true;
        }
        if (matches(name, "on", "only")) {
            WindowCommands.unsplitAllWindows(editor);
            return true;
        }
        if (matches(name, "se", "set")) {
            VimrcParser.set(VimKeyMap.getSharedOptions(), command.args);
            // ignorecase and smartcase change what the pattern matches.
            state.recompileLastSearch(editor);
            // Setting hlsearch shows the matches again after :noh.
            if (command.args.matches("(.*\\s)?(hls|hlsearch)(\\s.*)?"))
                editor.setSearchHighlightHidden(false);
            // An option such as hlsearch may change what every window shows.
            for (Editor ed : Editor.getEditorList())
                ed.repaintDisplay();
            return true;
        }
        // The lists of positions, as finders.
        if (matches(name, "ju", "jumps")) {
            Finders.jumps(editor);
            return true;
        }
        if (name.equals("changes")) {
            Finders.changeList(editor);
            return true;
        }
        if (matches(name, "noh", "nohlsearch")) {
            editor.clearSearchHighlight();
            return true;
        }
        if (matches(name, "delm", "delmarks")) {
            deleteMarks(state, command);
            return true;
        }
        if (matches(name, "g", "global") || matches(name, "v", "vglobal")) {
            global(editor, state, command, command.bang || matches(name, "v", "vglobal"));
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- join

    /** {@code :j} -- one join per line the range covers past the first. */
    private static void join(Editor editor, VimState state, VimEx.Command command) throws VimEx.BadCommand {
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
            editor.setDot(dot.getLine(), VimMotions.firstNonBlank(dot.getLine()));
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
    private static void moveOrCopy(Editor editor, VimState state, VimEx.Command command, boolean move)
            throws VimEx.BadCommand {
        final String where = command.args.trim();
        if (where.isEmpty())
            throw new VimEx.BadCommand("E16: Invalid range");
        final int target = where.equals("0") ? 0 : VimEx.parse(editor, state, where).range.last;
        if (VimEx.lineAt(editor, command.range.first) == null || VimEx.lineAt(editor, command.range.last) == null)
            throw new VimEx.BadCommand("E16: Invalid range");

        final boolean done = move
                ? Lines.moveLines(editor, command.range.first, command.range.last, target)
                : Lines.copyLines(editor, command.range.first, command.range.last, target);
        if (!done)
            throw new VimEx.BadCommand("E16: Invalid range");
        final Position dot = editor.getDot();
        if (dot != null) {
            // The caret is on the last line moved or copied.
            Line first = dot.getLine();
            for (int i = command.range.first; i < command.range.last && first.previous() != null; i++)
                first = first.previous();
            state.getMarks().noteLines(editor.getBuffer(), first, dot.getLine(), first);
            editor.setDot(dot.getLine(), VimMotions.firstNonBlank(dot.getLine()));
            editor.moveCaretToDotCol();
        }
        state.clampCaret(editor);
    }

    // -------------------------------------------------------------- normal

    /**
     * {@code :normal} -- run the rest of the line as normal-mode keys.
     *
     * Once per line when a range is given, which is what makes
     * {@code :%norm A;} useful. The argument is taken exactly as typed,
     * spaces included, since a space is a motion.
     */
    private static void normal(Editor editor, VimState state, VimEx.Command command) throws VimEx.BadCommand {
        final String keys = command.args;
        if (keys.isEmpty())
            throw new VimEx.BadCommand("E471: Argument required: normal");
        // One undo step for the whole command, as vim makes it, however many
        // changes the keys make on however many lines.
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            if (!command.range.given) {
                state.getHandler().runKeys(editor, keys);
                return;
            }
            // By number and from the bottom up, for the reason :g gives
            // below: a Line captured here does not survive the keys deleting
            // it, and the numbers of the lines still to come must not be
            // shifted by an edit above them. :%norm dd used to leave a line
            // behind.
            editor.getBuffer().renumber();
            for (int n = command.range.last; n >= command.range.first; n--) {
                final Line line = VimEx.lineAt(editor, n);
                if (line == null)
                    continue;
                editor.setDot(line, 0);
                editor.moveCaretToDotCol();
                editor.getBuffer().renumber();
                state.getHandler().runKeys(editor, keys);
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
    }

    // --------------------------------------------------------- write, quit

    /**
     * {@code :w} -- j's own save, and {@code :w FILE} its saveAs or saveCopy.
     *
     * Two small differences from vim are j's and kept: an unmodified buffer
     * is not rewritten ("Not modified"), and a buffer with no name opens the
     * Save As dialog where vim says E32.
     *
     * @return true when the buffer was written, which :wq needs to know --
     *         vim will not quit after a write that failed
     */
    private static boolean write(Editor editor, VimEx.Command command) throws VimEx.BadCommand {
        if (command.range.given)
            throw new VimEx.BadCommand("Writing part of a buffer is not supported");
        final String file = command.args.trim();
        if (file.startsWith("!"))
            throw new VimEx.BadCommand("Writing to a command is not supported");
        final org.armedbear.j.Buffer buffer = editor.getBuffer();
        if (file.isEmpty()) {
            FileCommands.save(editor);
            return !buffer.isModified();
        }
        final org.armedbear.j.File destination = FileCommands.fileNamed(editor, file);
        if (destination == null)
            throw new VimEx.BadCommand("E32: No file name");
        // The dialog asks before overwriting; without one, vim's rule does.
        if (destination.exists() && !command.bang && !destination.equals(buffer.getFile()))
            throw new VimEx.BadCommand("E13: File exists (add ! to override)");
        // A buffer with no name takes the one it is written to, as in vim;
        // one that has a name keeps it, and FILE gets a copy.
        final boolean written =
                buffer.isUntitled() ? FileCommands.saveAs(editor, file) : FileCommands.saveCopy(editor, file);
        if (!written)
            throw new VimEx.BadCommand("E212: Can't open file for writing");
        return true;
    }

    /**
     * :q, :close and CTRL-W q and c: close this window, and the caret goes to
     * the one that takes its space. The last one is :q leaving j, and E444
     * for :close.
     *
     * Vim's :q is the window's, not the buffer's -- in a split only that
     * split goes and vim carries on, which nvim confirms -- and the last
     * window closing is vim exiting. j's quit asks first when other buffers
     * have unsaved changes, where vim refuses with E37.
     */
    static void closeWindow(Editor editor, boolean quit) {
        final org.armedbear.j.Frame frame = editor.getFrame();
        if (frame == null)
            return;
        if (frame.getEditorCount() > 1)
            WindowCommands.killWindow(editor, "vim");
        else if (quit)
            FileCommands.quit(editor);
        else
            editor.status("E444: Cannot close last window");
    }

    /**
     * {@code :split} and {@code :vsplit}, with a file to open in the new
     * window or without. The caret stays in the top or left window, which
     * is how vim's split, the new window above and the caret in it, looks.
     */
    private static void split(Editor editor, VimEx.Command command, boolean vertical) {
        if (vertical)
            FileCommands.openFileInVsplit(editor, command.args);
        else
            FileCommands.openFileInSplit(editor, command.args);
    }

    // --------------------------------------------------------------- marks

    /**
     * {@code :delmarks} -- forget marks by name.
     *
     * The argument is a list of names, which may be separated by spaces or
     * not at all, and may include {@code a-c} ranges. {@code :delmarks!}
     * forgets every mark rather than taking names.
     */
    private static void deleteMarks(VimState state, VimEx.Command command) throws VimEx.BadCommand {
        if (command.bang) {
            state.getMarks().clear();
            return;
        }
        final String names = command.args.trim();
        if (names.isEmpty())
            throw new VimEx.BadCommand("E471: Argument required");
        for (int i = 0; i < names.length(); i++) {
            final char c = names.charAt(i);
            if (c == ' ')
                continue;
            // "b-d" is every name from b to d, so the dash is read here
            // rather than treated as a mark of its own.
            if (i + 2 < names.length() && names.charAt(i + 1) == '-') {
                final char to = names.charAt(i + 2);
                for (char m = c; m <= to; m++)
                    state.getMarks().remove(m);
                i += 2;
                continue;
            }
            state.getMarks().remove(c);
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
    private static void global(Editor editor, VimState state, VimEx.Command command, boolean invert)
            throws VimEx.BadCommand {
        final String args = command.args;
        if (args.isEmpty())
            throw new VimEx.BadCommand("E35: No previous regular expression");
        final char separator = args.charAt(0);
        final int close = indexOfUnescaped(args, separator, 1);
        final String pattern = close < 0 ? args.substring(1) : args.substring(1, close);
        // A bare :g/pat/ prints the matching lines in vim. There is nowhere
        // to print them here, so it does nothing but set the search pattern.
        final String line = close < 0 ? "" : args.substring(close + 1).trim();

        final Pattern regex;
        try {
            regex = VimRegex.compile(pattern, null);
        }
        catch (PatternSyntaxException e) {
            throw new VimEx.BadCommand(VimExSubstitute.badPattern(pattern, e));
        }
        state.setLastSearch(editor, new VimSearch.Query(pattern, true, false));

        final VimEx.Range range = command.range.given
                ? command.range
                : new VimEx.Range(1, Math.max(1, editor.getBuffer().getLineCount()), true);
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
        final List<Integer> targets = new ArrayList<>();
        editor.getBuffer().renumber();
        Line scan = VimEx.lineAt(editor, range.first);
        for (int n = range.first; n <= range.last && scan != null; n++, scan = scan.next()) {
            final String text = scan.getText() == null ? "" : scan.getText();
            if (regex.matcher(text).find() != invert)
                targets.add(Integer.valueOf(n));
        }
        if (targets.isEmpty()) {
            // Not an error in vim -- a message, and the command carries on.
            editor.status("Pattern not found: " + pattern);
            return;
        }
        if (line.isEmpty())
            return;

        // One jump for the lot, from where :g began: the commands it runs
        // record none of their own.
        final Position from = editor.getDot();
        if (from != null)
            state.jumped(editor, from);
        state.holdJumps(true);
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
                try {
                    if (!VimExCommands.run(editor, state, inner))
                        throw new VimEx.BadCommand("E492: Not an editor command: " + inner.name);
                }
                catch (VimEx.BadCommand e) {
                    // An inner :s that finds nothing on this line is not an
                    // error to :g, which carries on to the next: nvim makes
                    // Xne of :g/e/s/o/X/ over "one three five" without
                    // complaint. Anything else still stops it.
                    if (e.getMessage() == null || !e.getMessage().startsWith("E486"))
                        throw e;
                }
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
            state.holdJumps(false);
        }
        state.clampCaret(editor);
    }

    /** The first unescaped occurrence of a character, or -1. */
    private static int indexOfUnescaped(String s, char c, int from) {
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

    /**
     * True when a typed name is an allowed abbreviation.
     *
     * Vim writes this as "d[elete]": the required part, then a tail of which
     * the user may type any prefix.
     */
    private static boolean matches(String typed, String required, String full) {
        return typed.length() >= required.length() && full.startsWith(typed) && typed.startsWith(required);
    }

    // --------------------------------------------------------------- go to

    /** {@code :42} -- the first non-blank of that line. */
    private static void goToLine(Editor editor, VimState state, int number) throws VimEx.BadCommand {
        // A bare address clamps both ways: :50 on three lines is the last
        // line and :0 the first. Only a command with a range refuses one
        // past the end -- :50d is E16. Both checked with nvim.
        final int lines = Math.max(1, editor.getBuffer().getLineCount());
        final Line line = VimEx.lineAt(editor, Math.max(1, Math.min(number, lines)));
        if (line == null)
            throw new VimEx.BadCommand("E16: Invalid range");
        final Position from = editor.getDot();
        if (from != null)
            state.jumped(editor, from);
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
    static VimRange linesOf(Editor editor, VimEx.Range range) throws VimEx.BadCommand {
        // Line 0 means the first line to most commands, as in vim: :0d
        // deletes line 1. Below that is an error, and so is past the end.
        if (range.first < 0 || range.last < 0)
            throw new VimEx.BadCommand("E16: Invalid range");
        final Line first = VimEx.lineAt(editor, Math.max(1, range.first));
        final Line last = VimEx.lineAt(editor, Math.max(1, range.last));
        if (first == null || last == null)
            throw new VimEx.BadCommand("E16: Invalid range");
        return VimRange.lines(first, last);
    }

    /** {@code :d}, with the register it goes to if one was named. */
    private static void delete(Editor editor, VimState state, VimEx.Command command) throws VimEx.BadCommand {
        final VimRange range = linesOf(editor, countedRange(editor, command));
        registerFrom(state, command);
        VimRegisters.getInstance()
                .deleted(state.takePendingRegister(), VimOperators.textOf(editor, range), VimRegisters.Type.LINEWISE);
        // :d is a jump in vim, as :s is.
        final Position from = editor.getDot();
        if (from != null)
            state.jumped(editor, from);
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            VimOperators.deleteRange(editor, range);
            // Vim leaves the caret on the first non-blank of the line that
            // moved up into the gap, as dd does.
            final Position dot = editor.getDot();
            if (dot != null) {
                final Position gap = new Position(dot.getLine(), 0);
                state.getMarks().noteChange(editor.getBuffer(), gap, gap, gap);
                editor.setDot(dot.getLine(), VimMotions.firstNonBlank(dot.getLine()));
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        state.clampCaret(editor);
    }

    /** {@code :y} -- the same range, copied rather than taken. */
    private static void yank(Editor editor, VimState state, VimEx.Command command) throws VimEx.BadCommand {
        final VimRange range = linesOf(editor, countedRange(editor, command));
        registerFrom(state, command);
        VimRegisters.getInstance()
                .yanked(state.takePendingRegister(), VimOperators.textOf(editor, range), VimRegisters.Type.LINEWISE);
        state.getMarks().noteChange(editor.getBuffer(), range.start, range.end, null);
    }

    /**
     * Applies the trailing {@code [register] [count]} that {@code :d} and
     * {@code :y} accept.
     *
     * A count there does not mean "repeat": it replaces the range with that
     * many lines starting at its <em>last</em> one, so {@code :1,3d 2} deletes
     * lines 3 and 4.
     */
    private static VimEx.Range countedRange(Editor editor, VimEx.Command command) {
        final String count = trailingCount(command.args);
        if (count.isEmpty())
            return command.range;
        // Read as a long and clamped below: a count too big for an int is
        // still just "to the end", as vim takes it.
        final long wanted = count.length() > 18 ? Long.MAX_VALUE : Long.parseLong(count);
        final int n = (int) Math.min(wanted, Integer.MAX_VALUE / 2);
        // A count past the end of the buffer clamps, where an address past
        // the end is an error. Vim really is asymmetric here: :1,3d 100 takes
        // what there is, and :100d takes nothing and complains.
        final int lines = Math.max(1, editor.getBuffer().getLineCount());
        return new VimEx.Range(command.range.last, Math.min(command.range.last + n - 1, lines), true);
    }

    /**
     * The count in the {@code [register] [count]} tail.
     *
     * The two need no space between them: {@code :d a2} and {@code :d a 2}
     * are the same, so the digits are taken from the end of the last word
     * rather than from a word of their own.
     */
    private static String trailingCount(String args) {
        final String s = args.trim();
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1)))
            --i;
        final String digits = s.substring(i);
        // What comes before must be a register name or nothing; digits in the
        // middle of a word are not a count. Spaces between the two do not
        // matter, so "a 2" is read the same as "a2".
        final String head = s.substring(0, i).trim();
        if (head.isEmpty() || (head.length() == 1 && VimRegisters.isValidName(head.charAt(0))))
            return digits;
        return "";
    }

    private static void registerFrom(VimState state, VimEx.Command command) {
        final String s = command.args.trim();
        if (!s.isEmpty() && !Character.isDigit(s.charAt(0)) && VimRegisters.isValidName(s.charAt(0)))
            state.setPendingRegister(s.charAt(0));
    }
}
