/*
 * ElectricCommands.java
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

/** Keys that insert a character and reindent, and paired brackets. */
public final class ElectricCommands {
    private ElectricCommands() {}

    public static void closeParen(Editor editor) {
        final Display display = editor.getDisplay();
        EditCommands.insertNormalChar(editor, ')');
        if (
            Editor.preferences().getBooleanProperty(Property.HIGHLIGHT_MATCHING_BRACKET)
                ||
                Editor.preferences().getBooleanProperty(Property.HIGHLIGHT_BRACKETS)
        )
            return;
        // Limit search to 50 lines.
        Position match =
            CaretCommands.findMatchInternal(editor, new Position(editor.getDotLine(), editor.getDotOffset() - 1), 50);
        if (match == null)
            return;
        // We don't want to reframe.
        if (match.lineNumber() < display.getTopLineNumber())
            return;
        if (editor.getBuffer().getCol(match) < display.getShift())
            return;
        // Highlight the match for a moment; the caret stays put.
        endParenFlash(editor);
        display.flashMatch(match);
        editor.parenFlash = new javax.swing.Timer(Editor.parenFlashMillis, e -> endParenFlash(editor));
        editor.parenFlash.setRepeats(false);
        editor.parenFlash.start();
    }

    /** Ends closeParen's highlight of the matching paren. */
    static void endParenFlash(Editor editor) {
        if (editor.parenFlash == null)
            return;
        editor.parenFlash.stop();
        editor.parenFlash = null;
        editor.getDisplay().flashMatch(null);
    }

    public static void electricSemi(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        if (
            editor.getMark() != null
                || editor.getDotLine().flags() == STATE_COMMENT
                ||
                editor.getMode().isInQuote(editor.getBuffer(), editor.getDot())
        ) {
            EditCommands.insertNormalChar(editor, ';');
        } else {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.insertChar(';');
            editor.moveCaretToDotCol();
            IndentCommands.indentLine(editor);
            if (editor.getBuffer().getBooleanProperty(Property.AUTO_NEWLINE)) {
                boolean b = true;
                String s = editor.getDot().getLine().trim();
                if (s.startsWith("for")) {
                    char c = s.charAt(3);
                    if (c == ' ' || c == '\t' || c == '(')
                        b = false;
                }
                if (b)
                    EditCommands.newlineAndIndent(editor);
            }
            editor.endCompoundEdit(compoundEdit);
        }
    }

    public static void electricColon(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        editor.getBuffer().withWriteLock(() -> {
            electricColonInternal(editor);
        });
    }

    private static void electricColonInternal(Editor editor) {
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();
        if (editor.getMark() != null || dotOffset != dotLine.length()) {
            EditCommands.insertNormalChar(editor, ':');
            return;
        }
        if (dotLine.flags() == STATE_COMMENT || editor.getMode().isInQuote(editor.getBuffer(), editor.getDot())) {
            EditCommands.insertNormalChar(editor, ':');
            return;
        }
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.insertChar(':');
        editor.moveCaretToDotCol();
        IndentCommands.indentLine(editor);
        if (editor.getBuffer().getBooleanProperty(Property.AUTO_NEWLINE))
            EditCommands.newlineAndIndent(editor);
        editor.endCompoundEdit(compoundEdit);
    }

    public static void electricStar(Editor editor) {
        if (!editor.checkReadOnly())
            return;

        // The intention here is to line up the '*' under the '*' of
        // the previous line if the current line is blank and if the
        // previous line begins with "/*".
        if (editor.getDotLine().isBlank()) {
            if (editor.getBuffer().needsParsing()) {
                if (editor.getFormatter().parseBuffer())
                    editor.getBuffer().repaint();
            }
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            EditCommands.insertNormalChar(editor, '*');
            IndentCommands.indentLine(editor);
            editor.endCompoundEdit(compoundEdit);
        } else
            EditCommands.insertNormalChar(editor, '*');
    }

    public static void electricPound(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        if (editor.getMark() == null && editor.getDotLine().isBlank()) {
            if (!editor.getBuffer().withWriteLock(() -> {
                editor.addUndo(SimpleEdit.LINE_EDIT);
                editor.getDotLine().setText("#");
                editor.getDot().setOffset(1);
                editor.getBuffer().modified();
            }))
                return;
            Editor.updateInAllEditors(editor.getDotLine());
            editor.moveCaretToDotCol();
        } else
            EditCommands.insertNormalChar(editor, '#');
    }

    public static void electricOpenBrace(Editor editor) {
        electricBraceInternal(editor, '{');
    }

    public static void electricCloseBrace(Editor editor) {
        electricBraceInternal(editor, '}');
    }

    private static void electricBraceInternal(Editor editor, char c) {
        if (!editor.checkReadOnly())
            return;
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        if (editor.getMark() == null && editor.getDotLine().isBlank()) {
            editor.addUndo(SimpleEdit.LINE_EDIT);
            editor.getDotLine().setText("");
            editor.getDot().setOffset(0);
            editor.insertChar(c);
            IndentCommands.indentLine(editor);
            MotionCommands.eol(editor);
            if (editor.getBuffer().getBooleanProperty(Property.AUTO_NEWLINE))
                EditCommands.newlineAndIndent(editor);
        } else {
            EditCommands.insertNormalChar(editor, c);
            IndentCommands.indentLine(editor);
        }
        editor.endCompoundEdit(compoundEdit);
    }

    public static void electricCloseAngleBracket(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        if (editor.getMark() == null) {
            int modeId = editor.getModeId();
            if (modeId == XML_MODE || modeId == HTML_MODE) {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.insertChar('>');
                editor.moveCaretToDotCol();
                if (editor.getBuffer().getBooleanProperty(Property.AUTO_INDENT)) {
                    if (
                        modeId == HTML_MODE
                            &&
                            editor.getDotLine()
                                .substring(0, editor.getDotOffset())
                                .endsWith(
                                    "</pre>"
                                )
                    ) {
                        ; // No autoindent after "</pre>" in HTML mode.
                    } else {
                        IndentCommands.indentLine(editor);
                    }
                }
                editor.endCompoundEdit(compoundEdit);
                return;
            }
        }
        // Otherwise...
        EditCommands.insertNormalChar(editor, '>');
    }

    public static void insertBraces(Editor editor) {
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.insertChar('{');
        IndentCommands.indentLine(editor);
        MotionCommands.eol(editor);
        EditCommands.newlineAndIndent(editor);
        editor.insertChar('}');
        IndentCommands.indentLine(editor);
        MotionCommands.up(editor);
        MotionCommands.eol(editor);
        EditCommands.newlineAndIndent(editor);
        editor.endCompoundEdit(compoundEdit);
    }

    public static void insertParentheses(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        boolean parensRequireSpaces =
            editor.getBuffer().getBooleanProperty(Property.PARENS_REQUIRE_SPACES);
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        if (editor.getMark() != null) {
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
            editor.insertChar(')');
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveTo(begin);
            editor.insertChar('(');
            if (parensRequireSpaces)
                editor.insertChar(' ');
        } else {
            editor.fillToCaret();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal(parensRequireSpaces ? "(  )" : "()");
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().skip(parensRequireSpaces ? -2 : -1);
        }
        editor.moveCaretToDotCol();
        editor.endCompoundEdit(compoundEdit);
    }

    public static void movePastCloseAndReindent(Editor editor) {
        Position pos = new Position(editor.getDot());
        int count = 1;
        if (pos.getChar() == ')') {
            count = 0;
        } else {
            while (pos.next()) {
                char c = pos.getChar();
                if (c == '(')
                    ++count;
                else if (c == ')')
                    --count;
                if (count == 0)
                    break;
            }
        }
        if (count == 0) {
            editor.getBuffer().withWriteLock(() -> {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.beginMotion();
                editor.getDot().moveTo(pos);
                if (editor.getDotLine().substring(0, editor.getDotOffset()).isBlank()) {
                    IndentCommands.justOneSpace(editor);
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.getDot().skip(-1);
                    EditCommands.deleteNormalChar(editor);
                }
                editor.addUndo(SimpleEdit.MOVE);
                editor.getDot().next();
                EditCommands.newlineAndIndent(editor);
                editor.endCompoundEdit(compoundEdit);
            });
        }
    }

    public static void electricQuote(Editor editor) {
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        if (editor.getDotChar() == '"') {
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().skip(1);
            EditCommands.newlineAndIndent(editor);
        } else {
            editor.fillToCaret();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal("\"\"");
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().skip(-1);
        }
        editor.moveCaretToDotCol();
        editor.endCompoundEdit(compoundEdit);
    }
}
