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
        if (ctx.character == null || ctx.character.length() != 1)
            return;
        final char name = ctx.character.charAt(0);
        if (VimRegisters.isValidName(name))
            ctx.state.setPendingRegister(name);
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
