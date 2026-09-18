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

import java.util.HashMap;
import java.util.Map;

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * The commands that are neither motions nor operators, by the names the key
 * map table uses.
 */
public final class VimActions
{
    public interface Action
    {
        void run(MotionContext ctx);
    }

    private static final Map<String, Action> ACTIONS =
        new HashMap<String, Action>();

    private VimActions()
    {
    }

    public static Action get(String name)
    {
        return ACTIONS.get(name);
    }

    public static void register(String name, Action action)
    {
        ACTIONS.put(name, action);
    }

    static {
        register("enterInsertMode", VimActions::enterInsertMode);
        register("openLine", VimActions::openLine);
        register("selectRegister", VimActions::selectRegister);
        register("setMark", VimActions::setMark);
        register("toggleVisualMode", VimActions::toggleVisualMode);
        register("swapVisualEnds", ctx -> VimVisual.swapEnds(ctx.editor));
        register("reselectVisual", ctx -> VimVisual.reselect(ctx.editor, ctx.state));
        register("undo", VimActions::undo);
        register("redo", VimActions::redo);
        register("joinLines", VimActions::joinLines);
        register("replaceCharacter", VimActions::replaceCharacter);
        register("toggleCase", VimActions::toggleCase);
        register("put", VimActions::put);
    }

    /**
     * "x -- names the register for the command that follows.
     *
     * Runs like any other action, but the command builder has already been
     * reset by the time it does, so what it sets survives into the next one.
     */
    private static void selectRegister(MotionContext ctx)
    {
        final char name = ctx.characterArg();
        if (VimRegisters.isValidName(name))
            ctx.state.setPendingRegister(name);
    }

    /**
     * v and V.
     *
     * Typing the mode you are already in leaves visual mode, which is how vim
     * lets the same key do both.
     */
    private static void toggleVisualMode(MotionContext ctx)
    {
        final VimMode wanted = ctx.arg("linewise") ? VimMode.VISUAL_LINE
                                                    : VimMode.VISUAL;
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
    private static void undo(MotionContext ctx)
    {
        ctx.state.endInsert(ctx.editor);
        for (int i = 0; i < ctx.count; i++)
            ctx.editor.getBuffer().undo();
        ctx.state.clampCaret(ctx.editor);
    }

    /** CTRL-R. */
    private static void redo(MotionContext ctx)
    {
        for (int i = 0; i < ctx.count; i++)
            ctx.editor.getBuffer().redo();
        ctx.state.clampCaret(ctx.editor);
    }

    /**
     * J and gJ -- pull the next line up onto this one.
     *
     * J puts a single space at the join and drops the next line's indent; gJ
     * joins the lines exactly as they are. Either way the caret lands where
     * the join happened, which is what makes a following {@code .} sensible.
     */
    private static void joinLines(MotionContext ctx)
    {
        final Editor editor = ctx.editor;
        final boolean keepSpaces = ctx.arg("keepSpaces");
        // J with no count joins two lines; with a count it joins that many,
        // so the number of joins is one less.
        final int joins = Math.max(1, ctx.count - 1);

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            for (int i = 0; i < joins; i++) {
                final Position dot = editor.getDot();
                if (dot == null)
                    return;
                final Line line = dot.getLine();
                final Line next = line.next();
                if (next == null)
                    return;
                final String rest = keepSpaces ? text(next)
                                               : stripLeading(text(next));
                // The line keeps its own trailing whitespace: vim only drops
                // the indent of the line being pulled up. And it adds a space
                // only if there is not one there already.
                final String head = text(line);
                final String separator =
                    keepSpaces || rest.isEmpty() || head.isEmpty()
                        || Character.isWhitespace(head.charAt(head.length() - 1))
                    ? "" : " ";

                editor.setDot(line, head.length());
                editor.setMark(new Position(next, 0));
                // Select from the join point to the start of the next line's
                // text, then replace it with the separator.
                editor.setDot(next, next.length() - rest.length());
                editor.setMark(new Position(line, head.length()));
                editor.deleteRegion();
                editor.setMark(null);
                final Position joined = editor.getDot();
                if (!separator.isEmpty()) {
                    editor.insertString(separator);
                    editor.setDot(joined.getLine(), head.length());
                } else {
                    editor.setDot(joined.getLine(), head.length());
                }
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    private static String stripLeading(String s)
    {
        if (s == null)
            return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i)))
            ++i;
        return s.substring(i);
    }

    private static String text(Line line)
    {
        final String s = line.getText();
        return s == null ? "" : s;
    }

    /**
     * r{char} -- overwrite the character under the caret.
     *
     * With a count it overwrites that many, and does nothing at all if there
     * are not that many left on the line: vim will not do half of it.
     */
    private static void replaceCharacter(MotionContext ctx)
    {
        final char replacement = ctx.characterArg();
        if (replacement == 0)
            return;
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Line line = dot.getLine();
        if (dot.getOffset() + ctx.count > line.length())
            return;

        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < ctx.count; i++)
            text.append(replacement);

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.setMark(new Position(line, dot.getOffset() + ctx.count));
            editor.setDot(line, dot.getOffset());
            editor.deleteRegion();
            editor.setMark(null);
            editor.insertString(text.toString());
            // The caret ends on the last character replaced.
            final Position now = editor.getDot();
            editor.setDot(now.getLine(), Math.max(0, now.getOffset() - 1));
            editor.moveCaretToDotCol();
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
    }

    /**
     * ~ -- swap the case of the characters under the caret and step past them.
     *
     * Not the g~ operator with an l motion: that would leave the caret where
     * it started, and ~ is meant to be held down.
     */
    private static void toggleCase(MotionContext ctx)
    {
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Line line = dot.getLine();
        final int start = dot.getOffset();
        final int end = Math.min(line.length(), start + ctx.count);
        if (end <= start)
            return;

        final String was = line.getText().substring(start, end);
        final StringBuilder now = new StringBuilder(was.length());
        for (int i = 0; i < was.length(); i++) {
            final char c = was.charAt(i);
            now.append(Character.isUpperCase(c) ? Character.toLowerCase(c)
                                                : Character.toUpperCase(c));
        }

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.setMark(new Position(line, end));
            editor.setDot(line, start);
            editor.deleteRegion();
            editor.setMark(null);
            editor.insertString(now.toString());
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        // Step onto the character after the last one changed.
        final Position after = editor.getDot();
        if (after != null) {
            editor.setDot(after.getLine(),
                          Math.min(after.getOffset(), after.getLineLength()));
            editor.moveCaretToDotCol();
        }
        ctx.state.clampCaret(editor);
    }

    /** m{a-z} -- remember where the caret is. */
    private static void setMark(MotionContext ctx)
    {
        final char name = ctx.characterArg();
        if (!VimMarks.isValidName(name))
            return;
        final Position dot = ctx.editor.getDot();
        if (dot != null)
            ctx.state.getMarks().set(name, ctx.editor.getBuffer(), dot);
    }

    /**
     * p and P.
     *
     * Where the text goes depends on how it was taken, not on how it looks:
     * linewise text becomes whole new lines below or above, and characterwise
     * text is spliced in beside the caret.
     */
    private static void put(MotionContext ctx)
    {
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
            if (register.type == VimRegisters.Type.LINEWISE)
                putLinewise(editor, dot, text.toString(), after);
            else
                putCharwise(editor, dot, text.toString(), after);
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        ctx.state.clampCaret(editor);
    }

    private static char registerName(MotionContext ctx)
    {
        final char named = ctx.state.takePendingRegister();
        return named == 0 ? VimRegisters.UNNAMED : named;
    }

    private static void putLinewise(Editor editor, Position dot, String text,
                                    boolean after)
    {
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
            landOnFirstNonBlank(editor,
                                back(editor.getDot().getLine(),
                                     countNewlines(body) - 1));
            return;
        }

        editor.setDot(after ? next : line, 0);
        editor.moveCaretToDotCol();
        editor.insertString(body);
        // The body ends in a newline, so the caret is now at the start of the
        // line below the block. Counting back finds the first pasted line --
        // the Line the insert started at may itself have been split by it.
        landOnFirstNonBlank(editor,
                            back(editor.getDot().getLine(), countNewlines(body)));
    }

    private static int countNewlines(String s)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '\n')
                ++n;
        return n;
    }

    private static Line back(Line line, int lines)
    {
        for (int i = 0; i < lines && line != null; i++) {
            final Line previous = line.previous();
            if (previous == null)
                break;
            line = previous;
        }
        return line;
    }

    private static void landOnFirstNonBlank(Editor editor, Line line)
    {
        if (line == null)
            return;
        editor.setDot(line, VimMotions.firstNonBlank(line));
        editor.moveCaretToDotCol();
    }

    private static void putCharwise(Editor editor, Position dot, String text,
                                    boolean after)
    {
        int offset = dot.getOffset();
        if (after && offset < dot.getLineLength())
            ++offset;
        editor.setDot(dot.getLine(), offset);
        editor.moveCaretToDotCol();
        editor.insertString(text);
        // Vim leaves the caret on the last character put, not past it.
        final Position now = editor.getDot();
        if (now != null && now.getOffset() > 0) {
            editor.setDot(now.getLine(), now.getOffset() - 1);
            editor.moveCaretToDotCol();
        }
    }

    /** i, a, I and A: the same action, differing only in where it starts. */
    private static void enterInsertMode(MotionContext ctx)
    {
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final String at = ctx.arg("at", "here");
        switch (at) {
            case "after":
                // Past the last character is where insert mode may sit.
                if (dot.getOffset() < dot.getLineLength())
                    editor.setDot(dot.getLine(), dot.getOffset() + 1);
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
        ctx.state.beginInsert(editor, VimMode.INSERT);
    }

    /**
     * o and O: open a line and start inserting on it.
     *
     * The undo step is opened before the line is split, so that undoing the
     * insert also takes the new line away, as it does in vim.
     */
    private static void openLine(MotionContext ctx)
    {
        final Editor editor = ctx.editor;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final boolean after = ctx.arg("after");
        ctx.state.beginInsert(editor, VimMode.INSERT);
        if (after) {
            editor.setDot(dot.getLine(), dot.getLineLength());
            editor.moveCaretToDotCol();
            editor.newlineAndIndent();
        } else {
            editor.setDot(dot.getLine(), 0);
            editor.moveCaretToDotCol();
            editor.newlineAndIndent();
            // The split left the caret on the line below the new one.
            final Position now = editor.getDot();
            final Line opened = now == null ? null : now.getLine().previous();
            if (opened != null) {
                editor.setDot(opened, opened.length());
                editor.moveCaretToDotCol();
            }
        }
    }
}
