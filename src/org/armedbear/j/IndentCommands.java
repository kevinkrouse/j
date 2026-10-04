/*
 * IndentCommands.java
 *
 * Copyright (C) 1998-2003 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import javax.swing.undo.CompoundEdit;
import org.armedbear.j.util.Utilities;

/** Indenting, sliding, commenting and wrapping lines and regions. */
public final class IndentCommands {
    private IndentCommands() {}

    // Cycle through the most plausible possibilities for the tab width of the
    // current buffer.
    public static void cycleTabWidth(Editor editor) {
        switch (editor.getBuffer().getTabWidth()) {
            case 2:
                editor.getBuffer().setTabWidth(4);
                break;
            case 4:
                editor.getBuffer().setTabWidth(8);
                break;
            case 8:
            default:
                editor.getBuffer().setTabWidth(2);
                break;
        }
        editor.getBuffer().saveProperties();
        editor.status("Tab width set to " + editor.getBuffer().getTabWidth());
        editor.getBuffer().repaint();
    }

    // Cycle through the most plausible possibilities for the indent size of
    // the current buffer.
    public static void cycleIndentSize(Editor editor) {
        switch (editor.getBuffer().getIndentSize()) {
            case 2:
                editor.getBuffer().setIndentSize(3);
                break;
            case 3:
                editor.getBuffer().setIndentSize(4);
                break;
            case 4:
                editor.getBuffer().setIndentSize(8);
                break;
            case 8:
            default:
                editor.getBuffer().setIndentSize(2);
                break;
        }
        editor.getBuffer().saveProperties();
        editor.status("Indent size set to " + editor.getBuffer().getIndentSize());
    }

    public static void tab(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        if (editor.getBuffer().getBooleanProperty(Property.TAB_ALWAYS_INDENT)) {
            indentLineOrRegion(editor);
            return;
        }
        if (editor.getMark() == null) {
            // No selection.
            if (editor.getDot().getOffset() <= editor.getDot().getLine().getIndentation())
                indentLine(editor);
            else
                insertTab(editor);
            return;
        }
        if (editor.getMarkLine() != editor.getDotLine()) {
            // Multi-line selection.
            indentRegion(editor);
            return;
        }
        // Single-line selection.
        Region r = new Region(editor);
        if (r.getBeginOffset() <= editor.getDotLine().getIndentation())
            indentLine(editor);
        else
            insertTab(editor);
    }

    public static void insertTab(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }

        if (!editor.checkReadOnly())
            return;

        editor.getBuffer().withWriteLock(() -> {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            if (editor.getMark() != null)
                editor.deleteRegion();
            if (editor.getBuffer().getUseTabs())
                editor.insertChar('\t');
            else {
                editor.fillToCaret();
                int tabWidth = editor.getBuffer().getTabWidth();
                editor.addUndo(SimpleEdit.LINE_EDIT);
                editor.getBuffer()
                    .insertChars(editor.getDot(), Utilities.spaces(tabWidth - editor.getDotCol() % tabWidth));
                Editor.updateInAllEditors(editor.getDotLine());
            }
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
        });
    }

    public static void moveDotToIndentation(Editor editor) {
        final Line dotLine = editor.getDotLine();
        final int limit = dotLine.length();
        int i;
        for (i = 0; i < limit; i++) {
            if (!Character.isWhitespace(dotLine.charAt(i)))
                break;
        }
        editor.getDot().setOffset(i);
    }

    public static void indentLineOrRegion(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        if (editor.getMode().canIndent()) {
            if (editor.getMark() != null && editor.getMarkLine() != editor.getDotLine()) {
                indentRegion(editor);
            } else {
                editor.unmark();
                indentLine(editor);
            }
        }
    }

    public static void indentRegion(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        if (editor.getMode().canIndent() && editor.getMark() != null) {
            Region r = new Region(editor);
            if (r.getBeginLine() == r.getEndLine())
                indentLine(editor);
            else {
                if (!editor.checkReadOnly())
                    return;
                editor.setWaitCursor();
                Position savedDot = new Position(editor.getDot());
                if (!editor.getBuffer().withWriteLock(() -> {
                    if (editor.getBuffer().needsParsing()) {
                        if (editor.getFormatter().parseBuffer())
                            editor.getBuffer().repaint();
                    }
                    CompoundEdit compoundEdit = editor.beginCompoundEdit();
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.getDot().moveTo(r.getBeginLine(), 0);
                    do {
                        if (!editor.getDot().getLine().isBlank())
                            indentLineInternal(editor);
                        editor.getDot().moveTo(editor.getDot().getNextLine(), 0);
                    } while (editor.getDot().getLine() != r.getEndLine());
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.setDot(savedDot);
                    if (editor.getDot().getOffset() > editor.getDotLine().length())
                        editor.getDot().setOffset(editor.getDotLine().length());
                    if (editor.getMark().getOffset() > editor.getMarkLine().length())
                        editor.getMark().setOffset(editor.getMarkLine().length());
                    editor.moveCaretToDotCol();
                    editor.endCompoundEdit(compoundEdit);
                }))
                    return;
                editor.setUpdateFlag(REFRAME);
                editor.setDefaultCursor();
            }
        }
    }

    public static void commentRegion(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        commentRegion(editor, true);
    }

    public static void uncommentRegion(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        commentRegion(editor, false);
    }

    // If argument is false, uncomment the region.
    private static void commentRegion(Editor editor, boolean comment) {
        if (!editor.checkReadOnly())
            return;
        if (editor.getDot() == null)
            return;
        editor.getBuffer().withWriteLock(() -> {
            commentRegionInternal(editor, comment);
        });
    }

    // If argument is false, uncomment the region.
    private static void commentRegionInternal(Editor editor, boolean comment) {
        String commentStart = editor.getBuffer().getCommentStart();
        if (commentStart == null)
            return;
        String commentEnd = editor.getBuffer().getCommentEnd();
        Position savedDot = new Position(editor.getDot());
        Line beginLine, endLine;
        if (editor.getMark() == null)
            editor.setMark(editor.getBuffer().getMark());
        if (editor.getMark() != null) {
            Region r = new Region(editor.getBuffer(), editor.getDot(), editor.getMark());
            beginLine = r.getBeginLine();
            endLine = r.getEndLine();
            // A region ending inside a line takes that line too.
            if (r.getEndOffset() > 0)
                endLine = endLine.next();
        } else {
            beginLine = editor.getDotLine();
            endLine = editor.getDotLine().next();
        }
        if (endLine == beginLine)
            endLine = beginLine.next();
        // What uncomment also accepts: the comment start without its padding.
        final String bareStart = commentStart.trim();

        CompoundEdit compoundEdit = editor.beginCompoundEdit();

        if (editor.getMark() != null) {
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMark(null);
            editor.setUpdateFlag(REPAINT);
        }

        if (editor.getDotLine() != beginLine) {
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveTo(beginLine, 0);
        }

        boolean modified = false;

        while (true) {
            Line dotLine = editor.getDotLine();
            String trim = dotLine.trim();
            if (comment) {
                if (trim.length() != 0) {
                    editor.addUndo(SimpleEdit.LINE_EDIT);
                    if (commentEnd != null)
                        dotLine.setText(commentStart + dotLine.getText() + commentEnd);
                    else
                        dotLine.setText(commentStart + dotLine.getText());
                    modified = true;
                    if (dotLine == savedDot.getLine())
                        savedDot.setOffset(savedDot.getOffset() + commentStart.length());
                    Editor.updateInAllEditors(dotLine);
                }
            } else {
                // Uncomment: the inverse of comment, where the comment
                // starts after any indentation; nothing is re-indented.
                final String text = dotLine.getText();
                final int at = text.length() - text.stripLeading().length();
                final String start = text.startsWith(commentStart, at)
                    ? commentStart
                    : text.startsWith(bareStart, at) ? bareStart : null;
                // The comment end may have trailing whitespace after it.
                final int trimmed = text.stripTrailing().length();
                final int end = commentEnd == null
                    ? text.length()
                    : text.startsWith(commentEnd, trimmed - commentEnd.length())
                        ? trimmed - commentEnd.length()
                        : -1;
                final String tail = commentEnd == null ? "" : text.substring(trimmed);
                if (start != null && end >= at + start.length()) {
                    editor.addUndo(SimpleEdit.LINE_EDIT);
                    dotLine.setText(text.substring(0, at) + text.substring(at + start.length(), end) + tail);
                    modified = true;
                    if (dotLine == savedDot.getLine()) {
                        int offset = savedDot.getOffset();
                        if (offset > at)
                            offset = Math.max(at, offset - start.length());
                        savedDot.setOffset(Math.min(offset, dotLine.length()));
                    }
                    Editor.updateInAllEditors(dotLine);
                }
            }

            if (dotLine.next() == endLine)
                break;

            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveTo(dotLine.next(), 0);
        }

        editor.addUndo(SimpleEdit.MOVE);
        editor.setDot(savedDot);
        editor.moveCaretToDotCol();

        if (modified)
            editor.getBuffer().modified();

        editor.endCompoundEdit(compoundEdit);
    }

    public static void indentLine(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        if (!editor.checkReadOnly())
            return;
        if (!editor.getMode().canIndent())
            return; // No change.
        if (!editor.getBuffer().withWriteLock(() -> {
            if (editor.getBuffer().needsParsing()) {
                if (editor.getFormatter().parseBuffer())
                    editor.getBuffer().repaint();
            }
            indentLineInternal(editor);
        }))
            return;
        editor.setUpdateFlag(REFRAME);
    }

    static void indentLineInternal(Editor editor) {
        final Display display = editor.getDisplay();
        final Line dotLine = editor.getDotLine();
        final int indent = editor.getMode().getCorrectIndentation(dotLine, editor.getBuffer());
        final int shift = display.getShift();

        if (dotLine.isBlank()) {
            // Put the caret where it needs to go...
            editor.addUndo(SimpleEdit.LINE_EDIT);
            dotLine.setText("");
            editor.getDot().setOffset(0);
            display.setCaretCol(indent - shift);
            // Fill if necessary.
            if (editor.getBuffer().getBooleanProperty(Property.RESTRICT_CARET))
                editor.fillToCaret();
            Editor.updateInAllEditors(dotLine);
            return;
        }

        // Line is not blank. Figure out current indentation.
        final int oldIndent = editor.getBuffer().getIndentation(dotLine);

        StringBuilder sb = null;

        if (indent == oldIndent) {
            boolean ok = false;
            if (editor.getBuffer().getBooleanProperty(Property.INDENT_LINE_FIX_WHITESPACE)) {
                sb = editor.getBuffer().getCorrectIndentationString(indent);
                if (dotLine.getText().startsWith(sb.toString()))
                    ok = true;
            } else
                ok = true;

            if (ok) {
                // Current indentation is correct. If the caret is in the
                // indentation area, move it to the start of the non-blank
                // text.
                if (display.getCaretCol() + shift < indent) {
                    editor.addUndo(SimpleEdit.MOVE);
                    display.setCaretCol(indent - shift);
                    editor.moveDotToCaretCol();
                }
                return;
            }
        }

        // We need to fix the indentation. Figure out where we want to put the
        // caret when we're done. We want to maintain the existing offset from
        // the start of the non-blank text.
        final int existing = display.getCaretCol() + shift - oldIndent;
        final int goal = existing < 0 ? indent : existing + indent;

        // Strip existing indentation.
        int i = 0;
        while (i < dotLine.length() && Character.isWhitespace(dotLine.charAt(i)))
            ++i;
        String nonBlank = dotLine.substring(i);

        // Get the correct indentation string.
        if (sb == null)
            sb = editor.getBuffer().getCorrectIndentationString(indent);

        // Add the rest of the line.
        sb.append(nonBlank);

        // Replace the existing text.
        editor.addUndo(SimpleEdit.LINE_EDIT);
        dotLine.setText(sb.toString());
        editor.getBuffer().modified();
        Editor.updateInAllEditors(dotLine);

        // Put the caret where we want it.
        display.setCaretCol(goal - shift);
        editor.moveDotToCaretCol();
    }

    public static void slideIn(Editor editor) {
        slide(editor, editor.getBuffer().getIndentSize());
    }

    public static void slideOut(Editor editor) {
        slide(editor, -editor.getBuffer().getIndentSize());
    }

    private static void slide(Editor editor, int amount) {
        final Display display = editor.getDisplay();
        if (!editor.checkReadOnly())
            return;
        Region r = editor.getMark() != null ? new Region(editor) : null;
        if (r != null && (r.getBeginOffset() != 0 || r.getEndOffset() != 0))
            return; // If a block is marked, it must be a block of full lines.
        editor.getBuffer().withWriteLock(() -> {
            if (r == null) {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                int dotCol = editor.getDotCol();
                int oldIndent = editor.getBuffer().getIndentation(editor.getDotLine());
                int newIndent = oldIndent + amount;
                editor.addUndo(SimpleEdit.LINE_EDIT);
                editor.getBuffer().setIndentation(editor.getDotLine(), newIndent);
                Editor.updateInAllEditors(editor.getDotLine());
                if (dotCol < oldIndent) {
                    // Caret was originally in indentation area. Move caret to
                    // start of text. This ensures that the caret is on an
                    // actual character, in case the indentation got entabbed.
                    editor.moveDotToCol(newIndent);
                } else {
                    // Move caret with text.
                    display.setCaretCol(display.getCaretCol() + amount);
                    editor.moveDotToCaretCol();
                }
                editor.endCompoundEdit(compoundEdit);
                editor.getBuffer().modified();
            } else {
                // If a block is marked, it must be a block of full lines.
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                Position saved = new Position(editor.getDot());
                Line line = r.getBeginLine();
                while (line != r.getEndLine()) {
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.getDot().moveTo(line, 0);
                    editor.addUndo(SimpleEdit.LINE_EDIT);
                    editor.getBuffer()
                        .setIndentation(
                            editor.getDotLine(),
                            editor.getBuffer().getIndentation(editor.getDotLine()) + amount
                        );
                    Editor.updateInAllEditors(editor.getDotLine());
                    line = line.next();
                }
                editor.addUndo(SimpleEdit.MOVE);
                editor.setDot(saved);
                editor.endCompoundEdit(compoundEdit);
                editor.getBuffer().modified();
            }
        });
    }

    public static void wrapRegion(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        if (editor.getDot() == null || editor.getMark() == null)
            return;
        // Must be line block.
        if (editor.getDot().getOffset() != 0 || editor.getMark().getOffset() != 0)
            return;
        new WrapText(editor).wrapRegion();
    }

    public static void wrapParagraph(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        new WrapText(editor).wrapParagraph();
    }

    public static void unwrapParagraph(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        new WrapText(editor).unwrapParagraph();
    }

    public static void wrapParagraphsInRegion(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        new WrapText(editor).wrapParagraphsInRegion();
    }

    public static void justOneSpace(Editor editor) {
        try {
            editor.getBuffer().lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.beginMotion();
            while (MotionCommands.inWhitespace(editor) && MotionCommands.nextChar(editor))
                ;
            editor.setMarkAtDot();
            while (MotionCommands.prevChar(editor)) {
                if (!MotionCommands.inWhitespace(editor)) {
                    MotionCommands.nextChar(editor);
                    break;
                }
            }

            editor.deleteRegion();
            editor.insertChar(' ');
            editor.endCompoundEdit(compoundEdit);
        }
        finally {
            editor.getBuffer().unlockWrite();
        }
    }
}
