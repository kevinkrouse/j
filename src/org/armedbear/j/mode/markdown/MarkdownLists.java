/*
 * MarkdownLists.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.Buffer;
import org.armedbear.j.EditCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.IndentCommands;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.UndoLineEdit;

/**
 * Tab, Shift+Tab, Enter and Backspace in Markdown lists.
 *
 * <p>Tab and Shift+Tab shift a list item, the lines of a selection, or a
 * line with the caret in its indentation, by the indent size. Enter
 * continues a list, and Enter on an empty item takes its marker away.
 * Backspace after an empty item's marker leaves the caret under the text of
 * the item above; in a blank line's indentation it steps back to that text,
 * then that item's level, then the levels of the items it's under.
 */
public final class MarkdownLists {
    // Lines looked back over for the item above.
    private static final int LOOK_BACK = 500;

    private MarkdownLists() {}

    // A list item: its marker, where its text begins, and its box if it has one.
    record Item(int markerBegin, int markerEnd, int textBegin, boolean box) {
        boolean isEmpty(String text) {
            return text.substring(textBegin).isBlank();
        }
    }

    static Item item(Line line) {
        if (MarkdownFormatter.isCode(line) || MarkdownFormatter.isIndentedCodeBlock(line))
            return null;
        final String text = line.getText();
        // Quoted lists are left to the plain commands.
        if (MarkdownFormatter.QUOTE_PREFIX.matcher(text).lookingAt())
            return null;
        final Matcher m = MarkdownFormatter.LIST_ITEM.matcher(text);
        if (!m.lookingAt())
            return null;
        final Matcher box = MarkdownFormatter.TASK_BOX.matcher(text).region(m.end(), text.length());
        if (box.lookingAt()) {
            int end = box.end();
            while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t'))
                end++;
            return new Item(m.start(1), m.end(1), end, true);
        }
        return new Item(m.start(1), m.end(1), m.end(), false);
    }

    /** The column an item's text starts at: one past the marker if more than four spaces follow it. */
    static int textColumn(Line line, Item item, Buffer buffer) {
        final int marker = buffer.getCol(line, item.markerEnd());
        final int spaces = buffer.getCol(line, item.textBegin()) - marker;
        if (item.box())
            return buffer.getCol(line, item.textBegin());
        return marker + (spaces == 0 || spaces > 4 ? 1 : spaces);
    }

    /**
     * The stops Backspace steps back through on line, ascending: the levels
     * of the items it's under, then the item above's level and its text's
     * column; null if there's no item above.
     */
    static int[] stops(Line line, Buffer buffer) {
        Line above = line.previous();
        Item item = null;
        for (int n = 0; above != null && n < LOOK_BACK; above = above.previous(), n++) {
            if (above.isBlank())
                continue;
            item = item(above);
            if (item != null)
                break;
            // A paragraph at the margin ends the list.
            if (buffer.getIndentation(above) == 0
                    || MarkdownFormatter.isCode(above)
                    || MarkdownFormatter.isIndentedCodeBlock(above))
                return null;
        }
        if (item == null)
            return null;
        final int level = buffer.getIndentation(above);
        final int text = textColumn(above, item, buffer);
        List<Integer> stops = new ArrayList<>();
        int under = level;
        for (Line l = above.previous(); l != null && under > 0; l = l.previous()) {
            if (l.isBlank())
                continue;
            final int indent = buffer.getIndentation(l);
            if (item(l) != null) {
                if (indent < under) {
                    stops.add(0, indent);
                    under = indent;
                }
            } else if (indent == 0)
                break;
        }
        if (stops.isEmpty() || stops.get(0) != 0)
            stops.add(0, 0);
        stops.add(level);
        stops.add(text);
        return stops.stream().distinct().mapToInt(Integer::intValue).toArray();
    }

    /** The stop before col, or -1 at the margin. */
    static int previousStop(int[] stops, int col) {
        for (int i = stops.length - 1; i >= 0; i--)
            if (stops[i] < col)
                return stops[i];
        return -1;
    }

    // ------------------------------------------------------------ commands

    /** {@code markdownTab}. */
    public static void tab(Editor editor) {
        if (!editor.checkReadOnly() || editor.getDot() == null)
            return;
        parse(editor.getBuffer());
        if (isMultiLine(editor)) {
            shiftLines(editor, editor.getBuffer().getIndentSize());
            return;
        }
        final Buffer buffer = editor.getBuffer();
        final Line line = editor.getDotLine();
        final boolean inIndentation = editor.getDot().getOffset() <= indentationLength(line);
        final boolean isItem = item(line) != null;
        if (!isItem && !inIndentation) {
            IndentCommands.insertTab(editor);
            return;
        }
        reindent(editor, buffer.getIndentation(line) + buffer.getIndentSize());
    }

    /** {@code markdownShiftTab}. */
    public static void shiftTab(Editor editor) {
        if (!editor.checkReadOnly() || editor.getDot() == null)
            return;
        parse(editor.getBuffer());
        if (isMultiLine(editor)) {
            shiftLines(editor, -editor.getBuffer().getIndentSize());
            return;
        }
        final Buffer buffer = editor.getBuffer();
        final int col = buffer.getIndentation(editor.getDotLine());
        if (col > 0)
            reindent(editor, Math.max(0, col - buffer.getIndentSize()));
    }

    /** {@code markdownNewline}. */
    public static void newline(Editor editor) {
        if (!editor.checkReadOnly() || editor.getDot() == null)
            return;
        parse(editor.getBuffer());
        final Line line = editor.getDotLine();
        final Item item = editor.getMark() == null ? item(line) : null;
        if (item == null || editor.getDot().getOffset() < item.textBegin()) {
            EditCommands.newlineAndIndent(editor);
            return;
        }
        final Buffer buffer = editor.getBuffer();
        final String text = line.getText();
        buffer.withWriteLock(() -> {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            if (item.isEmpty(text)) {
                // Enter on an empty item ends the list.
                setText(editor, line, text.substring(0, item.markerBegin()));
                editor.getDot().moveTo(line, line.length());
            } else {
                final String rest = text.substring(editor.getDot().getOffset()).stripLeading();
                final String marker = text.substring(0, item.textBegin());
                setText(editor, line, text.substring(0, editor.getDot().getOffset()).stripTrailing());
                editor.getDot().moveTo(line, line.length());
                editor.addUndo(SimpleEdit.INSERT_LINE_SEP);
                EditCommands.insertLineSeparator(editor);
                final String next = nextMarker(marker, item);
                setText(editor, editor.getDotLine(), next + rest);
                editor.getDot().moveTo(editor.getDotLine(), next.length());
            }
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
            buffer.modified();
        });
    }

    /** {@code markdownBackspace}. */
    public static void backspace(Editor editor) {
        if (!editor.checkReadOnly() || editor.getDot() == null)
            return;
        parse(editor.getBuffer());
        final Buffer buffer = editor.getBuffer();
        final Line line = editor.getDotLine();
        final String text = line.getText();
        final int offset = editor.getDot().getOffset();
        if (editor.getMark() == null && offset == text.length() && offset > 0) {
            final Item item = item(line);
            if (item != null && item.isEmpty(text) && offset >= item.textBegin()) {
                // The marker goes, to the text of the item above.
                final int[] stops = stops(line, buffer);
                reindentBlank(editor, line, stops != null ? stops[stops.length - 1] : buffer.getIndentation(line));
                return;
            }
            if (text.isBlank()) {
                final int[] stops = stops(line, buffer);
                if (stops != null) {
                    final int to = previousStop(stops, buffer.getIndentation(line));
                    if (to >= 0) {
                        reindentBlank(editor, line, to);
                        return;
                    }
                }
            }
        }
        EditCommands.backspace(editor);
    }

    // ------------------------------------------------------------ helpers

    // The marker after one: the same, numbered one on, with an empty box for a box.
    static String nextMarker(String marker, Item item) {
        String m = marker.substring(0, item.markerEnd());
        final String number = marker.substring(item.markerBegin(), item.markerEnd() - 1);
        if (Character.isDigit(marker.charAt(item.markerBegin()))) {
            m = marker.substring(0, item.markerBegin()) + (Long.parseLong(number) + 1)
                    + marker.charAt(item.markerEnd() - 1);
        }
        if (item.box()) {
            final int open = marker.indexOf('[', item.markerEnd());
            return m + marker.substring(item.markerEnd(), open) + "[ ] ";
        }
        final String spaces = marker.substring(item.markerEnd());
        return m + (spaces.isEmpty() ? " " : spaces);
    }

    private static void parse(Buffer buffer) {
        if (buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
    }

    private static boolean isMultiLine(Editor editor) {
        return editor.getMark() != null && editor.getMarkLine() != editor.getDotLine();
    }

    private static int indentationLength(Line line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
            i++;
        return i;
    }

    // Line's indentation to col, the caret moving with the text, or to it from the indentation.
    private static void reindent(Editor editor, int col) {
        final Buffer buffer = editor.getBuffer();
        final Line line = editor.getDotLine();
        if (line.isBlank()) {
            reindentBlank(editor, line, col);
            return;
        }
        buffer.withWriteLock(() -> {
            final int before = indentationLength(line);
            final int offset = editor.getDot().getOffset();
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.LINE_EDIT);
            buffer.setIndentation(line, col);
            final int after = indentationLength(line);
            editor.getDot().setOffset(offset < before ? after : offset + after - before);
            Editor.updateInAllEditors(line);
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
            buffer.modified();
        });
    }

    // A blank line to just the indentation for col, the caret at its end.
    private static void reindentBlank(Editor editor, Line line, int col) {
        final Buffer buffer = editor.getBuffer();
        buffer.withWriteLock(() -> {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            setText(editor, line, buffer.getCorrectIndentationString(col).toString());
            editor.getDot().moveTo(line, line.length());
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
            buffer.modified();
        });
    }

    private static void setText(Editor editor, Line line, String text) {
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsRenumbering())
            buffer.renumber();
        buffer.addEdit(new UndoLineEdit(buffer, line));
        line.setText(text);
        Editor.updateInAllEditors(buffer, line);
    }

    // The selection's lines shifted by amount, blank ones left; a line the
    // selection ends at the start of isn't in it.
    private static void shiftLines(Editor editor, int amount) {
        final Buffer buffer = editor.getBuffer();
        final Region r = new Region(editor);
        Line last = r.getEndLine();
        if (r.getEndOffset() == 0 && last != r.getBeginLine())
            last = last.previous();
        final Line end = last;
        buffer.withWriteLock(() -> {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            for (Line line = r.getBeginLine();; line = line.next()) {
                if (!line.isBlank()) {
                    final int before = indentationLength(line);
                    buffer.addEdit(new UndoLineEdit(buffer, line));
                    buffer.setIndentation(line, Math.max(0, buffer.getIndentation(line) + amount));
                    final int delta = indentationLength(line) - before;
                    shift(editor.getDot(), line, before, delta);
                    shift(editor.getMark(), line, before, delta);
                    Editor.updateInAllEditors(buffer, line);
                }
                if (line == end)
                    break;
            }
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
            buffer.modified();
        });
    }

    private static void shift(Position pos, Line line, int indentation, int delta) {
        if (pos != null && pos.getLine() == line && pos.getOffset() > 0)
            pos.setOffset(Math.max(0, pos.getOffset() < indentation ? indentation + delta : pos.getOffset() + delta));
    }
}
