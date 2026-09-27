/*
 * CaretCommands.java
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
 * Moving the caret about, and changing the one character under it.
 *
 * Three things every vi-like editor has and j did not: jumping to a named
 * character on the line, jumping to the top, middle or bottom of the window,
 * and overwriting one character. They are here rather than in the modal layer
 * because none of them is modal, and vim's {@code f}, {@code t}, {@code H},
 * {@code M}, {@code L} and {@code r} call in rather than carrying their own.
 */
public final class CaretCommands
{
    private CaretCommands()
    {
    }

    // ------------------------------------------------- find a character

    /** Where {@code f}, {@code F}, {@code t} and {@code T} land. */
    public static final class CharSearch
    {
        /** A code point, so that an emoji can be found as one character. */
        public final int target;
        public final boolean forward;
        /** True to stop one short of the character rather than on it. */
        public final boolean till;

        public CharSearch(int target, boolean forward, boolean till)
        {
            this.target = target;
            this.forward = forward;
            this.till = till;
        }
    }

    /**
     * The position of the count'th occurrence of a character on this line, or
     * null if there are not that many.
     *
     * Never leaves the line: this is vim's {@code f}, which searches the line
     * the caret is on and nothing else.
     *
     * @param repeat true when this is a {@code ;} or {@code ,} rather than a
     *               fresh search, which for {@code t} has to start one past
     *               where it is standing or it would never move
     */
    public static Position findCharacter(Position from, CharSearch search,
                                         int count, boolean repeat)
    {
        final Line line = from.getLine();
        final String text = line.getText();
        if (text == null)
            return null;

        int found = from.getOffset();
        if (search.till && repeat)
            found = step(text, found, search.forward);

        for (int i = 0; i < count; i++) {
            found = indexOf(text, search.target,
                            step(text, found, search.forward), search.forward);
            if (found < 0)
                return null;
        }
        final int landing = search.till
            ? step(text, found, !search.forward) : found;
        if (landing < 0 || landing >= text.length())
            return null;
        return new Position(line, landing);
    }

    /** One character on or back, a surrogate pair being one. */
    private static int step(String text, int offset, boolean forward)
    {
        if (forward)
            return offset >= 0 && offset < text.length()
                ? offset + Character.charCount(text.codePointAt(offset))
                : offset + 1;
        return offset > 0 && offset <= text.length()
            ? offset - Character.charCount(text.codePointBefore(offset))
            : offset - 1;
    }

    private static int indexOf(String text, int target, int from,
                               boolean forward)
    {
        if (forward) {
            for (int i = Math.max(0, from); i < text.length();
                 i = step(text, i, true))
                if (text.codePointAt(i) == target)
                    return i;
            return -1;
        }
        for (int i = Math.min(from, text.length() - 1); i >= 0;
             i = step(text, i, false))
            if (text.codePointAt(i) == target)
                return i;
        return -1;
    }

    /** {@code findCharInLine x} -- forward to the next x on this line. */
    public static void findCharInLine(String parameters)
    {
        jumpToChar(parameters, true, false);
    }

    /** {@code findCharInLineBackward x} -- backward to the previous x. */
    public static void findCharInLineBackward(String parameters)
    {
        jumpToChar(parameters, false, false);
    }

    /** {@code tillCharInLine x} -- forward to just before the next x. */
    public static void tillCharInLine(String parameters)
    {
        jumpToChar(parameters, true, true);
    }

    /** {@code tillCharInLineBackward x} -- backward to just after the previous x. */
    public static void tillCharInLineBackward(String parameters)
    {
        jumpToChar(parameters, false, true);
    }

    private static void jumpToChar(String parameters, boolean forward,
                                   boolean till)
    {
        final Editor editor = Editor.currentEditor();
        if (parameters == null || parameters.isEmpty()) {
            editor.status("a character to find is required");
            return;
        }
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        // The first character of the argument, so that a trailing space in a
        // key map definition does not become the thing being looked for.
        final Position to = findCharacter(dot,
            new CharSearch(parameters.codePointAt(0), forward, till), 1, false);
        if (to == null) {
            editor.status("not found on this line");
            return;
        }
        editor.addUndo(SimpleEdit.MOVE);
        editor.unmark();
        editor.setDot(to);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    // ------------------------------------------------- unmatched brackets

    /**
     * {@code findUnmatchedBracket (} -- back to the '(' still open at the
     * caret, or with ')' on to the one that closes it; '[', ']', '{' and '}'
     * likewise, as vim's [( and ]).
     */
    public static void findUnmatchedBracket(String parameters)
    {
        final Editor editor = Editor.currentEditor();
        final String bracket = parameters == null ? "" : parameters.trim();
        if (bracket.length() != 1 || "([{}])".indexOf(bracket.charAt(0)) < 0) {
            editor.status("a bracket is required");
            return;
        }
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Position to =
            editor.findUnmatched(dot, bracket.charAt(0), false);
        if (to == null) {
            editor.status("No match");
            return;
        }
        editor.addUndo(SimpleEdit.MOVE);
        editor.unmark();
        editor.setDot(to);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    // --------------------------------------------- top, middle, bottom

    /** The line at the top, middle or bottom of what the window shows. */
    public static Line screenLine(Editor editor, String where, int count)
    {
        final Display display = editor.getDisplay();
        final Line top = display.getTopLine();
        if (top == null)
            return null;
        final int rows = Math.max(1, display.getRows());

        // How many lines of buffer the window actually shows, which is fewer
        // than its rows at the end of a short file.
        int visible = 0;
        Line line = top;
        while (line != null && visible < rows) {
            ++visible;
            line = line.nextVisible();
        }

        final int index;
        if (where.equals("middle"))
            index = (visible - 1) / 2;
        else if (where.equals("bottom"))
            index = Math.max(0, visible - count);
        else
            index = Math.min(count - 1, visible - 1);

        line = top;
        for (int i = 0; i < index && line.nextVisible() != null; i++)
            line = line.nextVisible();
        return line;
    }

    /** Moves the caret to the top line on screen, as vim's H does. */
    public static void moveToWindowTop()
    {
        toScreenLine("top");
    }

    /** Moves the caret to the middle line on screen, as vim's M does. */
    public static void moveToWindowMiddle()
    {
        toScreenLine("middle");
    }

    /** Moves the caret to the bottom line on screen, as vim's L does. */
    public static void moveToWindowBottom()
    {
        toScreenLine("bottom");
    }

    private static void toScreenLine(String where)
    {
        final Editor editor = Editor.currentEditor();
        final Line line = screenLine(editor, where, 1);
        if (line == null)
            return;
        editor.addUndo(SimpleEdit.MOVE);
        editor.unmark();
        editor.setDot(line, firstNonBlank(line));
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    /**
     * The offset of the first character on the line that is not a blank: a
     * space or a tab, as vim counts them, so a form feed is not one.
     */
    public static int firstNonBlank(Line line)
    {
        final String text = line.getText();
        if (text == null)
            return 0;
        int i = 0;
        while (i < text.length() && isBlank(text.charAt(i)))
            ++i;
        return i == text.length() ? Math.max(0, i - 1) : i;
    }

    private static boolean isBlank(char c)
    {
        return c == ' ' || c == '\t';
    }

    // ------------------------------------------- overwrite a character

    /**
     * {@code replaceChar x} -- put x where the caret is, without inserting.
     *
     * Does nothing at the end of a line: there is no character there to
     * replace, and vim will not lengthen a line to do it.
     */
    public static void replaceChar(String parameters)
    {
        final Editor editor = Editor.currentEditor();
        if (parameters == null || parameters.isEmpty()) {
            editor.status("a replacement character is required");
            return;
        }
        if (!editor.checkReadOnly())
            return;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        replaceChars(editor, dot.getLine(), dot.getOffset(),
                     parameters.codePointAt(0), 1);
    }

    /**
     * Overwrites {@code count} characters with the same one.
     *
     * All of them or none: vim refuses rather than doing part of it when the
     * line is too short. Shared with vim's {@code r}, which takes a count.
     *
     * @return false when there are not that many characters left on the line
     */
    public static boolean replaceChars(Editor editor, Line line, int offset,
                                       int replacement, int count)
    {
        // Whole characters: a surrogate pair is one, as it is on screen.
        final String was = line.getText();
        int end = offset;
        for (int i = 0; i < count; i++) {
            if (end >= line.length())
                return false;
            end += Character.charCount(was.codePointAt(end));
        }
        final StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            text.appendCodePoint(replacement);

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.addUndo(SimpleEdit.MOVE);
            editor.deleteRegion(new Position(line, offset),
                                new Position(line, end));
            editor.insertString(text.toString());
            // The caret ends on the last character replaced, as vim leaves it.
            final Position now = editor.getDot();
            if (now != null) {
                editor.setDot(now.getLine(),
                              Math.max(0, now.getOffset()
                                          - Character.charCount(replacement)));
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        return true;
    }
}
