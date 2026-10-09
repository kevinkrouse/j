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

import static org.armedbear.j.Constants.*;

import javax.swing.undo.CompoundEdit;

/**
 * Moving the caret about, changing the one character under it, and matching
 * brackets.
 *
 * Three things every vi-like editor has and j did not: jumping to a named
 * character on the line, jumping to the top, middle or bottom of the window,
 * and overwriting one character. They are here rather than in the modal layer
 * because none of them is modal, and vim's {@code f}, {@code t}, {@code H},
 * {@code M}, {@code L} and {@code r} call in rather than carrying their own.
 */
public final class CaretCommands {
    private CaretCommands() {}

    // ------------------------------------------------- find a character

    /** Where {@code f}, {@code F}, {@code t} and {@code T} land. */
    public static final class CharSearch {
        /** A code point, so that an emoji can be found as one character. */
        public final int target;
        public final boolean forward;
        /** True to stop one short of the character rather than on it. */
        public final boolean till;

        public CharSearch(int target, boolean forward, boolean till) {
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
    public static Position findCharacter(
        Position from,
        CharSearch search,
        int count,
        boolean repeat
    ) {
        final Line line = from.getLine();
        final String text = line.getText();
        if (text == null)
            return null;

        int found = from.getOffset();
        if (search.till && repeat)
            found = step(text, found, search.forward);

        for (int i = 0; i < count; i++) {
            found = indexOf(
                text,
                search.target,
                step(text, found, search.forward),
                search.forward
            );
            if (found < 0)
                return null;
        }
        final int landing = search.till
            ? step(text, found, !search.forward)
            : found;
        if (landing < 0 || landing >= text.length())
            return null;
        return new Position(line, landing);
    }

    /** One character on or back, a surrogate pair being one. */
    private static int step(String text, int offset, boolean forward) {
        if (forward)
            return offset >= 0 && offset < text.length()
                ? offset + Character.charCount(text.codePointAt(offset))
                : offset + 1;
        return offset > 0 && offset <= text.length()
            ? offset - Character.charCount(text.codePointBefore(offset))
            : offset - 1;
    }

    private static int indexOf(
        String text,
        int target,
        int from,
        boolean forward
    ) {
        if (forward) {
            for (int i = Math.max(0, from);
                i < text.length();
                i = step(text, i, true))
                if (text.codePointAt(i) == target)
                    return i;
            return -1;
        }
        for (int i = Math.min(from, text.length() - 1);
            i >= 0;
            i = step(text, i, false))
            if (text.codePointAt(i) == target)
                return i;
        return -1;
    }

    /** {@code findCharInLine x} -- forward to the next x on this line. */
    public static void findCharInLine(String parameters) {
        jumpToChar(parameters, true, false);
    }

    /** {@code findCharInLineBackward x} -- backward to the previous x. */
    public static void findCharInLineBackward(String parameters) {
        jumpToChar(parameters, false, false);
    }

    /** {@code tillCharInLine x} -- forward to just before the next x. */
    public static void tillCharInLine(String parameters) {
        jumpToChar(parameters, true, true);
    }

    /** {@code tillCharInLineBackward x} -- backward to just after the previous x. */
    public static void tillCharInLineBackward(String parameters) {
        jumpToChar(parameters, false, true);
    }

    private static void jumpToChar(
        String parameters,
        boolean forward,
        boolean till
    ) {
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
        final Position to = findCharacter(
            dot,
            new CharSearch(parameters.codePointAt(0), forward, till),
            1,
            false
        );
        if (to == null) {
            editor.status("not found on this line");
            return;
        }
        editor.beginMotion();
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
    public static void findUnmatchedBracket(String parameters) {
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
            findUnmatched(editor, dot, bracket.charAt(0), false);
        if (to == null) {
            editor.status("No match");
            return;
        }
        editor.beginMotion();
        editor.setDot(to);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    // --------------------------------------------- top, middle, bottom

    /** The line at the top, middle or bottom of what the window shows. */
    public static Line screenLine(Editor editor, String where, int count) {
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
    public static void moveToWindowTop() {
        toScreenLine("top");
    }

    /** Moves the caret to the middle line on screen, as vim's M does. */
    public static void moveToWindowMiddle() {
        toScreenLine("middle");
    }

    /** Moves the caret to the bottom line on screen, as vim's L does. */
    public static void moveToWindowBottom() {
        toScreenLine("bottom");
    }

    private static void toScreenLine(String where) {
        final Editor editor = Editor.currentEditor();
        final Line line = screenLine(editor, where, 1);
        if (line == null)
            return;
        editor.beginMotion();
        editor.setDot(line, firstNonBlank(line));
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    /**
     * The offset of the first character on the line that is not a blank: a
     * space or a tab, as vim counts them, so a form feed is not one.
     */
    public static int firstNonBlank(Line line) {
        final String text = line.getText();
        if (text == null)
            return 0;
        int i = 0;
        while (i < text.length() && isBlank(text.charAt(i)))
            ++i;
        return i == text.length() ? Math.max(0, i - 1) : i;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    // ------------------------------------------- overwrite a character

    /**
     * {@code replaceChar x} -- put x where the caret is, without inserting.
     *
     * Does nothing at the end of a line: there is no character there to
     * replace, and vim will not lengthen a line to do it.
     */
    public static void replaceChar(String parameters) {
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
        replaceChars(
            editor,
            dot.getLine(),
            dot.getOffset(),
            parameters.codePointAt(0),
            1
        );
    }

    /**
     * Overwrites {@code count} characters with the same one.
     *
     * All of them or none: vim refuses rather than doing part of it when the
     * line is too short. Shared with vim's {@code r}, which takes a count.
     *
     * @return false when there are not that many characters left on the line
     */
    public static boolean replaceChars(
        Editor editor,
        Line line,
        int offset,
        int replacement,
        int count
    ) {
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
            editor.deleteRegion(
                new Position(line, offset),
                new Position(line, end)
            );
            editor.insertString(text.toString());
            // The caret ends on the last character replaced, as vim leaves it.
            final Position now = editor.getDot();
            if (now != null) {
                editor.setDot(
                    now.getLine(),
                    Math.max(
                        0,
                        now.getOffset()
                            - Character.charCount(replacement)
                    )
                );
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        return true;
    }

    // ---------------------------------------------- matching brackets

    // If numLines is non-zero, limit the search to that many lines either
    // forward or backward in the buffer.
    public static Position findMatchInternal(Editor editor, Position start, int numLines) {
        return findMatchInternal(editor, start, numLines, false);
    }

    /**
     * With vim, match as vim's % does ('cpoptions' without %): the start may
     * be in a string or escaped, and a bracket is skipped when it is inside
     * "..." counted from the start, in a 'x' literal, or escaped differently
     * from the start.
     */
    public static Position findMatchInternal(Editor editor, Position start, int numLines, boolean vim) {
        if (start == null)
            return null;
        final String s1 = new String("{([})]");
        final char origChar = start.getChar();
        int index = s1.indexOf(origChar);
        if (index < 0)
            return null;
        final Mode mode = editor.getBuffer().getMode();
        final int offset = start.getOffset();
        if (!vim) {
            if (mode.isInComment(editor.getBuffer(), start) || mode.isInQuote(editor.getBuffer(), start))
                return null;
            if (offset > 0 && start.getLine().charAt(offset - 1) == '\\') {
                // It's escaped.
                return null;
            }
        }
        final String s2 = new String("})]{([");
        return scanForMatch(
            editor,
            start,
            origChar,
            s2.charAt(index),
            index > 2,
            numLines,
            vim,
            isEscaped(start.getLine(), offset)
        );
    }

    /**
     * The bracket still open at {@code start}, as vim's [( and ]) find it:
     * with '(', '[' or '{' the one before, with ')', ']' or '}' the one
     * after. The character at start does not count. With vim, brackets are
     * skipped as {@link #findMatchInternal(Editor, Position, int, boolean)} skips
     * them, but for an escaped start: vim does not look at the caret's.
     */
    public static Position findUnmatched(Editor editor, Position start, char bracket, boolean vim) {
        final int index = "{([})]".indexOf(bracket);
        if (index < 0)
            return null;
        // Back to a '(' counts the ')'s on the way, forward the '('s.
        return scanForMatch(
            editor,
            start,
            "})]{([".charAt(index),
            bracket,
            index < 3,
            0,
            vim,
            false
        );
    }

    /**
     * The other end of the string a quote at pos opens or closes, as the
     * mode's isInQuote sees strings, or null. If numLines is non-zero, looks
     * no further than that many lines away.
     */
    public static Position findMatchingQuote(Editor editor, Position pos, int numLines) {
        final char c = pos.getChar();
        if (c != '"' && c != '\'' && c != '`')
            return null;
        final Line line = pos.getLine();
        final int offset = pos.getOffset();
        if (isEscaped(line, offset))
            return null;
        // The apostrophe of a word.
        if (
            c == '\''
                && offset > 0
                && offset + 1 < line.length()
                && Character.isLetter(line.charAt(offset - 1))
                && Character.isLetter(line.charAt(offset + 1))
        )
            return null;
        final Mode mode = editor.getBuffer().getMode();
        if (mode.isInComment(editor.getBuffer(), pos))
            return null;
        final boolean closing = mode.isInQuote(editor.getBuffer(), pos);
        if (!closing && !mode.isInQuote(editor.getBuffer(), new Position(line, offset + 1)))
            return null;
        final Position p = new Position(pos);
        while (closing ? p.prev() : p.next()) {
            if (
                numLines != 0
                    &&
                    Math.abs(p.lineNumber() - pos.lineNumber()) > numLines
            )
                return null;
            if (
                p.getOffset() < p.getLine().length()
                    && p.getChar() == c
                    && !isEscaped(p.getLine(), p.getOffset())
            ) {
                // The first one there must be the string's other end.
                if (mode.isInQuote(editor.getBuffer(), p) != closing)
                    return p;
                return null;
            }
        }
        return null;
    }

    /** The first match after start not paired with an origChar on the way. */
    private static Position scanForMatch(
        Editor editor,
        Position start,
        char origChar,
        char match,
        boolean searchBackwards,
        int numLines,
        boolean vim,
        boolean escaped
    ) {
        final Mode mode = editor.getBuffer().getMode();
        int stopLineNumber = searchBackwards ? 0 : editor.getBuffer().getLineCount();
        if (numLines != 0)
            stopLineNumber = searchBackwards ? start.lineNumber() - numLines : start.lineNumber() + numLines;
        int count = 1;
        final SyntaxIterator it = mode.getSyntaxIterator(start);
        while (true) {
            char c;
            Position pos = it.getPosition();
            if (searchBackwards) {
                if (pos.lineNumber() < stopLineNumber)
                    return null;
                else
                    c = it.prevChar();
            } else {
                if (pos.lineNumber() > stopLineNumber)
                    return null;
                else
                    c = it.nextChar();
            }
            if (c == SyntaxIterator.DONE)
                return null;
            if (
                vim
                    && (c == origChar || c == match)
                    && isSkippedByVim(start, it.getPosition(), escaped)
            )
                continue;
            if (c == origChar)
                ++count;
            else if (c == match)
                --count;
            if (count == 0) {
                // Found it!
                return it.getPosition();
            }
        }
    }

    private static boolean isSkippedByVim(
        Position start,
        Position pos,
        boolean startEscaped
    ) {
        final Line line = pos.getLine();
        final int offset = pos.getOffset();
        if (isEscaped(line, offset) != startEscaped)
            return true;
        final String text = line.getText();
        if (text == null)
            return false;
        // 'x' and '\x'.
        if (
            offset + 1 < text.length()
                && text.charAt(offset + 1) == '\''
                && (offset >= 1 && text.charAt(offset - 1) == '\''
                    || offset >= 2
                        && text.charAt(offset - 2) == '\''
                        && text.charAt(offset - 1) == '\\')
        )
            return true;
        // Quotes say nothing on a line with an odd number of them. Counted
        // from the start on its own line, from the line's start on others.
        if (!hasEvenQuotes(text))
            return false;
        int from = 0;
        int to = offset;
        if (line == start.getLine()) {
            from = Math.min(start.getOffset(), offset) + 1;
            to = Math.max(start.getOffset(), offset);
        }
        boolean inQuote = false;
        for (int i = from; i < to; i++)
            if (isQuote(text, i) && !isEscaped(line, i))
                inQuote = !inQuote;
        return inQuote;
    }

    /** After an odd number of backslashes. */
    private static boolean isEscaped(Line line, int offset) {
        int i = offset;
        while (i > 0 && line.charAt(i - 1) == '\\')
            --i;
        return ((offset - i) & 1) != 0;
    }

    /** A double quote, but not the one in the literal '"'. */
    private static boolean isQuote(String text, int i) {
        return text.charAt(i) == '"'
            && (i == 0
                || text.charAt(i - 1) != '\''
                || i + 1 == text.length()
                || text.charAt(i + 1) != '\'');
    }

    /** Vim's count, which leaves out \" and '"'. */
    private static boolean hasEvenQuotes(String text) {
        int quotes = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isQuote(text, i))
                ++quotes;
            else if (text.charAt(i) == '\\' && i + 1 < text.length())
                ++i;
        }
        return (quotes & 1) == 0;
    }

    /**
     * Goes to the other end of the pair at the caret, as the mode's
     * {@link PairMatcher} pairs them: brackets, and in some modes #if and
     * #endif or start and end tags. Vim's % is the same search. The pair is
     * the delimiter at the caret, else a closing bracket just before a bar
     * caret, else the first delimiter after the caret on its line. A bar
     * caret lands after a closing bracket.
     */
    public static void findMatchingPair(Editor editor) {
        final InputHandler handler = editor.getInputHandler();
        final boolean bar = handler == null || handler.getCaretShape() == InputHandler.CaretShape.BAR;
        final PairMatcher matcher = editor.getMode().getPairMatcher();
        final Position dot = editor.getDotCopy();
        editor.setWaitCursor();
        Position match = null;
        if (bar && dot.getOffset() > 0 && "{([".indexOf(dot.getChar()) < 0) {
            final Position before = new Position(dot.getLine(), dot.getOffset() - 1);
            if ("})]".indexOf(before.getChar()) >= 0)
                match = matcher.findMatch(editor, before);
        }
        if (match == null)
            match = matcher.findMatch(editor, dot);
        if (match != null) {
            if (bar && "})]".indexOf(match.getChar()) >= 0)
                match.next();
            editor.beginMotion();
            editor.updateDotLine();
            editor.getDot().moveTo(match);
            editor.updateDotLine();
            editor.moveCaretToDotCol();
        } else
            editor.status("No match");
        editor.setDefaultCursor();
    }

    public static void selectSyntax(Editor editor) {
        editor.setWaitCursor();
        Position pos = findDelimiterNearDot(editor);
        if (pos != null) {
            Position match = findMatchInternal(editor, pos, 0);
            if (match != null) {
                if ("})]".indexOf(pos.getChar()) >= 0)
                    pos.next();
                else if ("})]".indexOf(match.getChar()) >= 0)
                    match.next();
                if (pos.getLine() != match.getLine()) {
                    // Extend selection to full lines if possible.
                    Region r = new Region(editor.getBuffer(), pos, match);
                    Position begin = r.getBegin();
                    String trim =
                        begin.getLine().substring(0, begin.getOffset()).trim();
                    if (trim.length() == 0) {
                        Position end = r.getEnd();
                        trim = end.getLine().substring(end.getOffset()).trim();
                        if (trim.length() == 0) {
                            // Extend selection to complete lines.
                            begin.setOffset(0);
                            if (end.getNextLine() != null)
                                end.moveTo(end.getNextLine(), 0);
                            else
                                end.setOffset(end.getLineLength());
                            if (pos.isBefore(match)) {
                                pos = begin;
                                match = end;
                            } else {
                                match = begin;
                                pos = end;
                            }
                        }
                    }
                }
                editor.beginMotion();
                editor.getDot().moveTo(pos);
                editor.setMarkAtDot();
                editor.updateDotLine();
                editor.getDot().moveTo(match);
                editor.updateDotLine();
                editor.moveCaretToDotCol();
                if (editor.getDot().getLine() != editor.getMark().getLine())
                    editor.setUpdateFlag(REPAINT);
            } else
                editor.status("No match");
        }
        editor.setDefaultCursor();
    }

    private static Position findDelimiterNearDot(Editor editor) {
        Position pos = editor.getDot().copy();
        if ("{([".indexOf(pos.getChar()) >= 0) {
            // The character to the right of the caret is a left delimiter.
            return pos;
        }
        Position saved = editor.getDot().copy();
        if (pos.getOffset() > 0) {
            pos.prev();
            if ("})]".indexOf(pos.getChar()) >= 0) {
                // The character to the left of the caret is a right delimiter.
                return pos;
            }
        }
        // There's no delimiter at the exact location of the caret.
        final String delimiters = "{([})]";
        pos.moveTo(saved);
        while (pos.getOffset() > 0) {
            // Look at previous char.
            pos.prev();
            char c = pos.getChar();
            if (delimiters.indexOf(c) >= 0)
                return pos;
            if (!Character.isWhitespace(c) && c != ';')
                break;
        }
        pos.moveTo(saved);
        final int limit = pos.getLineLength();
        while (pos.getOffset() < limit) {
            char c = pos.getChar();
            if (delimiters.indexOf(c) >= 0)
                return pos;
            if (!Character.isWhitespace(c))
                return null;
            // Look at next char.
            pos.next();
        }
        return null;
    }
}
