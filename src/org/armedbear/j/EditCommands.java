/*
 * EditCommands.java
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

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.undo.CompoundEdit;

/** Inserting and deleting text, undo and redo. */
public final class EditCommands {
    private EditCommands() {}

    public static void deleteLineSeparator(Editor editor) {
        final Display display = editor.getDisplay();
        final Line dotLine = editor.getDotLine();
        final Line nextLine = dotLine.next();
        if (nextLine == null)
            return;
        editor.getBuffer().withWriteLock(() -> {
            if (dotLine.length() == 0) {
                editor.adjustMarkers(dotLine);
                // Save original text.
                StringBuilder sb = new StringBuilder();
                if (dotLine.getOriginalText() != null)
                    sb.append(dotLine.getOriginalText());
                sb.append('\n');
                if (nextLine.getOriginalText() != null)
                    sb.append(nextLine.getOriginalText());
                else
                    sb.append(nextLine.getText());
                nextLine.setOriginalText(sb.toString());
                // Unlink the current line.
                final Line prevLine = dotLine.previous();
                if (prevLine != null)
                    prevLine.setNext(nextLine);
                nextLine.setPrevious(prevLine);
                if (dotLine == editor.getBuffer().getFirstLine()) {
                    Log.debug("deleteLineSeparator calling buffer.setFirstLine()");
                    editor.getBuffer().setFirstLine(nextLine);
                    Log.debug("first line = |" + editor.getBuffer().getFirstLine().getText() + "|");
                }
                if (dotLine == display.getTopLine())
                    display.setTopLine(nextLine);
                editor.getDot().moveTo(nextLine, 0);
            } else {
                // Append the next line's text to end of this line.
                dotLine.setText(dotLine.getText() + nextLine.getText());
                // Save original text.
                StringBuilder sb = new StringBuilder();
                if (dotLine.getOriginalText() != null)
                    sb.append(dotLine.getOriginalText());
                else
                    sb.append(dotLine.getText());
                if (!nextLine.isNew()) {
                    sb.append('\n');
                    if (nextLine.getOriginalText() != null)
                        sb.append(nextLine.getOriginalText());
                    else
                        sb.append(nextLine.getText());
                }
                dotLine.setOriginalText(sb.toString());
                // Move any markers that might be on the next line.
                editor.adjustMarkers(nextLine);
                // Unlink the next line.
                if (nextLine.next() != null)
                    nextLine.next().setPrevious(dotLine);
                dotLine.setNext(nextLine.next());
            }
            editor.getBuffer().repaint();
            editor.setUpdateFlag(REFRAME);
            editor.getBuffer().needsRenumbering = true;
            editor.getBuffer().modified();
        });
    }

    static void deleteNormalChar(Editor editor) {
        editor.addUndo(SimpleEdit.LINE_EDIT);
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();
        String head = dotLine.substring(0, dotOffset);
        String tail = "";
        if (dotOffset < dotLine.length() - 1)
            tail = dotLine.substring(dotOffset + 1);
        dotLine.setText(head.concat(tail));
        editor.getBuffer().modified();
        Editor.updateInAllEditors(dotLine);
    }

    // A deletion, not a kill!
    public static void delete(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        editor.getBuffer().withWriteLock(() -> {
            if (editor.getMark() != null) {
                editor.deleteRegion();
            } else {
                final Line dotLine = editor.getDotLine();
                final int dotOffset = editor.getDotOffset();
                final int length = dotLine.length();
                if (dotOffset < length) {
                    deleteNormalChar(editor);
                } else if (dotOffset == length) {
                    if (dotLine.next() != null) {
                        CompoundEdit compoundEdit = editor.beginCompoundEdit();
                        editor.fillToCaret();
                        editor.addUndo(SimpleEdit.DELETE_LINE_SEP);
                        deleteLineSeparator(editor);
                        editor.endCompoundEdit(compoundEdit);
                    } else
                        editor.status("End of buffer");
                } else {
                    // Shouldn't happen.
                    Debug.bug();
                }
            }
        });
    }

    // A deletion, not a kill!
    public static void backspace(Editor editor) {
        final Display display = editor.getDisplay();
        if (!editor.checkReadOnly())
            return;
        editor.getBuffer().withWriteLock(() -> {
            if (editor.getMark() != null) {
                delete(editor);
            } else if (display.getCaretCol() > editor.getBuffer()
                    .getCol(editor.getDotLine(), editor.getDotLine().length())) {
                // The caret is beyond the end of the actual text on the current line.
                editor.addUndo(SimpleEdit.MOVE);
                display.setCaretCol(display.getCaretCol() - 1);
                editor.updateDotLine();
            } else if (ElectricPairs
                    .deleteBoth(editor.getMode(), editor.getBuffer(), editor.getDotLine(), editor.getDotOffset())) {
                // The caret sits between an empty pair: take both
                // characters as one undo step. The closer is at the
                // caret, the opener one left; delete the closer, step
                // onto the opener, delete it. The caret lands where
                // the opener was.
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                deleteNormalChar(editor);
                editor.getDot().moveLeft();
                deleteNormalChar(editor);
                editor.endCompoundEdit(compoundEdit);
                editor.moveCaretToDotCol();
            } else if (editor.getDot().getOffset() > 0) {
                editor.addUndo(SimpleEdit.LINE_EDIT);
                editor.getDot().moveLeft();
                deleteNormalChar(editor);
                editor.moveCaretToDotCol();
            } else if (editor.getDotLine().previous() != null) {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.addUndo(SimpleEdit.MOVE);
                editor.getDot().moveTo(editor.getDotLine().previous(), editor.getDotLine().previous().length());
                editor.addUndo(SimpleEdit.DELETE_LINE_SEP);
                deleteLineSeparator(editor);
                editor.endCompoundEdit(compoundEdit);
                editor.moveCaretToDotCol();
            }
        });
    }

    // No undo.
    public static void insertLineSeparator(Editor editor) {
        Debug.assertTrue(editor.getMark() == null);
        if (!editor.getBuffer().withWriteLock(() -> {
            editor.getBuffer().insertLineSeparator(editor.getDot());
        }))
            return;
        final Line dotLine = editor.getDotLine();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            if (ed.getTopLine() == dotLine)
                ed.setTopLine(dotLine.previous());
        }
    }

    public static void newline(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        if (editor.getMark() != null)
            editor.deleteRegion();
        editor.addUndo(SimpleEdit.INSERT_LINE_SEP);
        insertLineSeparator(editor);
        editor.moveCaretToDotCol();
        editor.endCompoundEdit(compoundEdit);
    }

    public static void newlineAndIndent(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        if (!editor.checkReadOnly())
            return;
        if (!editor.getBuffer().withWriteLock(() -> {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            if (editor.getMark() != null)
                editor.deleteRegion();
            final Mode mode = editor.getMode();
            if (ElectricPairs
                    .decide(
                        mode,
                        editor.getBuffer(),
                        editor.getDot(),
                        ElectricPairs.Operation.ENTER,
                        '\n',
                        false,
                        false)
                    .action() == ElectricPairs.Action.OPEN_LINE) {
                openLineBetweenPair(editor);
                editor.endCompoundEdit(compoundEdit);
                return;
            }
            editor.addUndo(SimpleEdit.INSERT_LINE_SEP);
            insertLineSeparator(editor);
            final Line dotLine = editor.getDotLine();
            int indent;
            if (mode.canIndent()) {
                if (editor.getBuffer().needsRenumbering())
                    editor.getBuffer().renumber();
                editor.getFormatter().parseBuffer();
                indent = mode.getCorrectIndentation(dotLine, editor.getBuffer());
            } else {
                // Can't indent according to context. Match indentation of previous line.
                indent = editor.getBuffer().getIndentation(dotLine.previous());
            }
            if (indent != editor.getBuffer().getIndentation(dotLine)) {
                editor.addUndo(SimpleEdit.LINE_EDIT);
                editor.getBuffer().setIndentation(dotLine, indent);
            }
            if (dotLine.length() > 0) {
                IndentCommands.moveDotToIndentation(editor);
                editor.moveCaretToDotCol();
            } else {
                display.setCaretCol(indent - display.getShift());
                if (editor.getBuffer().getBooleanProperty(Property.RESTRICT_CARET))
                    editor.fillToCaret();
            }
            editor.endCompoundEdit(compoundEdit);
        }))
            return;
        editor.setUpdateFlag(REFRAME);
    }

    /**
     * Enter between an empty pair: {@code {|}} becomes three lines,
     * the caret on the indented interior, the closer on the third at
     * the outer indent. Both lines are created before any indentation
     * is computed. If the interior were indented after only the first
     * split, the closer would still be on it, {@code
     * getCorrectIndentation} would see the closer and return the
     * outer indent, and the middle line would land at the wrong
     * column. The closer is reindented on its own line with its own
     * call, the way {@code insertBraces} does.
     */
    private static void openLineBetweenPair(Editor editor) {
        final Display display = editor.getDisplay();
        final Mode mode = editor.getMode();
        final Buffer buffer = editor.getBuffer();
        // The closer moves to the line below the opener.
        editor.addUndo(SimpleEdit.INSERT_LINE_SEP);
        insertLineSeparator(editor);
        // The caret is at the start of the closer's line; a second
        // separator there opens the empty interior above it.
        editor.addUndo(SimpleEdit.INSERT_LINE_SEP);
        insertLineSeparator(editor);
        final Line closer = editor.getDotLine();
        final Line interior = closer.previous();
        if (mode.canIndent()) {
            if (buffer.needsRenumbering())
                buffer.renumber();
            buffer.getFormatter().parseBuffer();
            // The closer has content, so it takes a real indent.
            int closerIndent = mode.getCorrectIndentation(closer, buffer);
            if (closerIndent != buffer.getIndentation(closer)) {
                editor.addUndo(SimpleEdit.LINE_EDIT);
                buffer.setIndentation(closer, closerIndent);
            }
            // The interior is blank: a blank line carries no real
            // indent, so the caret goes to the body indent virtually,
            // as the ordinary newlineAndIndent leaves it.
            int interiorIndent = mode.getCorrectIndentation(interior, buffer);
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveTo(interior, 0);
            display.setCaretCol(interiorIndent - display.getShift());
            if (buffer.getBooleanProperty(Property.RESTRICT_CARET))
                editor.fillToCaret();
            return;
        }
        // No context indent: match the opener's indentation.
        int indent = buffer.getIndentation(interior.previous());
        editor.addUndo(SimpleEdit.MOVE);
        editor.getDot().moveTo(interior, 0);
        display.setCaretCol(indent - display.getShift());
    }

    public static void insertNormalChar(Editor editor, char c) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        if (!editor.checkReadOnly())
            return;
        try {
            editor.getBuffer().lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            c = editor.getMode().fixCase(editor, c);
            ElectricPairs.Decision decision = ElectricPairs.decide(
                editor.getMode(),
                editor.getBuffer(),
                editor.getDot(),
                ElectricPairs.Operation.INSERT,
                c,
                editor.getMark() != null,
                // The literal-insertion depth arrives with step 8.
                false);
            switch (decision.action()) {
                case INSERT_PAIR:
                    insertPair(editor, decision.pair().opener(), decision.pair().closer());
                    return;
                case INSERT_OPENER:
                    editor.insertChar(c);
                    return;
                case TYPE_OVER:
                    typeOverCloser(editor);
                    return;
                case WRAP:
                    wrapSelection(editor, decision.pair().opener(), decision.pair().closer());
                    return;
                case SUPPRESS:
                    return;
                default:
                    break;
            }
            if (editor.getMark() != null) {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.deleteRegion();
                editor.insertChar(c);
                editor.endCompoundEdit(compoundEdit);
            } else {
                // No selection.
                if (editor.getBuffer().getBooleanProperty(Property.WRAP)
                        && editor.getDotCol() >= editor.getBuffer().getIntegerProperty(Property.WRAP_COL)) {
                    CompoundEdit compoundEdit = editor.beginCompoundEdit();
                    editor.insertChar(c);
                    new WrapText(editor).wrapLine();
                    editor.endCompoundEdit(compoundEdit);
                } else
                    editor.insertChar(c);
            }
        }
        finally {
            editor.getBuffer().unlockWrite();
        }
        editor.moveCaretToDotCol();
    }

    /**
     * Insert a pair with the caret between: one undo step for both
     * characters. {@link Editor#insertChar} adds its own LINE_EDIT, so
     * the compound edit is what makes the two inserts one step. The
     * caret is synced here because the caller's epilogue is bypassed.
     */
    private static void insertPair(Editor editor, char opener, char closer) {
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.insertChar(opener);
        editor.insertChar(closer);
        editor.addUndo(SimpleEdit.MOVE);
        editor.getDot().skip(-1);
        editor.endCompoundEdit(compoundEdit);
        editor.moveCaretToDotCol();
    }

    /** Step over the closer under the caret; the text does not change. */
    private static void typeOverCloser(Editor editor) {
        editor.addUndo(SimpleEdit.MOVE);
        editor.getDot().skip(1);
        editor.moveCaretToDotCol();
    }

    /**
     * Wrap the selection in a pair, leaving the caret after the opener.
     * The shape of {@code insertParentheses}: insert the closer at the
     * end of the region, then the opener at its start, in one edit.
     */
    private static void wrapSelection(Editor editor, char opener, char closer) {
        boolean parensRequireSpaces =
                opener == '(' && editor.getBuffer().getBooleanProperty(Property.PARENS_REQUIRE_SPACES);
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        Position begin, end;
        if (editor.getMark().isBefore(editor.getDot())) {
            begin = new Position(editor.getMark());
            end = new Position(editor.getDot());
        } else {
            begin = new Position(editor.getDot());
            end = new Position(editor.getMark());
        }
        editor.addUndo(SimpleEdit.MOVE);
        editor.setMark(null);
        editor.getDot().moveTo(end);
        if (parensRequireSpaces)
            editor.insertChar(' ');
        editor.insertChar(closer);
        editor.addUndo(SimpleEdit.MOVE);
        editor.getDot().moveTo(begin);
        editor.insertChar(opener);
        if (parensRequireSpaces)
            editor.insertChar(' ');
        editor.endCompoundEdit(compoundEdit);
        editor.moveCaretToDotCol();
    }

    public static void insertByte(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        String input = InputDialog.showInputDialog(editor, "Byte:", "Insert Byte");
        if (input == null || input.length() == 0)
            return;
        editor.repaintNow();
        int c = Editor.parseNumericInput(input);
        if (c >= 0 && c <= 255) {
            byte[] bytes = new byte[1];
            bytes[0] = (byte) c;
            String encoding = Editor.preferences().getStringProperty(Property.DEFAULT_ENCODING);
            try {
                String s = new String(bytes, encoding);
                editor.insertChar(s.charAt(0));
            }
            catch (UnsupportedEncodingException e) {
                Log.error(e);
                MessageDialog.showMessageDialog(editor, "Unsupported encoding \"" + encoding + "\"", "Insert Byte");
            }
        } else
            MessageDialog.showMessageDialog(editor, "Invalid byte \"" + input + "\"", "Insert Byte");
    }

    public static void stamp(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        Date now = new Date(System.currentTimeMillis());
        String dateString = null;
        String stampFormat = editor.getBuffer().getStringProperty(Property.STAMP_FORMAT);
        if (stampFormat != null) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(stampFormat);
                dateString = df.format(now);
            }
            catch (IllegalArgumentException e) {
                // Fall through...
            }
        }
        if (dateString == null) {
            SimpleDateFormat df = new SimpleDateFormat("MMM d yyyy h:mm a");
            dateString = df.format(now);
        }
        try {
            editor.getBuffer().lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            if (editor.getMark() != null)
                delete(editor);
            editor.fillToCaret();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal(dateString);
            editor.getBuffer().modified();
            editor.endCompoundEdit(compoundEdit);
            editor.moveCaretToDotCol();
            Editor.updateInAllEditors(editor.getDotLine());
        }
        finally {
            editor.getBuffer().unlockWrite();
        }
    }

    public static void undo(Editor editor) {
        try {
            editor.getBuffer().lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        editor.setWaitCursor();
        try {
            editor.getBuffer().undo();
            editor.checkDotInOtherFrames();
            editor.setCurrentCommand(COMMAND_UNDO);
        }
        catch (RuntimeException e) {
            Log.error(e);
        }
        finally {
            editor.getBuffer().unlockWrite();
            editor.setDefaultCursor();
        }
    }

    public static void redo(Editor editor) {
        try {
            editor.getBuffer().lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        editor.setWaitCursor();
        try {
            editor.getBuffer().redo();
            editor.checkDotInOtherFrames();
        }
        catch (RuntimeException e) {
            Log.error(e);
        }
        finally {
            editor.getBuffer().unlockWrite();
            editor.setDefaultCursor();
        }
    }

    public static void insertKeyText(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        editor.setInsertingKeyText(true); // The real work is done in handleKeyEvent.
    }

    static void insertKeyTextInternal(Editor editor, char keyChar, int keyCode, int modifiers) {
        Log.debug("keycode = 0x" + Integer.toString(keyCode, 16));
        Log.debug("modifiers = 0x" + Integer.toString(modifiers, 16));
        Log.debug("character = " + String.valueOf(keyChar));
        Log.debug("character = 0x" + Integer.toString((int) keyChar, 16));

        editor.setInsertingKeyText(false);

        editor.getBuffer().withWriteLock(() -> {
            KeyMapping km;
            if (keyCode != 0)
                km = new KeyMapping(keyCode, modifiers, null);
            else
                km = new KeyMapping(keyChar, null);

            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            if (editor.getMark() != null)
                delete(editor);
            editor.fillToCaret();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal(km.toString());
            editor.getBuffer().modified();
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
        });
    }

    public static void whatChar(Editor editor) {
        if (editor.getDot().getOffset() < editor.getDot().getLineLength()) {
            char c = editor.getDotChar();
            StringBuilder sb = new StringBuilder(Integer.toString(c));
            sb.append("  0x");
            sb.append(Integer.toHexString(c));
            if (c >= ' ' && c < 0x7f) {
                sb.append("  '");
                if (c == '\'')
                    sb.append('\\');
                sb.append(c);
                sb.append('\'');
            }
            editor.status(sb.toString());
        }
    }
}
