/*
 * Block.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.util.Utilities;

/**
 * A block of text: the same screen columns on each of a run of lines -- or,
 * ragged, from a column to the end of each line -- as a column selection
 * takes and vim's CTRL-V does.
 *
 * <p>Columns are screen columns, so a tab counts for the columns it covers. A
 * tab the edge of the block cuts through is split into spaces, those inside
 * the block going with it; every other tab on the line stays a tab. A line
 * that ends before the block starts has no part in it.
 */
public final class Block {
    private final Buffer buffer;
    private final Line first;
    private final Line last;
    private final int startCol;
    /** Exclusive; ignored when the block runs to the end of each line. */
    private final int endCol;
    private final boolean toEol;

    public Block(
        Buffer buffer,
        Line first,
        Line last,
        int startCol,
        int endCol,
        boolean toEol
    ) {
        this.buffer = buffer;
        this.first = first;
        this.last = last;
        this.startCol = startCol;
        this.endCol = endCol;
        this.toEol = toEol;
    }

    /**
     * The block between two corners, each taking in the character it is on,
     * as a selection from one to the other does; to the end of each line
     * when {@code toEol}.
     */
    public static Block between(
        Buffer buffer,
        Position a,
        Position b,
        boolean toEol
    ) {
        if (buffer.needsRenumbering())
            buffer.renumber();
        final boolean aFirst = a.lineNumber() <= b.lineNumber();
        final int colA = buffer.getCol(a);
        final int colB = buffer.getCol(b);
        return new Block(
            buffer,
            (aFirst ? a : b).getLine(),
            (aFirst ? b : a).getLine(),
            Math.min(colA, colB),
            Math.max(
                colA + width(buffer, a, colA),
                colB + width(buffer, b, colB)
            ),
            toEol
        );
    }

    /** Columns the character at a position covers: one past a line's end. */
    private static int width(Buffer buffer, Position pos, int col) {
        final String text = pos.getLine().getText();
        final int offset = pos.getOffset();
        if (text == null || offset >= text.length())
            return 1;
        return widthAt(text, offset, col, buffer.getTabWidth());
    }

    public Line getFirstLine() {
        return first;
    }

    public Line getLastLine() {
        return last;
    }

    public int getStartCol() {
        return startCol;
    }

    public boolean isToEol() {
        return toEol;
    }

    /** The same columns on the lines after the first, or null for none. */
    public Block below() {
        if (first == last || first.next() == null)
            return null;
        return new Block(buffer, first.next(), last, startCol, endCol, toEol);
    }

    /** The lines, first to last. */
    public List<Line> lines() {
        final List<Line> lines = new ArrayList<Line>();
        for (Line line = first; line != null; line = line.next()) {
            lines.add(line);
            if (line == last)
                break;
        }
        return lines;
    }

    /** The text inside the block on each line, first to last. */
    public List<String> getLines() {
        final List<String> texts = new ArrayList<String>();
        for (Line line : lines())
            texts.add(span(line).inside());
        return texts;
    }

    /** The lines' texts joined by newlines, as a column copy keeps them. */
    public String getText() {
        return String.join("\n", getLines());
    }

    /** The offsets the block covers on a line, as [start, end), or null. */
    public int[] getOffsets(Line line) {
        final Span s = span(line);
        return s.isShort ? null : new int[] { s.keepTo, s.keepFrom };
    }

    /** Deletes the block, as one undo step, the caret at its top left. */
    public void delete(Editor editor) {
        edit(
            editor,
            s -> s.before() + spaces(s.padBefore + s.padAfter)
                + s.after()
        );
    }

    /**
     * Changes the text inside the block on each line, as {@code r} and case
     * changes do: the text passed in is the text inside, tabs cut through
     * already spaces.
     */
    public void transform(Editor editor, UnaryOperator<String> change) {
        edit(
            editor,
            s -> s.isShort
                ? s.text
                : s.before() + spaces(s.padBefore)
                    + change.apply(s.inside()) + spaces(s.padAfter)
                    + s.after()
        );
    }

    /**
     * Inserts the same text at the block's left edge on each line -- vim's
     * {@code I} -- or with {@code append} at its right edge, or at the end
     * of each line when the block runs to it -- vim's {@code A}. A line
     * shorter than that column is skipped for an insert, and padded with
     * spaces for an append.
     */
    public void insertOnEachLine(Editor editor, String text, boolean append) {
        final int col = append ? endCol : startCol;
        edit(editor, s -> {
            if (append && toEol)
                return s.text + text;
            final Span at = new Block(buffer, first, last, col, col, false)
                .span(s.line);
            if (at.isShort)
                return append ? s.text + spaces(at.shortBy) + text : s.text;
            return at.before() + spaces(at.padBefore) + text
                + spaces(at.padAfter) + at.after();
        });
    }

    /**
     * Where vim's A inserts on a line: at the block's right edge, the line
     * padded out to it with spaces first if it is shorter.
     */
    public Position appendPoint(Editor editor, Line line) {
        final Span at = new Block(buffer, line, line, endCol, endCol, false)
            .span(line);
        if (!at.isShort)
            return positionAt(buffer, line, endCol);
        editor.setDot(line, line.length());
        editor.moveCaretToDotCol();
        editor.insertString(spaces(at.shortBy));
        return new Position(editor.getDot());
    }

    /**
     * Narrows the blanks at the block's left edge on each line by up to so
     * many columns, as a shift left of a block does. A tab among them counts
     * for its columns, so it may be what goes; what stays of the run is
     * spaces.
     */
    public void shiftLeft(Editor editor, int cols) {
        shift(editor, -cols);
    }

    /**
     * Widens the blanks at the block's left edge on each line by so many
     * columns, as a shift right of a block does: the blanks already there
     * and the new ones become one run of spaces, so the text after them
     * moves over even past a tab. A line with no text from the left edge
     * on is left alone.
     */
    public void shiftRight(Editor editor, int cols) {
        shift(editor, cols);
    }

    /** The run of blanks from the left edge, so many columns wider. */
    private void shift(Editor editor, int delta) {
        final int tabWidth = buffer.getTabWidth();
        edit(editor, s -> {
            final Span at = new Block(
                buffer,
                first,
                last,
                startCol,
                startCol,
                false
            ).span(s.line);
            final String after = spaces(at.padAfter) + at.after();
            if (at.isShort || after.isEmpty())
                return s.text;
            int col = startCol;
            int i = 0;
            while (i < after.length()) {
                final char c = after.charAt(i);
                if (c != ' ' && c != '\t')
                    break;
                col += widthAt(after, i, col, tabWidth);
                i++;
            }
            final int width = Math.max(0, col - startCol + delta);
            return at.before() + spaces(at.padBefore) + spaces(width)
                + after.substring(i);
        });
    }

    /**
     * Puts lines of text as a block with its top left at a column of a line:
     * each piece on the next line down, lines added at the end of the buffer
     * as needed, short lines padded out to the column with spaces, and a
     * piece shorter than the widest padded out too when there is text after
     * it, so what follows stays in line. One undo step; the caret ends at the
     * top left.
     */
    public static void put(
        Editor editor,
        Line line,
        int col,
        List<String> pieces
    ) {
        editor.getBuffer()
            .withWriteLock(
                () -> putLocked(
                    editor,
                    line,
                    col,
                    pieces
                )
            );
    }

    private static void putLocked(
        Editor editor,
        Line line,
        int col,
        List<String> pieces
    ) {
        final Buffer buffer = editor.getBuffer();
        int width = 0;
        for (String piece : pieces)
            width = Math.max(width, piece.length());
        final CompoundEdit edit = buffer.beginCompoundEdit();
        try {
            editor.getDot().moveTo(positionAt(buffer, line, col));
            editor.addUndo(SimpleEdit.MOVE);
            Line target = line;
            for (int i = 0; i < pieces.size(); i++) {
                if (target == null) {
                    // Past the end of the buffer: a new line for the piece.
                    final Line end = lastLine(buffer);
                    editor.getDot().moveTo(end, end.length());
                    editor.addUndo(SimpleEdit.INSERT_LINE_SEP);
                    buffer.insertLineSeparator(editor.getDot());
                    target = end.next();
                }
                final Span at = new Block(
                    buffer,
                    target,
                    target,
                    col,
                    col,
                    false
                ).span(target);
                final String piece = pieces.get(i);
                final String text;
                if (at.isShort) {
                    text = at.text + spaces(at.shortBy) + piece;
                } else {
                    final String after = spaces(at.padAfter) + at.after();
                    text = at.before() + spaces(at.padBefore) + piece
                        + (after.isEmpty()
                            ? ""
                            : spaces(width - piece.length()))
                        + after;
                }
                setLine(editor, target, text);
                target = target.next();
            }
            editor.getDot().moveTo(positionAt(buffer, line, col));
            editor.moveCaretToDotCol();
        }
        finally {
            buffer.endCompoundEdit(edit);
        }
        buffer.modified();
    }

    /** Every line, new text from its span, as one undo step. */
    private void edit(Editor editor, Function<Span, String> change) {
        buffer.withWriteLock(() -> editLocked(editor, change));
    }

    private void editLocked(Editor editor, Function<Span, String> change) {
        final CompoundEdit edit = buffer.beginCompoundEdit();
        try {
            // Undo puts the caret at the top left, as vim's does.
            editor.getDot().moveTo(positionAt(buffer, first, startCol));
            editor.addUndo(SimpleEdit.MOVE);
            for (Line line : lines()) {
                final Span s = span(line);
                final String text = change.apply(s);
                if (!text.equals(s.text))
                    setLine(editor, line, text);
            }
            editor.getDot().moveTo(positionAt(buffer, first, startCol));
            editor.moveCaretToDotCol();
        }
        finally {
            buffer.endCompoundEdit(edit);
        }
        buffer.modified();
    }

    /** A line's new text, recorded for undo the way j records a line edit. */
    private static void setLine(Editor editor, Line line, String text) {
        editor.getDot().moveTo(line, 0);
        editor.addUndo(SimpleEdit.LINE_EDIT);
        line.setText(text);
        editor.updateInAllEditors(line);
    }

    /** The position at a screen column of a line, or its end. */
    public static Position positionAt(Buffer buffer, Line line, int col) {
        final Position pos = new Position(line, 0);
        pos.moveOntoCol(col, buffer.getTabWidth());
        return pos;
    }

    private static Line lastLine(Buffer buffer) {
        Line line = buffer.getFirstLine();
        while (line.next() != null)
            line = line.next();
        return line;
    }

    private static int widthAt(String text, int i, int col, int tabWidth) {
        return text.charAt(i) == '\t' ? tabWidth - col % tabWidth : 1;
    }

    private static String spaces(int n) {
        return n <= 0 ? "" : Utilities.spaces(n);
    }

    /** Where the block falls on one line. */
    private Span span(Line line) {
        final Span s = new Span(line);
        final String t = s.text;
        final int n = t.length();
        final int tabWidth = buffer.getTabWidth();
        int i = 0;
        int col = 0;
        while (i < n) {
            final int w = widthAt(t, i, col, tabWidth);
            if (col + w > startCol)
                break;
            col += w;
            i += Character.charCount(t.codePointAt(i));
        }
        s.keepTo = i;
        if (i >= n) {
            // A line that ends right at the left edge is not short: I
            // inserts at its end.
            s.isShort = col < startCol;
            s.shortBy = startCol - col;
            s.from = s.to = s.keepFrom = n;
            return s;
        }
        if (col < startCol) {
            // A tab the left edge cuts through.
            final int w = widthAt(t, i, col, tabWidth);
            s.padBefore = startCol - col;
            i++;
            if (!toEol && col + w >= endCol) {
                // All of the block is inside the one tab.
                s.padIn = Math.max(0, endCol - startCol);
                s.padAfter = col + w - Math.max(endCol, startCol);
                s.from = s.to = s.keepFrom = i;
                return s;
            }
            s.padIn = col + w - startCol;
            col += w;
        }
        s.from = i;
        if (toEol) {
            s.to = s.keepFrom = n;
            return s;
        }
        while (i < n) {
            final int w = widthAt(t, i, col, tabWidth);
            if (col + w > endCol)
                break;
            col += w;
            i += Character.charCount(t.codePointAt(i));
        }
        s.to = i;
        s.keepFrom = i;
        if (i < n && col < endCol) {
            // A tab the right edge cuts through.
            final int w = widthAt(t, i, col, tabWidth);
            s.padInEnd = endCol - col;
            s.padAfter = col + w - endCol;
            s.keepFrom = i + 1;
        }
        return s;
    }

    /** One line's part: kept before, inside, kept after. */
    private static final class Span {
        final Line line;
        final String text;
        /** text[0:keepTo] stays before the block. */
        int keepTo;
        /** Spaces for the part of a cut tab before the block and after it. */
        int padBefore;
        int padAfter;
        /** Spaces for the parts of cut tabs inside the block. */
        int padIn;
        int padInEnd;
        /** text[from:to] is wholly inside the block. */
        int from;
        int to;
        /** text[keepFrom:] stays after the block. */
        int keepFrom;
        /** The line ends before the block starts, by this many columns. */
        boolean isShort;
        int shortBy;

        Span(Line line) {
            this.line = line;
            text = line.getText() == null ? "" : line.getText();
        }

        String before() {
            return text.substring(0, keepTo);
        }

        String after() {
            return text.substring(keepFrom);
        }

        String inside() {
            return isShort
                ? ""
                : spaces(padIn) + text.substring(from, to) + spaces(padInEnd);
        }
    }
}
