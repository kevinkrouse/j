/*
 * NumberCommands.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.Locale;
import javax.swing.undo.CompoundEdit;

/**
 * Adding to the number at or after the caret, as vim's CTRL-A and CTRL-X do
 * with nvim's default 'nrformats' of bin,hex: decimal with an optional
 * leading minus, 0x hexadecimal and 0b binary.
 *
 * The arithmetic is vim's: an unsigned 64-bit value, with a decimal
 * number's sign kept apart, so that hexadecimal and binary wrap around and
 * decimal changes sign; one too big for 64 bits is read as the largest
 * and left there. The digits keep their width -- 0x0f, 007 -- and a
 * hexadecimal number keeps the case of its last letter.
 */
public final class NumberCommands {
    private NumberCommands() {}

    /** What an addition does to a line: text to put in place of a span. */
    public static final class Change {
        /** The span replaced, from the sign if there is one. */
        public final int start;
        public final int end;
        public final String text;

        Change(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        /** The offset of the new number's last character. */
        public int last() {
            return start + text.length() - 1;
        }
    }

    /**
     * Works out adding to a number on a line, without changing it.
     *
     * @param from      the caret, or the start of a selection
     * @param limit     the end of a selection, exclusive, or -1 for none
     * @param amount    how much, unsigned
     * @param subtract  CTRL-X rather than CTRL-A
     * @return null when there is no number there
     */
    public static Change plan(
        String text,
        int from,
        int limit,
        long amount,
        boolean subtract
    ) {
        final boolean selection = limit >= 0;
        final int end = selection
            ? Math.min(limit, text.length())
            : text.length();
        final int col = selection
            ? firstDigit(text, from, end)
            : findNumber(text, from);
        if (col < 0 || col >= end)
            return null;

        // The prefix, the digits and the value, vim's str2nr. A prefix
        // counts only with a digit after it inside the limit, as there.
        int digits = col;
        int radix = 10;
        if (text.charAt(col) == '0' && col + 2 < end) {
            final char p = text.charAt(col + 1);
            final char d = text.charAt(col + 2);
            if ((p == 'x' || p == 'X') && isHex(d))
                radix = 16;
            else if ((p == 'b' || p == 'B') && isBinary(d))
                radix = 2;
            if (radix != 10)
                digits = col + 2;
        }
        int stop = digits;
        long n = 0;
        boolean overflow = false;
        for (; stop < end; stop++) {
            final int d = digit(text.charAt(stop), radix);
            if (d < 0)
                break;
            if (
                Long.compareUnsigned(
                    n,
                    Long.divideUnsigned(-1L - d, radix)
                ) > 0
            )
                overflow = true;
            n = overflow ? -1L : n * radix + d;
        }

        // Only a decimal number has a sign, and in a selection only one
        // the selection takes in.
        boolean negative = radix == 10
            && col > (selection ? from : 0)
            && text.charAt(col - 1) == '-';
        final int start = negative ? col - 1 : col;

        final boolean down = subtract ^ negative;
        final long was = n;
        if (!overflow)
            n = down ? n - amount : n + amount;
        if (radix == 10) {
            if (down && Long.compareUnsigned(n, was) > 0) {
                n = 1 + ~n;
                negative = !negative;
            } else if (!down && Long.compareUnsigned(n, was) < 0) {
                n = ~n;
                negative = !negative;
            }
            if (n == 0)
                negative = false;
        }

        String number;
        if (radix == 10) {
            number = Long.toUnsignedString(n);
        } else if (radix == 2) {
            number = Long.toBinaryString(n);
        } else {
            number = Long.toHexString(n);
            if (lastLetterIsUpper(text, start, stop))
                number = number.toUpperCase(Locale.ROOT);
        }
        // The width stays, where the number starts with a zero.
        if (text.charAt(col) == '0')
            number = zeros(stop - digits - number.length()) + number;
        final StringBuilder sb = new StringBuilder();
        if (negative)
            sb.append('-');
        sb.append(text, col, digits).append(number);
        return new Change(start, stop, sb.toString());
    }

    /**
     * Where the number the caret is on, or the next one, starts: a 0x or
     * 0b prefix the caret is inside or after first, then the first digit
     * from the caret, and back to the start of its run. -1 when none.
     */
    private static int findNumber(String text, int cursor) {
        final int length = text.length();
        int col = cursor;
        while (col > 0 && col < length && isHex(text.charAt(col)))
            --col;
        if (!isPrefix(text, col, 'x')) {
            col = cursor;
            while (col > 0 && col < length && isDecimal(text.charAt(col)))
                --col;
        }
        if (isPrefix(text, col, 'x') || isPrefix(text, col, 'b'))
            return col - 1;
        col = cursor;
        while (col < length && !isDecimal(text.charAt(col)))
            ++col;
        while (col > 0 && isDecimal(text.charAt(col - 1)))
            --col;
        return col < length ? col : -1;
    }

    private static int firstDigit(String text, int from, int end) {
        for (int col = from; col < end; col++)
            if (isDecimal(text.charAt(col)))
                return col;
        return -1;
    }

    /** True when the x or b at col follows a 0 and comes before a digit. */
    private static boolean isPrefix(String text, int col, char letter) {
        if (
            col <= 0
                || col + 1 >= text.length()
                || Character.toLowerCase(text.charAt(col)) != letter
                || text.charAt(col - 1) != '0'
        )
            return false;
        final char next = text.charAt(col + 1);
        return letter == 'x' ? isHex(next) : isBinary(next);
    }

    private static boolean lastLetterIsUpper(String text, int start, int end) {
        for (int i = end - 1; i >= start; i--)
            if (Character.isLetter(text.charAt(i)))
                return Character.isUpperCase(text.charAt(i));
        return false;
    }

    private static String zeros(int count) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++)
            sb.append('0');
        return sb.toString();
    }

    /** An ASCII digit's value in the radix, or -1. */
    private static int digit(char c, int radix) {
        final boolean ascii = radix == 16
            ? isHex(c)
            : radix == 2 ? isBinary(c) : isDecimal(c);
        return ascii ? Character.digit(c, radix) : -1;
    }

    private static boolean isDecimal(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDecimal(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isBinary(char c) {
        return c == '0' || c == '1';
    }

    /**
     * Adds to the number at or after an offset on a line, leaving the caret
     * on its last character, as one undo step that gives the caret back
     * where it was.
     *
     * @return the change made, or null when there was no number
     */
    public static Change add(
        Editor editor,
        Line line,
        int from,
        int limit,
        long amount,
        boolean subtract
    ) {
        final String text = line.getText() == null ? "" : line.getText();
        final Change change = plan(text, from, limit, amount, subtract);
        if (change == null)
            return null;
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            editor.addUndo(SimpleEdit.MOVE);
            editor.deleteRegion(
                new Position(line, change.start),
                new Position(line, change.end)
            );
            editor.insertString(change.text);
            editor.setDot(line, change.last());
            editor.moveCaretToDotCol();
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        return change;
    }

    /** What adding over lines changed: its first and last changes. */
    public static final class Changes {
        public final Line firstLine;
        public final Change first;
        public final Line lastLine;
        public final Change last;

        Changes(Line firstLine, Change first, Line lastLine, Change last) {
            this.firstLine = firstLine;
            this.first = first;
            this.lastLine = lastLine;
            this.last = last;
        }
    }

    /**
     * Adds to the first number of each line from start to end -- on the first
     * line from start's offset, on the last up to end's -- as one undo step,
     * as vim's CTRL-A does over a selection, leaving the caret at start.
     * Progressive, each number found gets amount more than the one before,
     * as vim's g CTRL-A.
     *
     * @return what changed, or null when there was no number
     */
    public static Changes addOverLines(
        Editor editor,
        Position start,
        Position end,
        long amount,
        boolean subtract,
        boolean progressive
    ) {
        Line firstLine = null;
        Change first = null;
        Line lastLine = null;
        Change last = null;
        long add = amount;
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            for (Line line = start.getLine(); line != null; line = line.next()) {
                final boolean isLast = line == end.getLine();
                final int from = line == start.getLine() ? start.getOffset() : 0;
                final int to = isLast ? end.getOffset() : line.length();
                final Change change = add(editor, line, from, to, add, subtract);
                if (change != null) {
                    if (first == null) {
                        firstLine = line;
                        first = change;
                    }
                    lastLine = line;
                    last = change;
                    if (progressive)
                        add += amount;
                }
                if (isLast)
                    break;
            }
            // Recorded, so that undo puts the caret back where the last edit
            // left it before taking that edit back: j's undo reads the caret.
            editor.addUndo(SimpleEdit.MOVE);
            editor.setDot(start.getLine(), start.getOffset());
            editor.moveCaretToDotCol();
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        return first == null
            ? null
            : new Changes(firstLine, first, lastLine, last);
    }

    public static void incrementNumber() {
        addHere(null, false);
    }

    /**
     * {@code incrementNumber [n] [progressive]} -- add n, or 1, to the number
     * at or after the caret; with a selection, to the first number of each
     * of its lines, and progressive, n more to each after the first.
     */
    public static void incrementNumber(String parameters) {
        addHere(parameters, false);
    }

    public static void decrementNumber() {
        addHere(null, true);
    }

    /** {@code decrementNumber [n] [progressive]} -- the same, taking away. */
    public static void decrementNumber(String parameters) {
        addHere(parameters, true);
    }

    private static void addHere(String parameters, boolean subtract) {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        long amount = 1;
        boolean progressive = false;
        if (parameters != null) {
            for (String word : parameters.trim().split("\\s+")) {
                if (word.isEmpty())
                    continue;
                if (word.equals("progressive")) {
                    progressive = true;
                    continue;
                }
                try {
                    amount = Long.parseLong(word);
                }
                catch (NumberFormatException e) {
                    editor.status("not a number: " + word);
                    return;
                }
            }
        }
        // Less than nothing is the other way.
        final boolean down = subtract ^ amount < 0;
        if (editor.getMark() != null) {
            final Region region = new Region(editor);
            editor.unmark();
            final Changes changes = addOverLines(
                editor,
                region.getBegin(),
                region.getEnd(),
                Math.abs(amount),
                down,
                progressive
            );
            if (changes == null)
                editor.status("no number in the selection");
            return;
        }
        final Change change = add(
            editor,
            dot.getLine(),
            dot.getOffset(),
            -1,
            Math.abs(amount),
            down
        );
        if (change == null)
            editor.status("no number at or after the caret");
    }
}
