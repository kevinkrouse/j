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
