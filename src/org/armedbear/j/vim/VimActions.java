/*
 * VimActions.java
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoableEdit;
import org.armedbear.j.Block;
import org.armedbear.j.Buffer;
import org.armedbear.j.CaretCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.JEvent;
import org.armedbear.j.Line;
import org.armedbear.j.Lines;
import org.armedbear.j.NumberCommands;
import org.armedbear.j.Position;
import org.armedbear.j.RegionCommands;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.UndoBoundary;
import org.armedbear.j.UndoManager;
import org.armedbear.j.Words;

/**
 * The commands that are neither motions nor operators, by the names the key
 * map table uses.
 */
public final class VimActions {
    public interface Action {
        void run(MotionContext ctx);
    }

    private static final Map<String, Action> ACTIONS =
        new HashMap<String, Action>();

    private VimActions() {}

    public static Action get(String name) {
        return ACTIONS.get(name);
    }

    public static void register(String name, Action action) {
        ACTIONS.put(name, action);
    }

    static {
        register("enterInsertMode", VimActions::enterInsertMode);
        register("openLine", VimActions::openLine);
        register("selectRegister", VimActions::selectRegister);
        register("setMark", VimActions::setMark);
        register("toggleVisualMode", VimActions::toggleVisualMode);
        register(
            "swapVisualEnds",
            ctx -> VimVisual.swapEnds(
                ctx.editor,
                ctx.state,
                ctx.arg("sideways")
            )
        );
        register("reselectVisual", ctx -> VimVisual.reselect(ctx.editor, ctx.state));
        register("undo", VimActions::undo);
        register("redo", VimActions::redo);
        register("joinLines", VimActions::joinLines);
        register("replaceCharacter", VimActions::replaceCharacter);
        register("toggleCase", VimActions::toggleCase);
        register("repeatLastChange", VimActions::repeatLastChange);
        register("put", VimActions::put);
        register("addToNumber", VimActions::addToNumber);
        register("insertShift", VimActions::insertShift);
        register("insertDeleteBack", VimActions::insertDeleteBack);
        register("insertRegister", VimActions::insertRegister);
        register(
            "insertOneCommand",
            ctx -> ctx.handler.runOneCommand(ctx.editor)
        );
        register("visualJoin", VimActions::visualJoin);
        register("visualReplace", VimActions::visualReplace);
        register("visualPut", VimActions::visualPut);
        register("switchWindow", VimActions::switchWindow);
        register("travelJumps", VimActions::travelJumps);
        register("visualInsert", VimActions::visualInsert);
        register(
            "incsearchStep",
            ctx -> ctx.handler.searchStep(ctx.editor, ctx.arg("forward"))
        );
        register(
            "closeWindow",
            ctx -> VimExCommands.closeWindow(
                ctx.editor,
                ctx.arg("quit")
            )
        );
        register(
            "swapLastSelection",
            ctx -> VimVisual.swapWithLast(ctx.editor, ctx.state)
        );
    }

    /**
     * "x -- names the register for the command that follows.
     *
     * Runs like any other action, but the command builder has already been
     * reset by the time it does, so what it sets survives into the next one.
     */
    private static void selectRegister(MotionContext ctx) {
        final char name = ctx.characterArg();
        if (VimRegisters.isValidName(name))
            ctx.state.setPendingRegister(name);
    }

    /**
     * v, V and CTRL-V.
     *
     * Typing the mode you are already in leaves visual mode, which is how vim
     * lets the same key do both.
     */
    private static void toggleVisualMode(MotionContext ctx) {
        final VimMode wanted = ctx.arg("linewise")
            ? VimMode.VISUAL_LINE
            : ctx.arg("block") ? VimMode.VISUAL_BLOCK : VimMode.VISUAL;
        if (ctx.state.getMode() == wanted)
            VimVisual.leave(ctx.editor, ctx.state);
        else
            VimVisual.enter(ctx.editor, ctx.state, wanted);
    }

    /**
     * u.
     *
     * An insert session still in progress is closed first, so that u undoes
     * the insert as one step rather than joining the step before it.
     */
    private static void undo(MotionContext ctx) {
        ctx.state.endInsert(ctx.editor);
        // Under the write lock, as j's own undo takes it: restoring a line
        // edit expects it.
        final Buffer buffer = ctx.editor.getBuffer();
        buffer.withWriteLock(() -> {
            for (int i = 0; i < ctx.count; i++) {
                buffer.undo();
                noteUndoneAt(ctx.editor);
            }
        });
        ctx.state.clearSelectionUnlessVisual(ctx.editor);
        ctx.state.clampCaret(ctx.editor);
    }

    /**
     * CTRL-R.
     *
     * The caret goes where undoing the change put it, as nvim leaves it,
     * whatever has moved it since. j's own redo would put it back where it
     * was when u was pressed, which may be nowhere near the change.
     */
    private static void redo(MotionContext ctx) {
        final Buffer buffer = ctx.editor.getBuffer();
        buffer.withWriteLock(() -> {
            for (int i = 0; i < ctx.count; i++) {
                buffer.redo();
                moveToUndonePlace(ctx.editor);
            }
        });
        ctx.state.clearSelectionUnlessVisual(ctx.editor);
        ctx.state.clampCaret(ctx.editor);
    }

    /**
     * Where u left the caret, by the change it took back, for CTRL-R. Before
     * the caret is pulled back onto a character: after A, u rests on the last
     * one and CTRL-R just past it. Weak keys, so a change that falls off the
     * undo list takes its entry with it.
     */
    private static final Map<UndoableEdit, int[]> undonePlaces =
        new WeakHashMap<UndoableEdit, int[]>();

    private static void noteUndoneAt(Editor editor) {
        final UndoManager undo = editor.getBuffer().getUndoManager();
        final Position dot = editor.getDot();
        if (undo == null || dot == null)
            return;
        final UndoableEdit edit = undo.lastUndone();
        // One shared instance, so it cannot stand for one change.
        if (edit == null || edit instanceof UndoBoundary)
            return;
        editor.getBuffer().renumber();
        undonePlaces.put(edit, new int[] { dot.lineNumber(), dot.getOffset() });
    }

    private static void moveToUndonePlace(Editor editor) {
        final UndoManager undo = editor.getBuffer().getUndoManager();
        if (undo == null)
            return;
        final UndoableEdit edit = undo.lastRedone();
        final int[] at = edit == null ? null : undonePlaces.get(edit);
        if (at == null)
            return;
        final Line line = editor.getBuffer().getLine(at[0]);
        if (line == null)
            return;
        editor.setDot(line, Math.min(at[1], line.length()));
        editor.moveCaretToDotCol();
    }

    /**
     * J and gJ -- pull the next line up onto this one.
     *
     * J puts a single space at the join and drops the next line's indent; gJ
     * joins the lines exactly as they are. Either way the caret lands where
     * the join happened, which is what makes a following {@code .} sensible.
     */
    private static void joinLines(MotionContext ctx) {
        // J with no count joins two lines; with a count it joins that many,
        // so the number of joins is one less.
        joinAt(
            ctx.editor,
            ctx.state,
            Math.max(1, ctx.count - 1),
            ctx.arg("keepSpaces")
        );
    }

    /**
     * Joins this many times, starting at the caret.
     *
     * Shared by {@code J} and by {@code :join}, which differ only in how they
     * work out how many joins to do and where to start.
     */
    static void joinAt(
        Editor editor,
        VimState state,
        int joins,
        boolean keepSpaces
    ) {
        final Position dot = editor.getDot();
        final int joinedAt = dot != null ? dot.getLineLength() : 0;
        final int modCount = editor.getBuffer().getModCount();
        Lines.join(editor, joins, keepSpaces);
        // '[ where the first join was, '] at the end of the joined line.
        final Position now = editor.getDot();
        if (now != null && editor.getBuffer().getModCount() != modCount) {
            final Line line = now.getLine();
            final Position start =
                new Position(line, Math.min(joinedAt, line.length()));
            state.getMarks()
                .noteEdit(
                    editor.getBuffer(),
                    start,
                    new Position(line, line.length())
                );
        }
        state.clampCaret(editor);
    }

    /**
     * Insert-mode CTRL-T and CTRL-D: one shiftwidth more or less indent on
     * this line, rounded to a multiple of it, with the caret staying with the
     * text -- even from inside the indent, as nvim moves it.
     */
    private static void insertShift(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (!editor.checkReadOnly())
            return;
        if (dot == null)
            return;
        final Line line = dot.getLine();
        final int offset = dot.getOffset();
        final int delta = Lines.shiftToMultiple(
            editor,
            line,
            ctx.arg("right"),
            VimOptions.shiftWidth(editor.getBuffer())
        );
        editor.setDot(
            line,
            Math.max(
                0,
                Math.min(
                    line.length(),
                    offset + delta
                )
            )
        );
        editor.moveCaretToDotCol();
    }

    /**
     * CTRL-W and CTRL-U in insert mode: delete back to the start of the word,
     * as b goes, or of the line's text. Both stop once where typing began,
     * and at the start of a line join it to the one before. In replace mode
     * they are Backspace that many times, putting back what was typed over.
     */
    private static void insertDeleteBack(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final VimState state = ctx.state;
        final Position dot = editor.getDot();
        if (dot == null || !editor.checkReadOnly())
            return;
        final boolean replace = state.getMode() == VimMode.REPLACE;
        final Line line = dot.getLine();
        final int caret = dot.getOffset();
        int to = 0;
        if (caret > 0 && ctx.arg("word")) {
            final Position word = Words.backwardToWordStart(
                dot,
                editor.getBuffer().getMode(),
                false
            );
            if (word != null && word.getLine() == line)
                to = word.getOffset();
        } else if (caret > 0) {
            // Autoindent is on, as in nvim: the indent stays.
            final int indent = Lines.leadingBlanks(line);
            if (indent < caret)
                to = indent;
        }
        to = Math.max(to, state.backStop(editor));
        if (replace) {
            while (editor.getDot().getOffset() > to)
                replaceBackspace(editor, state);
        } else if (caret == 0) {
            editor.backspace();
        } else {
            editor.deleteRegion(new Position(line, to), new Position(dot));
        }
        state.insertDeletedBack(editor);
    }

    /**
     * CTRL-O and CTRL-I -- back and forward along j's jump list, count
     * entries at a time, into another buffer if that is where it goes.
     */
    private static void travelJumps(MotionContext ctx) {
        ctx.state.travel(
            ctx.editor,
            ctx.arg("forward") ? ctx.count : -ctx.count
        );
    }

    /**
     * CTRL-W w and W: the next window or the one before, and with a count
     * that window, the top left first.
     */
    private static void switchWindow(MotionContext ctx) {
        if (ctx.countGiven)
            ctx.editor.gotoWindow(String.valueOf(ctx.count));
        else if (ctx.arg("backward"))
            ctx.editor.previousWindow();
        else
            ctx.editor.nextWindow();
    }

    /**
     * CTRL-A and CTRL-X: add the count to the number at or after the caret.
     * In visual mode, to the first number of each line the selection takes
     * in, and with g one count more for each number after the first. '[ and
     * '] go around the numbers, '. at the start of the first line.
     */
    private static void addToNumber(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final boolean subtract = ctx.arg("subtract");
        if (!ctx.state.getMode().isVisual()) {
            final Position dot = editor.getDot();
            if (dot == null)
                return;
            final Line line = dot.getLine();
            final NumberCommands.Change change = NumberCommands.add(
                editor,
                line,
                dot.getOffset(),
                -1,
                ctx.count,
                subtract
            );
            if (change != null)
                ctx.state.getMarks()
                    .noteChange(
                        editor.getBuffer(),
                        new Position(line, change.start),
                        new Position(line, change.last() + 1),
                        new Position(line, 0)
                    );
            return;
        }
        final VimRange range = VimVisual.take(editor, ctx.state);
        if (range == null)
            return;
        // Undo gives the caret back at the start of the selection.
        final Line first = range.start.getLine();
        editor.setDot(first, range.start.getOffset());
        editor.moveCaretToDotCol();
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            final NumberCommands.Changes changes = NumberCommands.addOverLines(
                editor,
                range.start,
                range.end,
                ctx.count,
                subtract,
                ctx.arg("progressive")
            );
            if (changes != null)
                ctx.state.getMarks()
                    .noteChange(
                        editor.getBuffer(),
                        new Position(changes.firstLine, changes.first.start),
                        new Position(changes.lastLine, changes.last.last() + 1),
                        new Position(first, 0)
                    );
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    /**
     * CTRL-R in insert mode: the register's text, typed. Only the registers
     * p knows.
     */
    private static void insertRegister(MotionContext ctx) {
        final char name = ctx.characterArg();
        if (!VimRegisters.isValidName(name))
            return;
        final VimRegisters.Register register =
            VimRegisters.getInstance().get(name);
        if (register != null)
            ctx.handler.typeText(ctx.editor, register.text);
    }

    /**
     * r{char} -- overwrite the character under the caret.
     *
     * With a count it overwrites that many, and does nothing at all if there
     * are not that many left on the line: vim will not do half of it.
     */
    private static void replaceCharacter(MotionContext ctx) {
        final int replacement = ctx.codePointArg();
        if (replacement == 0)
            return;
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        // j's own replaceChar, which refuses rather than doing part of it
        // when the line is too short.
        final Line line = dot.getLine();
        final int start = dot.getOffset();
        if (
            !CaretCommands.replaceChars(
                editor,
                line,
                start,
                replacement,
                ctx.count
            )
        )
            return;
        int end = start;
        for (int i = 0; i < ctx.count; i++)
            end = CodePoints.next(line, end);
        ctx.state.getMarks()
            .noteEdit(
                editor.getBuffer(),
                new Position(line, start),
                new Position(line, end)
            );
    }

    /**
     * ~ -- swap the case of the characters under the caret and step past them.
     *
     * Not the g~ operator with an l motion: that would leave the caret where
     * it started, and ~ is meant to be held down.
     */
    private static void toggleCase(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Line line = dot.getLine();
        final int start = dot.getOffset();
        final int end = Math.min(line.length(), start + ctx.count);
        if (end <= start)
            return;

        final String now =
            RegionCommands.Case.TOGGLE.apply(line.getText().substring(start, end));

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.deleteRegion(
                new Position(line, start),
                new Position(line, end)
            );
            editor.insertString(now);
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        // Step onto the character after the last one changed.
        final Position after = editor.getDot();
        if (after != null) {
            ctx.state.getMarks()
                .noteEdit(
                    editor.getBuffer(),
                    new Position(after.getLine(), start),
                    new Position(after.getLine(), end)
                );
            editor.setDot(
                after.getLine(),
                Math.min(after.getOffset(), after.getLineLength())
            );
            editor.moveCaretToDotCol();
        }
        ctx.state.clampCaret(editor);
    }

    /** . -- do the last change again. */
    private static void repeatLastChange(MotionContext ctx) {
        ctx.handler.repeatLastChange(ctx.editor, ctx.count, ctx.countGiven);
    }

    /** m{a-z} -- remember where the caret is. */
    private static void setMark(MotionContext ctx) {
        final char name = ctx.characterArg();
        final Position here = ctx.editor.getDot();
        // m' and m` set the previous context mark, as a jump from here would.
        if ((name == '\'' || name == '`') && here != null) {
            ctx.state.jumped(ctx.editor, here);
            return;
        }
        if (VimMarks.isValidName(name) && here != null)
            ctx.state.getMarks().set(name, ctx.editor.getBuffer(), here);
    }

    /**
     * p and P.
     *
     * Where the text goes depends on how it was taken, not on how it looks:
     * linewise text becomes whole new lines below or above, and characterwise
     * text is spliced in beside the caret.
     */
    private static void put(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final VimRegisters.Register register =
            VimRegisters.getInstance().get(registerName(ctx));
        if (register == null || register.text.isEmpty())
            return;
        final Position dot = editor.getDot();
        if (dot == null)
            return;

        final boolean after = ctx.arg("after");
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < ctx.count; i++)
            text.append(register.text);

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            // Where the caret was before any of this, so undo gives it back.
            // A put moves it to the insertion point first, and that move is
            // as much a part of the change as the text is.
            VimOperators.recordCaret(editor);
            if (register.type == VimRegisters.Type.LINEWISE)
                putLinewise(editor, ctx.state, dot, text.toString(), after);
            else if (register.type == VimRegisters.Type.BLOCKWISE)
                putBlockwise(
                    editor,
                    ctx.state,
                    dot,
                    register.text,
                    ctx.count,
                    after
                );
            else
                putCharwise(editor, ctx.state, dot, text.toString(), after);
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    /**
     * A block from the register, its top left at the caret or just after
     * it: j's Block.put. A count repeats each of its lines along.
     */
    private static void putBlockwise(
        Editor editor,
        VimState state,
        Position dot,
        String text,
        int count,
        boolean after
    ) {
        final List<String> pieces = new ArrayList<String>();
        for (String piece : text.split("\n", -1))
            pieces.add(piece.repeat(Math.max(1, count)));
        int col = editor.getBuffer().getCol(dot);
        if (after && dot.getOffset() < dot.getLineLength())
            col = editor.getBuffer()
                .getCol(
                    new Position(
                        dot.getLine(),
                        CodePoints.next(dot.getLine(), dot.getOffset())
                    )
                );
        Block.put(editor, dot.getLine(), col, pieces);
        final Position at = editor.getDot();
        if (at != null)
            state.getMarks().noteChange(editor.getBuffer(), at, at, at);
    }

    private static char registerName(MotionContext ctx) {
        final char named = ctx.state.takePendingRegister();
        return named == 0 ? VimRegisters.UNNAMED : named;
    }

    private static void putLinewise(
        Editor editor,
        VimState state,
        Position dot,
        String text,
        boolean after
    ) {
        // Linewise text always ends with a newline; inserting it at the start
        // of a line is what turns it back into whole lines.
        final String body = text.endsWith("\n") ? text : text + "\n";
        final Line line = dot.getLine();
        final Line next = line.next();

        if (after && next == null) {
            // Nothing below to insert in front of, so append instead. The
            // caret ends on the last pasted line rather than past the block.
            editor.setDot(line, line.length());
            editor.moveCaretToDotCol();
            editor.insertString("\n" + body.substring(0, body.length() - 1));
            final Line first =
                back(editor.getDot().getLine(), countNewlines(body) - 1);
            markPut(editor, state, new Position(first, 0));
            landOnFirstNonBlank(editor, first);
            return;
        }

        editor.setDot(after ? next : line, 0);
        editor.moveCaretToDotCol();
        editor.insertString(body);
        // The body ends in a newline, so the caret is now at the start of the
        // line below the block. Counting back finds the first pasted line --
        // the Line the insert started at may itself have been split by it.
        final Line first = back(editor.getDot().getLine(), countNewlines(body));
        markPut(editor, state, new Position(first, 0));
        landOnFirstNonBlank(editor, first);
    }

    /** '[ and '] around text just put, ending at the caret; '. at its start. */
    private static void markPut(Editor editor, VimState state, Position start) {
        final Position end = editor.getDot();
        if (end != null)
            state.getMarks().noteEdit(editor.getBuffer(), start, end);
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '\n')
                ++n;
        return n;
    }

    private static Line back(Line line, int lines) {
        for (int i = 0; i < lines && line != null; i++) {
            final Line previous = line.previous();
            if (previous == null)
                break;
            line = previous;
        }
        return line;
    }

    private static void landOnFirstNonBlank(Editor editor, Line line) {
        if (line == null)
            return;
        moveAfterEdit(editor, line, VimMotions.firstNonBlank(line));
    }

    /**
     * Puts the caret somewhere after an insert, recording the move.
     *
     * j's {@code UndoInsertString} works out how much text to take back from
     * where the caret is <em>when undo runs</em>, not from where the insert
     * left it. Moving the caret afterwards without a record would therefore
     * make undo take back the wrong lines -- p in the middle of a buffer then
     * u used to duplicate the line below instead of removing the pasted one.
     * A compound edit undoes its parts in reverse, so recording the move puts
     * the caret back first and the insert then sees what it expects.
     */
    private static void moveAfterEdit(Editor editor, Line line, int offset) {
        editor.addUndo(SimpleEdit.MOVE);
        editor.setDot(line, offset);
        editor.moveCaretToDotCol();
    }

    private static void putCharwise(
        Editor editor,
        VimState state,
        Position dot,
        String text,
        boolean after
    ) {
        int offset = dot.getOffset();
        if (after && offset < dot.getLineLength())
            offset = CodePoints.next(dot.getLine(), offset);
        editor.setDot(dot.getLine(), offset);
        editor.moveCaretToDotCol();
        editor.insertString(text);
        // Vim leaves the caret on the last character put, not past it.
        final Position now = editor.getDot();
        if (now != null)
            markPut(
                editor,
                state,
                new Position(
                    back(now.getLine(), countNewlines(text)),
                    offset
                )
            );
        if (now != null && now.getOffset() > 0)
            moveAfterEdit(
                editor,
                now.getLine(),
                CodePoints.previous(now.getLine(), now.getOffset())
            );
    }

    /** i, a, I and A: the same action, differing only in where it starts. */
    private static void enterInsertMode(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final String at = ctx.arg("at", "here");
        switch (at) {
            case "after":
                // Past the last character is where insert mode may sit.
                if (dot.getOffset() < dot.getLineLength())
                    editor.setDot(
                        dot.getLine(),
                        CodePoints.next(dot.getLine(), dot.getOffset())
                    );
                break;
            case "firstNonBlank":
                editor.setDot(dot.getLine(), VimMotions.firstNonBlank(dot.getLine()));
                break;
            case "eol":
                editor.setDot(dot.getLine(), dot.getLineLength());
                break;
            default:
                break;
        }
        editor.moveCaretToDotCol();
        ctx.state.beginInsert(
            editor,
            ctx.arg("replace")
                ? VimMode.REPLACE
                : VimMode.INSERT
        );
        // 3iab<Esc> types ab three times: Escape types the other two.
        ctx.state.setInsertRepeat(ctx.count - 1, false);
    }

    /**
     * I and A in visual mode. Over a block, an insert at its left edge or
     * after its right one -- the end of each line after $ -- which Escape
     * puts on every line of it; otherwise at the start of the selection's
     * first line, or just after the selection.
     */
    private static void visualInsert(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final VimState state = ctx.state;
        final boolean append = ctx.arg("append");
        final boolean block = state.getMode() == VimMode.VISUAL_BLOCK;
        final Block selected = block ? VimVisual.block(editor, state) : null;
        final VimRange range = VimVisual.take(editor, state);
        if (range == null)
            return;
        state.beginInsert(editor, VimMode.INSERT);
        if (block) {
            final Line first = selected.getFirstLine();
            final Position at = !append
                ? Block.positionAt(
                    editor.getBuffer(),
                    first,
                    selected.getStartCol()
                )
                : selected.isToEol()
                    ? new Position(first, first.length())
                    : selected.appendPoint(editor, first);
            editor.setDot(at);
            editor.moveCaretToDotCol();
            state.beginBlockInsert(
                editor,
                selected,
                append,
                Block.positionAt(
                    editor.getBuffer(),
                    first,
                    selected.getStartCol()
                )
            );
            return;
        }
        final Position at = append
            ? range.end
            : new Position(range.start.getLine(), 0);
        editor.setDot(
            append && range.linewise
                ? new Position(range.last, range.last.length())
                : at
        );
        editor.moveCaretToDotCol();
    }

    // ------------------------------------------------------- replace mode

    /**
     * One keystroke of R: type over the character the caret is on.
     *
     * Past the end of the line there is nothing to type over, so it appends,
     * which is what vim does and why R can lengthen a line but never shorten
     * one. Each keystroke notes what it displaced so that BS can undo it one
     * character at a time without ending the session.
     */
    public static void replaceTypedCharacter(
        Editor editor,
        VimState state,
        char c
    ) {
        final Position dot = editor.getDot();
        if (!editor.checkReadOnly())
            return;
        if (dot == null)
            return;
        final Line line = dot.getLine();
        final int offset = dot.getOffset();
        if (offset >= line.length()) {
            editor.insertString(String.valueOf(c));
            noteReplaced(editor, state, VimState.APPENDED);
            return;
        }
        final int was = line.getText().codePointAt(offset);
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            final int next = CodePoints.next(line, offset);
            editor.deleteRegion(
                new Position(line, offset),
                new Position(line, next)
            );
            editor.insertString(String.valueOf(c));
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        noteReplaced(editor, state, was);
    }

    /** Records a replace keystroke against where the edit left the caret. */
    private static void noteReplaced(Editor editor, VimState state, int was) {
        final Position now = editor.getDot();
        if (now != null)
            state.pushReplaced(was, now.getLine(), now.getOffset());
    }

    /**
     * BS in replace mode: step left, putting back what R typed over there.
     *
     * Vim's BS here is not a delete. It walks the session backwards, and once
     * it reaches the column R started in it only moves the caret -- the text
     * to the left was never this session's to restore.
     */
    public static void replaceBackspace(Editor editor, VimState state) {
        final Position dot = editor.getDot();
        if (!editor.checkReadOnly())
            return;
        if (dot == null || dot.getOffset() == 0)
            return;
        final Line line = dot.getLine();
        final int offset = CodePoints.previous(line, dot.getOffset());
        final int was = state.popReplaced(line, dot.getOffset());
        if (was == 0) {
            editor.setDot(line, offset);
            editor.moveCaretToDotCol();
            return;
        }
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.deleteRegion(
                new Position(line, offset),
                new Position(line, dot.getOffset())
            );
            if (was != VimState.APPENDED)
                editor.insertString(new String(Character.toChars(was)));
            // Back to the character just restored, from wherever the edit
            // left the caret rather than from the line captured above.
            final Position now = editor.getDot();
            if (now != null) {
                editor.setDot(now.getLine(), offset);
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
    }

    /**
     * Enter in insert mode: whatever j binds Enter to in this mode, with the
     * indent it gives the new line the session's until something is typed,
     * as after o. A line left behind with nothing typed after its indent is
     * emptied, as vim does: A<CR><CR><Esc> under "  x" leaves two empty lines.
     *
     * @return false when j binds nothing to it
     */
    static boolean insertNewline(Editor editor, VimState state, JEvent enter) {
        final Position dot = editor.getDot();
        if (dot == null)
            return false;
        final Line left = dot.getLine();
        final boolean untouched = state.isUntouchedAutoIndent(left);
        if (!editor.handleKeyMapEvent(enter))
            return false;
        final Position now = editor.getDot();
        // Read-only, or bound to something that is not a line break.
        if (now == null || now.getLine() == left)
            return true;
        if (untouched && left.length() > 0) {
            editor.deleteRegion(
                new Position(left, 0),
                new Position(left, left.length())
            );
            editor.setDot(now);
            editor.moveCaretToDotCol();
        }
        state.noteAutoIndent(now.getLine());
        return true;
    }

    /**
     * o and O: open a line and start inserting on it.
     *
     * The undo step is opened before the line is split, so that undoing the
     * insert also takes the new line away, as it does in vim.
     */
    private static void openLine(MotionContext ctx) {
        final Editor editor = ctx.editor;
        if (editor.getDot() == null)
            return;
        ctx.state.beginInsert(editor, VimMode.INSERT);
        // 3o opens three lines: Escape opens the other two, below this one.
        ctx.state.setInsertRepeat(ctx.count - 1, true);
        // Inside the insert session's undo step, before the caret moves to
        // where the new line goes.
        VimOperators.recordCaret(editor);
        openLine(editor, ctx.state, ctx.arg("after"));
    }

    /**
     * Opens a line below or above the caret's and puts the caret on it,
     * indented. The indent is the session's until something is typed.
     */
    static void openLine(Editor editor, VimState state, boolean after) {
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        if (after) {
            editor.setDot(dot.getLine(), dot.getLineLength());
            editor.moveCaretToDotCol();
            editor.newlineAndIndent();
        } else {
            // A plain split: newlineAndIndent would reindent the line below,
            // which O must leave alone. The new line is indented as o's is:
            // by j's indentLine where the mode knows the language, which is
            // nvim with filetype indent, and otherwise with the line's own
            // indent, which is vim's autoindent.
            final String indent = VimOperators.leadingBlanks(dot.getLine());
            editor.setDot(dot.getLine(), 0);
            editor.moveCaretToDotCol();
            editor.newline();
            final Position now = editor.getDot();
            final Line opened = now == null ? null : now.getLine().previous();
            if (opened != null) {
                editor.setDot(opened, 0);
                editor.moveCaretToDotCol();
                if (editor.getMode().canIndent())
                    editor.indentLine();
                else if (!indent.isEmpty())
                    editor.insertString(indent);
            }
        }
        // Whatever indent j gave the new line is the session's own, not the
        // user's, until something is typed after it.
        final Position now = editor.getDot();
        if (now != null)
            state.noteAutoIndent(now.getLine());
    }

    // ---------------------------------------------------------- visual mode

    /**
     * Visual J and gJ: join the selected lines, or this one and the next if
     * the selection is on one line. j's own join does the work.
     */
    private static void visualJoin(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final Position anchor = editor.getMark();
        final Position head = editor.getDot();
        if (anchor == null || head == null)
            return;
        editor.getBuffer().renumber();
        final Line first = anchor.isBefore(head)
            ? anchor.getLine()
            : head.getLine();
        final int joins = Math.max(
            1,
            Math.abs(
                anchor.lineNumber()
                    - head.lineNumber()
            )
        );
        // Where undo gives the caret back, as nvim does: where it was if
        // that was on the first line, else that line's start.
        final int column = head.getLine() == first ? head.getOffset() : 0;
        VimVisual.take(editor, ctx.state);
        editor.setDot(first, column);
        editor.moveCaretToDotCol();
        joinAt(editor, ctx.state, joins, ctx.arg("keepSpaces"));
    }

    /**
     * Visual r{char}: every selected character becomes this one. Line ends
     * stay; the caret goes to the start of the selection.
     */
    private static void visualReplace(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final int replacement = ctx.codePointArg();
        final VimRange range = VimVisual.take(editor, ctx.state);
        if (range == null || replacement == 0)
            return;
        if (range.block != null) {
            final String with = new String(Character.toChars(replacement));
            range.block.transform(
                editor,
                s -> with.repeat(
                    Character.codePointCount(s, 0, s.length())
                )
            );
            ctx.state.getMarks()
                .noteEdit(
                    editor.getBuffer(),
                    range.start,
                    range.end
                );
            ctx.state.clampCaret(editor);
            return;
        }
        // Undo gives the caret back at the start of the selection, as nvim
        // does, so that is where it is when the change is recorded.
        editor.setDot(range.start.getLine(), range.start.getOffset());
        editor.moveCaretToDotCol();
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            for (Line line = range.start.getLine();
                line != null;
                line = line.next()) {
                final boolean last = line == range.end.getLine();
                final int from = line == range.start.getLine()
                    ? range.start.getOffset()
                    : 0;
                final int to = last
                    ? Math.min(
                        range.end.getOffset(),
                        line.length()
                    )
                    : line.length();
                if (to > from)
                    CaretCommands.replaceChars(
                        editor,
                        line,
                        from,
                        replacement,
                        Character.codePointCount(line.getText(), from, to)
                    );
                if (last)
                    break;
            }
            // Recorded, or undo takes back the last line's edit from here.
            moveAfterEdit(
                editor,
                range.start.getLine(),
                range.start.getOffset()
            );
            ctx.state.getMarks()
                .noteEdit(
                    editor.getBuffer(),
                    range.start,
                    range.end
                );
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    /**
     * Visual p and P over a block: the block goes, and the register comes
     * in its place -- a block at its top left, characters on each of its
     * lines, whole lines after its last -- as nvim does.
     */
    private static void blockPut(
        MotionContext ctx,
        Block block,
        VimRegisters.Register register,
        String text
    ) {
        final Editor editor = ctx.editor;
        final String selected = block.getText();
        final Line first = block.getFirstLine();
        final Line last = block.getLastLine();
        final int col = block.getStartCol();
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            block.delete(editor);
            if (register.type == VimRegisters.Type.BLOCKWISE) {
                Block.put(
                    editor,
                    first,
                    col,
                    Arrays.asList(register.text.split("\n", -1))
                );
            } else if (register.type == VimRegisters.Type.LINEWISE) {
                editor.setDot(last, last.length());
                editor.moveCaretToDotCol();
                editor.insertString("\n" + text);
                // Count back: an empty last line may itself have been split.
                landOnFirstNonBlank(
                    editor,
                    back(
                        editor.getDot().getLine(),
                        countNewlines(text)
                    )
                );
            } else {
                new Block(editor.getBuffer(), first, last, col, col, false)
                    .insertOnEachLine(editor, text, false);
                final Position start = Block.positionAt(
                    editor.getBuffer(),
                    first,
                    col
                );
                editor.setDot(
                    first,
                    Math.max(
                        start.getOffset(),
                        start.getOffset() + text.length() - 1
                    )
                );
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        if (ctx.arg("after"))
            VimRegisters.getInstance()
                .deleted(
                    (char) 0,
                    selected,
                    VimRegisters.Type.BLOCKWISE
                );
        ctx.state.clampCaret(editor);
    }

    /**
     * Visual p and P: the register takes the selection's place. p leaves
     * what was selected in the unnamed register; P leaves the registers
     * alone, so it can be done again.
     */
    private static void visualPut(MotionContext ctx) {
        final Editor editor = ctx.editor;
        final VimRegisters.Register register =
            VimRegisters.getInstance().get(registerName(ctx));
        final VimRange range = VimVisual.take(editor, ctx.state);
        if (range == null || register == null || register.text.isEmpty())
            return;
        final boolean lines = register.type == VimRegisters.Type.LINEWISE;
        final String once = lines && register.text.endsWith("\n")
            ? register.text.substring(0, register.text.length() - 1)
            : register.text;
        final StringBuilder text = new StringBuilder(once);
        for (int i = 1; i < ctx.count; i++)
            text.append(lines ? "\n" : "").append(once);
        if (range.block != null) {
            blockPut(ctx, range.block, register, text.toString());
            return;
        }
        final String selected = VimOperators.textOf(editor, range);

        // Undo gives the caret back at the start of the selection, as nvim
        // does, so that is where it is when the change is recorded.
        editor.setDot(range.start.getLine(), range.start.getOffset());
        editor.moveCaretToDotCol();
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            final Line first = range.start.getLine();
            if (range.linewise) {
                // Empty the lines to one and fill that: no line after the
                // selection is needed, so the end of the buffer is no case.
                final Line last = range.last;
                editor.deleteRegion(
                    new Position(first, 0),
                    new Position(last, last.length())
                );
                editor.insertString(text.toString());
                markPut(editor, ctx.state, new Position(first, 0));
                landOnFirstNonBlank(editor, first);
            } else {
                VimOperators.deleteRange(editor, range);
                if (lines) {
                    // Linewise text goes in as whole lines, splitting the
                    // line where the selection was.
                    editor.insertString("\n" + text + "\n");
                    // Counted back from the caret, as an emptied line may
                    // itself have been split rather than first.
                    final Line put = back(
                        editor.getDot().getLine(),
                        countNewlines(text.toString()) + 1
                    );
                    markPut(editor, ctx.state, new Position(put, 0));
                    landOnFirstNonBlank(editor, put);
                } else {
                    putCharwise(
                        editor,
                        ctx.state,
                        editor.getDot(),
                        text.toString(),
                        false
                    );
                }
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        if (ctx.arg("after"))
            VimRegisters.getInstance()
                .deleted(
                    (char) 0,
                    selected,
                    range.linewise
                        ? VimRegisters.Type.LINEWISE
                        : VimRegisters.Type.CHARWISE
                );
        ctx.state.clampCaret(editor);
    }
}
