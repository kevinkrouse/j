/*
 * Lines.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import javax.swing.undo.CompoundEdit;

/**
 * Commands over whole lines: joining them, and moving or copying a run of
 * them somewhere else.
 *
 * Every one of these is a thing editors have and j did not. They are here
 * rather than in the modal layer because nothing about them is modal: vim's
 * {@code J}, {@code :join}, {@code :move} and {@code :copy} call in, and so
 * do the plain commands below, which is what makes them available to a key
 * map, a vimrc mapping and {@code executeCommand} alike.
 */
public final class Lines
{
    private Lines()
    {
    }

    // ---------------------------------------------------------------- join

    /**
     * Joins the next line onto this one, or the lines the selection covers.
     *
     * A single space goes in at the join and the indent of the line being
     * pulled up is dropped, which is what makes joining wrapped prose do the
     * right thing. A line already ending in whitespace gets no second space.
     */
    public static void joinLines()
    {
        joinLines(null);
    }

    /**
     * {@code joinLines} over a count: {@code joinLines 3} makes three lines
     * into one. A selection wins over the count, as it does in vim.
     */
    public static void joinLines(String parameters)
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        int joins = 1;
        if (editor.getMark() != null) {
            final Region region = new Region(editor);
            joins = Math.max(1, region.getEndLineNumber()
                                - region.getBeginLineNumber());
            editor.setDot(region.getBegin());
            editor.unmark();
        } else if (parameters != null && !parameters.trim().isEmpty()) {
            try {
                // A count names lines, not joins, so three lines is two.
                joins = Math.max(1, Integer.parseInt(parameters.trim()) - 1);
            }
            catch (NumberFormatException e) {
                editor.status("joinLines: not a number: " + parameters.trim());
                return;
            }
        }
        editor.moveCaretToDotCol();
        join(editor, joins, false);
    }

    /**
     * Joins this many times, starting at the caret.
     *
     * @param keepSpaces true to splice the lines exactly as they are, which
     *                   is what vim's {@code gJ} does
     */
    public static void join(Editor editor, int joins, boolean keepSpaces)
    {
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            // The caret move belongs to the change: j's undo works out how
            // much to take back from where the caret is when undo runs.
            editor.addUndo(SimpleEdit.MOVE);
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
                // The line keeps its own trailing whitespace: only the indent
                // of the line being pulled up is dropped. And a space goes in
                // only if there is not one there already.
                final String head = text(line);
                final String separator =
                    keepSpaces || rest.isEmpty() || head.isEmpty()
                        || Character.isWhitespace(head.charAt(head.length() - 1))
                    ? "" : " ";

                editor.setDot(next, next.length() - rest.length());
                editor.setMark(new Position(line, head.length()));
                editor.deleteRegion();
                editor.setMark(null);
                final Position joined = editor.getDot();
                if (!separator.isEmpty())
                    editor.insertString(separator);
                // Recorded, so that a second join's undo finds the caret
                // where the first one's edit left it.
                editor.setDot(joined.getLine(), head.length());
                editor.addUndo(SimpleEdit.MOVE);
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
    }

    // --------------------------------------------------------- move, copy

    /** Moves the current line or the selected lines up by one. */
    public static void moveLinesUp()
    {
        shift(-1);
    }

    /** Moves the current line or the selected lines down by one. */
    public static void moveLinesDown()
    {
        shift(1);
    }

    /** Copies the current line or the selected lines below themselves. */
    public static void duplicateLines()
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final int[] span = selectedSpan(editor);
        if (span == null)
            return;
        // The selection has been read; leaving it set would make the insert
        // below replace it rather than add to it.
        editor.unmark();
        copyLines(editor, span[0], span[1], span[1]);
    }

    private static void shift(int by)
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final int[] span = selectedSpan(editor);
        if (span == null)
            return;
        // Moving up by one means landing after the line two above; moving
        // down means landing after the line below.
        final int target = by < 0 ? span[0] - 2 : span[1] + 1;
        if (target < 0 || target > editor.getBuffer().getLineCount())
            return;
        editor.unmark();
        moveLines(editor, span[0], span[1], target);
    }

    /** The 1-based line span the selection covers, or the caret's line. */
    private static int[] selectedSpan(Editor editor)
    {
        final Position dot = editor.getDot();
        if (dot == null)
            return null;
        editor.getBuffer().renumber();
        if (editor.getMark() == null)
            return new int[] {dot.lineNumber() + 1, dot.lineNumber() + 1};
        final Region region = new Region(editor);
        int last = region.getEndLineNumber();
        // A selection ending at the start of a line does not include it.
        if (region.getEnd().getOffset() == 0 && last > region.getBeginLineNumber())
            --last;
        return new int[] {region.getBeginLineNumber() + 1, last + 1};
    }

    /**
     * Moves lines {@code first} to {@code last} to just after line
     * {@code after}, all 1-based; {@code after} of 0 means above the first
     * line. Leaves the caret on the last line moved.
     */
    public static void moveLines(Editor editor, int first, int last, int after)
    {
        transfer(editor, first, last, after, true);
    }

    /** {@link #moveLines}, but leaving the originals where they are. */
    public static void copyLines(Editor editor, int first, int last, int after)
    {
        transfer(editor, first, last, after, false);
    }

    private static void transfer(Editor editor, int first, int last, int after,
                                 boolean move)
    {
        editor.getBuffer().renumber();
        final Line from = lineAt(editor, first);
        final Line to = lineAt(editor, last);
        if (from == null || to == null)
            return;
        final int count = last - first + 1;
        final String block = blockOf(editor, from, to);

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.addUndo(SimpleEdit.MOVE);
            int at = after;
            if (move) {
                removeLines(editor, from, to);
                // Taking the lines out shifts everything below them up, so a
                // destination past the range has to come back by as many, and
                // one inside it collapses to just above where they were.
                if (after > last)
                    at = after - count;
                else if (after >= first)
                    at = first - 1;
            }
            insertBlock(editor, at, block);
            editor.getBuffer().renumber();
            final Line landed = lineAt(editor, at + count);
            if (landed != null) {
                editor.setDot(landed, 0);
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
    }

    /** The text of a run of lines, always ending in a newline. */
    private static String blockOf(Editor editor, Line from, Line to)
    {
        final StringBuilder sb = new StringBuilder();
        for (Line line = from; line != null; line = line.next()) {
            sb.append(text(line)).append('\n');
            if (line == to)
                break;
        }
        return sb.toString();
    }

    /** Takes out a run of whole lines, newlines and all. */
    private static void removeLines(Editor editor, Line from, Line to)
    {
        final Line after = to.next();
        if (after != null) {
            editor.setDot(from, 0);
            editor.setMark(new Position(after, 0));
        } else {
            // No line after, so there is no newline at the end of the run to
            // take with it; the one in front goes instead.
            final Line before = from.previous();
            if (before == null) {
                editor.setDot(from, 0);
                editor.setMark(new Position(to, to.length()));
            } else {
                editor.setDot(before, before.length());
                editor.setMark(new Position(to, to.length()));
            }
        }
        editor.moveCaretToDotCol();
        editor.deleteRegion();
        editor.setMark(null);
    }

    /** Puts whole lines after the given 1-based line, 0 meaning before the first. */
    private static void insertBlock(Editor editor, int after, String block)
    {
        if (after <= 0) {
            final Line first = editor.getBuffer().getFirstLine();
            if (first == null)
                return;
            editor.setDot(first, 0);
            editor.moveCaretToDotCol();
            editor.insertString(block);
            return;
        }
        final Line line = lineAt(editor, after);
        if (line == null)
            return;
        final Line next = line.next();
        if (next != null) {
            editor.setDot(next, 0);
            editor.moveCaretToDotCol();
            editor.insertString(block);
        } else {
            // Past the last line the block's own trailing newline would leave
            // an empty line behind, so the newline goes in front instead.
            editor.setDot(line, line.length());
            editor.moveCaretToDotCol();
            editor.insertString("\n" + block.substring(0, block.length() - 1));
        }
    }

    // ------------------------------------------------------------ helpers

    /** The line a 1-based number names, or null if the buffer is shorter. */
    public static Line lineAt(Editor editor, int number)
    {
        if (number < 1)
            return null;
        Line line = editor.getBuffer().getFirstLine();
        for (int i = 1; i < number && line != null; i++)
            line = line.next();
        return line;
    }

    private static String text(Line line)
    {
        final String s = line.getText();
        return s == null ? "" : s;
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
}
