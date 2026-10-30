/*
 * MotionCommands.java
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

import java.awt.AWTEvent;
import java.awt.event.MouseEvent;
import javax.swing.undo.CompoundEdit;

/** Caret motion by character, word, line, page and buffer, with and without selecting. */
public final class MotionCommands {
    private MotionCommands() {}

    public static void toCenter(Editor editor) {
        editor.getDisplay().toCenter();
    }

    /** With "start", the caret to its line's first non-blank too. */
    public static void toCenter(Editor editor, String arg) {
        if (placeCaret(editor, arg, null))
            toCenter(editor);
    }

    public static void toBottom(Editor editor) {
        editor.getDisplay().toBottom();
    }

    /**
     * With "start", the caret to its line's first non-blank too; with
     * "previous", to the line over the window first.
     */
    public static void toBottom(Editor editor, String arg) {
        if (placeCaret(editor, arg, "previous"))
            toBottom(editor);
    }

    public static void toTop(Editor editor) {
        editor.getDisplay().toTop();
    }

    /**
     * With "start", the caret to its line's first non-blank too; with
     * "next", to the line under the window first.
     */
    public static void toTop(Editor editor, String arg) {
        if (placeCaret(editor, arg, "next"))
            toTop(editor);
    }

    // The caret for toTop, toCenter or toBottom with arg: with "start" at
    // its line's first non-blank, with past at the first non-blank of the
    // line past the window's edge; none for no arg; false, saying so, for
    // another.
    private static boolean placeCaret(Editor editor, String arg, String past) {
        if (editor.getDot() == null)
            return false;
        final String a = arg == null ? "" : arg.strip();
        if (a.isEmpty())
            return true;
        Line line = editor.getDotLine();
        if (a.equals("next") && a.equals(past)) {
            final Line next = editor.getDisplay().getBottomLine().nextVisible();
            line = next != null ? next : line;
        } else if (a.equals("previous") && a.equals(past)) {
            final Line previous = editor.getDisplay().getFirstWholeLine().previousVisible();
            line = previous != null ? previous : line;
        } else if (!a.equals("start")) {
            editor.status(past == null ? "Takes start" : "Takes start or " + past);
            return false;
        }
        editor.addUndo(SimpleEdit.MOVE);
        editor.moveDotTo(line, CaretCommands.firstNonBlank(line));
        editor.moveCaretToDotCol();
        return true;
    }

    static boolean nextChar(Editor editor) {
        if (editor.getDotOffset() < editor.getDotLine().length()) {
            editor.getDot().skip(1);
            return true;
        }
        if (editor.getDotLine().next() != null) {
            editor.getDot().moveTo(editor.getDotLine().next(), 0);
            return true;
        }
        return false;
    }

    static boolean prevChar(Editor editor) {
        if (editor.getDotOffset() > 0) {
            editor.getDot().skip(-1);
            return true;
        }
        final Line previous = editor.getDotLine().previous();
        if (previous != null) {
            editor.getDot().moveTo(previous, previous.length());
            return true;
        }
        return false;
    }

    public static void pageDown(Editor editor) {
        if (editor.getDot() == null)
            return;
        editor.maybeResetGoalColumn();
        if (editor.getMark() != null) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMark(null);
            editor.setUpdateFlag(REPAINT);
            pageDownInternal(editor);
            editor.endCompoundEdit(compoundEdit);
        } else
            pageDownInternal(editor);
        editor.setCurrentCommand(COMMAND_PAGE_DOWN);
    }

    public static void pageDownOtherWindow(Editor editor) {
        final Editor ed = editor.getOtherEditor();
        if (ed != null) {
            pageDown(ed);
            ed.updateDisplay();
        }
    }

    public static void selectPageDown(Editor editor) {
        if (editor.getDot() == null)
            return;
        editor.maybeResetGoalColumn();
        if (editor.getMark() == null) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMarkAtDot();
            pageDownInternal(editor);
            editor.endCompoundEdit(compoundEdit);
        } else
            pageDownInternal(editor);
        editor.setCurrentCommand(COMMAND_PAGE_DOWN);
    }

    /** {@code pageDown vim} scrolls the way vim's CTRL-F does. */
    public static void pageDown(Editor editor, String parameters) {
        if (wantsVim(parameters))
            vimPage(editor, true);
        else
            pageDown(editor);
    }

    /** {@code pageUp vim} scrolls the way vim's CTRL-B does. */
    public static void pageUp(Editor editor, String parameters) {
        if (wantsVim(parameters))
            vimPage(editor, false);
        else
            pageUp(editor);
    }

    /**
     * Vim's CTRL-F and CTRL-B, which differ from pageDown and pageUp in three
     * ways, all checked with nvim: they keep two lines of the old page on
     * screen rather than one; the caret goes to the new top line (CTRL-F) or
     * the new bottom line (CTRL-B) rather than keeping its row; and CTRL-F
     * with the last line already showing puts it at the top. Too much to
     * change what PageDown does for everyone, so it is an argument. Pages are
     * the display's, measured as drawn, so that a line taller than a row, or
     * rows of virtual text, never leave the caret off the window.
     *
     * @return false when there is nowhere further to scroll
     */
    static boolean vimPage(Editor editor, boolean forward) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return false;
        final Line top = display.getTopLine();
        if (top == null)
            return false;
        final Line bottom = display.getBottomLine();
        Line newTop;
        if (forward) {
            if (bottom.nextVisible() == null) {
                // The last line is showing: put it at the top, or do nothing
                // if it is there already.
                if (top == bottom)
                    return false;
                newTop = bottom;
            } else {
                // The old page's last two lines at the top, a line on at least.
                newTop = bottom.previousVisible();
                if (newTop == null || !before(top, newTop, display.getRows() + 1))
                    newTop = top.nextVisible();
            }
        } else {
            if (top.previousVisible() == null)
                return false;
            // The old page's first two lines at the bottom, a line back at least.
            final Line second = top.nextVisible();
            newTop = display.topFor(second != null && second != bottom ? second : top);
            if (!before(newTop, top, display.getRows() + 1))
                newTop = top.previousVisible();
        }
        display.setTopLine(newTop);
        final Line caret = forward ? newTop : display.getBottomLine();
        // The caret keeps its column, as nvim's default 'nostartofline'
        // keeps it -- the column it is at now, not j's goal column, which
        // only j's own up and down set. A selection is left alone, so that
        // in visual mode the page extends it.
        final int col = editor.getDotCol();
        editor.addUndo(SimpleEdit.MOVE);
        editor.updateDotLine();
        editor.getDot().moveTo(caret, 0);
        editor.getDot().moveToCol(col, editor.getBuffer().getTabWidth());
        if (editor.getDot().getOffset() > caret.length())
            editor.getDot().setOffset(caret.length());
        editor.moveCaretToDotCol();
        editor.updateDotLine();
        editor.setUpdateFlag(REPAINT);
        return true;
    }

    // Whether line is before other, at most limit lines shown on: by the
    // lines themselves, as a new line's number may not be set yet.
    private static boolean before(Line line, Line other, int limit) {
        Line l = line.nextVisible();
        for (int i = 0; l != null && i < limit; i++, l = l.nextVisible())
            if (l == other)
                return true;
        return false;
    }

    public static void pageUp(Editor editor) {
        if (editor.getDot() == null)
            return;
        editor.maybeResetGoalColumn();
        if (editor.getMark() != null) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMark(null);
            editor.setUpdateFlag(REPAINT);
            pageUpInternal(editor);
            editor.endCompoundEdit(compoundEdit);
        } else
            pageUpInternal(editor);
        editor.setCurrentCommand(COMMAND_PAGE_UP);
    }

    public static void pageUpOtherWindow(Editor editor) {
        final Editor ed = editor.getOtherEditor();
        if (ed != null) {
            pageUp(ed);
            ed.updateDisplay();
        }
    }

    public static void selectPageUp(Editor editor) {
        if (editor.getDot() == null)
            return;
        editor.maybeResetGoalColumn();
        if (editor.getMark() == null) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMarkAtDot();
            pageUpInternal(editor);
            editor.endCompoundEdit(compoundEdit);
        } else
            pageUpInternal(editor);
        editor.setCurrentCommand(COMMAND_PAGE_UP);
    }

    private static void pageDownInternal(Editor editor) {
        final Display display = editor.getDisplay();
        Debug.assertTrue(editor.getBuffer().needsRenumbering == false);
        Line dotLine = editor.getDotLine();
        // The window's lines by their text, so rows of virtual text count.
        Line bottomLine = display.getBottomLine();
        int dotRow = -1;
        int row = 0;
        for (Line l = editor.getTopLine(); l != null; l = l.nextVisible(), row++) {
            if (l == dotLine)
                dotRow = row;
            if (l == bottomLine)
                break;
        }
        Line line;
        if (bottomLine.nextVisible() == null) {
            // We're on the last page already.
            if (dotRow >= 0) {
                Position eob = editor.getEob();
                if (eob != null) {
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.updateDotLine();
                    editor.getDot().setLine(eob.getLine());
                    editor.moveDotToGoalCol();
                    editor.updateDotLine();
                }
            }
            return;
        }
        // Not on last page.
        if (bottomLine == editor.getTopLine())
            bottomLine = bottomLine.nextVisible(); // One line taller than the window.
        display.setTopLine(bottomLine);
        editor.setUpdateFlag(REPAINT);
        if (dotRow >= 0) {
            line = editor.getTopLine();
            for (int i = 0; i < dotRow; i++) {
                Line next = line.nextVisible();
                if (next == null)
                    break;
                line = next;
            }
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().setLine(line);
            editor.moveDotToGoalCol();
        }
    }

    private static void pageUpInternal(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot().getLine() == editor.getBuffer().getFirstLine())
            return;
        Debug.assertTrue(editor.getBuffer().needsRenumbering == false);
        int topLineNumber = display.getTopLineNumber();
        int dotLineNumber = editor.getDot().lineNumber();
        // As far as puts the top line at the bottom, rows of virtual text and all.
        final Line newTop = display.pageAbove();
        int linesToScroll = 0;
        for (Line l = editor.getTopLine(); l != null && l != newTop; l = l.previousVisible())
            linesToScroll++;
        if (linesToScroll == 0)
            linesToScroll = display.getRows() - 1;
        boolean dotLineIsVisible = false;
        if (dotLineNumber >= topLineNumber) {
            Line bottomLine = display.getBottomLine();
            if (bottomLine != null)
                if (dotLineNumber <= bottomLine.lineNumber())
                    dotLineIsVisible = true;
        }
        editor.addUndo(SimpleEdit.MOVE);
        if (dotLineIsVisible) {
            for (int i = 0; i < linesToScroll; i++) {
                Line topLinePrevious = editor.getTopLine().previousVisible();
                if (topLinePrevious != null)
                    display.setTopLine(topLinePrevious);
                Line dotLinePrevious = editor.getDot().getLine().previousVisible();
                if (dotLinePrevious == null)
                    break;
                editor.getDot().setLine(dotLinePrevious);
            }
        } else {
            for (int i = 0; i < linesToScroll; i++) {
                Line dotLinePrevious = editor.getDot().getLine().previousVisible();
                if (dotLinePrevious == null)
                    break;
                editor.getDot().setLine(dotLinePrevious);
            }
        }
        editor.moveDotToGoalCol();
        editor.setUpdateFlag(REPAINT);
    }

    // Move dot to beginning of block, no undo.
    public static void beginningOfBlock(Editor editor) {
        if (editor.getMark() != null) {
            Region r = new Region(editor.getBuffer(), editor.getMark(), editor.getDot());
            editor.getDot().moveTo(r.getBegin());
            editor.setMark(null);
            editor.moveCaretToDotCol();
            if (r.getBeginLine() != r.getEndLine())
                editor.setUpdateFlag(REPAINT);
            else
                editor.updateDotLine();
        }
    }

    // Move dot to end of block, no undo.
    public static void endOfBlock(Editor editor) {
        if (editor.getMark() != null) {
            Region r = new Region(editor.getBuffer(), editor.getMark(), editor.getDot());
            editor.getDot().moveTo(r.getEnd());
            editor.setMark(null);
            editor.moveCaretToDotCol();
            if (r.getBeginLine() != r.getEndLine())
                editor.setUpdateFlag(REPAINT);
            else
                editor.updateDotLine();
        }
    }

    public static void right(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        if (editor.getBuffer().getBooleanProperty(Property.RESTRICT_CARET)) {
            if (editor.getMark() == null && editor.getDotOffset() >= editor.getDotLine().length()) {
                // Caret is at end of line.
                if (editor.getDotLine().next() != null) {
                    editor.moveDotTo(editor.getDotLine().next(), 0);
                    editor.setCurrentCommand(COMMAND_RIGHT);
                }
                return;
            }
        }
        if (editor.getMark() != null || editor.getLastCommand() != COMMAND_RIGHT)
            editor.addUndo(SimpleEdit.MOVE);
        if (editor.getMark() != null)
            endOfBlock(editor);
        else if (editor.getDot().getOffset() < editor.getDot().getLineLength()) {
            editor.getDot().skip(1);
            editor.moveCaretToDotCol();
        } else {
            display.setCaretCol(display.getCaretCol() + 1);
        }
        editor.updateDotLine();
        editor.setCurrentCommand(COMMAND_RIGHT);
        editor.setUpdateFlag(REFRAME);
    }

    public static void selectRight(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        if (editor.getDotOffset() < editor.getDotLine().length()) {
            if (editor.getMark() == null || editor.getLastCommand() != COMMAND_RIGHT)
                editor.beginSelectMotion();
            editor.getDot().moveRight();
            editor.moveCaretToDotCol();
        } else {
            // We're at or beyond the end of the line.
            if (editor.getBuffer().getBooleanProperty(Property.RESTRICT_CARET)) {
                if (editor.getDotLine().next() == null)
                    return;
                if (editor.getMark() == null || editor.getLastCommand() != COMMAND_RIGHT)
                    editor.beginSelectMotion();
                editor.updateDotLine();
                editor.getDot().moveTo(editor.getDotLine().next(), 0);
                editor.moveCaretToDotCol();
            } else {
                // Don't start a new selection, since there's no text there.
                if (editor.getLastCommand() != COMMAND_RIGHT)
                    editor.addUndo(SimpleEdit.MOVE);
                display.setCaretCol(display.getCaretCol() + 1);
            }
        }
        editor.updateDotLine();
        editor.setCurrentCommand(COMMAND_RIGHT);
        editor.setUpdateFlag(REFRAME);
    }

    public static void left(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();
        final Line prevLine = dotLine.previous();
        if (dotOffset == 0 && prevLine == null)
            return;
        if (editor.getMark() != null || editor.getLastCommand() != COMMAND_LEFT)
            editor.addUndo(SimpleEdit.MOVE);
        if (editor.getMark() != null)
            beginningOfBlock(editor);
        else {
            int absCaretCol = display.getCaretCol() + display.getShift();
            if (absCaretCol > 0 && absCaretCol <= editor.getBuffer().getCol(dotLine, dotLine.length())) {
                // Back up one character.
                editor.getDot().setOffset(dotOffset - 1);
                editor.moveCaretToDotCol();
            } else if (absCaretCol > 0) {
                // We're beyond the end of the text on the line.
                display.setCaretCol(display.getCaretCol() - 1);
            } else if (editor.getDot().getOffset() == 0) {
                // Back up to the end of the text on the previous line.
                editor.update(dotLine);
                editor.setDot(prevLine, prevLine.length());
                editor.moveCaretToDotCol();
            } else {
                // There shouldn't be any other cases.
                Debug.assertTrue(false);
            }
        }
        editor.updateDotLine();
        editor.setCurrentCommand(COMMAND_LEFT);
        editor.setUpdateFlag(REFRAME);
    }

    public static void selectLeft(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();
        final Line prevLine = dotLine.previous();
        if (dotOffset == 0 && prevLine == null)
            return;
        if (editor.getMark() == null || editor.getLastCommand() != COMMAND_LEFT)
            editor.addUndo(SimpleEdit.MOVE);

        // Only start a new selection if we're over some actual text.
        final int absCaretCol = display.getAbsoluteCaretCol();
        final int end = editor.getBuffer().getCol(dotLine, dotLine.length());
        if (editor.getMark() == null && absCaretCol <= end)
            editor.setMarkAtDot();

        if (absCaretCol > 0 && absCaretCol <= end) {
            // Back up one character.
            editor.getDot().moveLeft();
            editor.moveCaretToDotCol();
        } else if (absCaretCol > 0) {
            // We're beyond the end of the text on the line.
            display.setCaretCol(display.getCaretCol() - 1);
        } else if (dotOffset == 0) {
            // Back up to the end of the text on the previous line.
            editor.updateDotLine();
            editor.setDot(prevLine, prevLine.length());
            editor.moveCaretToDotCol();
        } else {
            // There shouldn't be any other cases.
            Debug.assertTrue(false);
        }
        editor.updateDotLine();
        editor.setCurrentCommand(COMMAND_LEFT);
        editor.setUpdateFlag(REFRAME);
    }

    // Move caret down one line, keeping it in the same column if possible.
    // Synchronize dot with caret.
    public static void down(Editor editor) {
        editor.maybeResetGoalColumn();
        editor.getDisplay().down(false);
        editor.setCurrentCommand(COMMAND_DOWN);
    }

    public static void selectDown(Editor editor) {
        editor.maybeResetGoalColumn();
        editor.getDisplay().down(true);
        editor.setCurrentCommand(COMMAND_DOWN);
    }

    // Move caret up one line, keeping it in the same column if possible.
    // Synchronize dot with caret.
    public static void up(Editor editor) {
        editor.maybeResetGoalColumn();
        editor.getDisplay().up(false);
        editor.setCurrentCommand(COMMAND_UP);
    }

    public static void selectUp(Editor editor) {
        editor.maybeResetGoalColumn();
        editor.getDisplay().up(true);
        editor.setCurrentCommand(COMMAND_UP);
    }

    public static void windowUp(Editor editor) {
        editor.maybeResetGoalColumn();
        editor.getDisplay().windowUp();
        editor.maybeScrollCaret();
        editor.setCurrentCommand(COMMAND_WINDOW_UP);
    }

    public static void windowDown(Editor editor) {
        editor.maybeResetGoalColumn();
        editor.getDisplay().windowDown();
        editor.maybeScrollCaret();
        editor.setCurrentCommand(COMMAND_WINDOW_DOWN);
    }

    private static void selectToPosition(Editor editor, Position pos) {
        if (!pos.equals(editor.getDot())
                || editor.getBuffer().getCol(pos) != editor.getDisplay().getAbsoluteCaretCol()) {
            editor.beginSelectMotion();
            if (pos.getLine() != editor.getDotLine())
                editor.setUpdateFlag(REPAINT);
            else
                editor.updateDotLine();
            editor.getDot().moveTo(pos);
            editor.moveCaretToDotCol();
        }
    }

    public static void bol(Editor editor) {
        if (editor.getDot() != null)
            editor.moveDotTo(editor.getDot().getLine(), 0);
    }

    public static void home(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        final boolean extend = Editor.preferences().getBooleanProperty(Property.EXTEND_HOME);
        Position pos;
        if (editor.getMark() != null)
            pos = new Position(new Region(editor).getBegin());
        else
            pos = new Position(editor.getDot());
        int indent = pos.getLine().getIndentation();
        if (extend) {
            if (pos.getOffset() > indent)
                pos.setOffset(indent);
            else
                pos.setOffset(0);
        } else {
            if (pos.getOffset() > indent)
                pos.setOffset(indent);
            else if (pos.getOffset() == indent)
                pos.setOffset(0);
            else
                pos.setOffset(indent);
        }
        if (editor.getMark() != null
                || !pos.equals(editor.getDot())
                || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol()) {
            editor.moveDotTo(pos);
            editor.setCurrentCommand(COMMAND_HOME);
            return;
        }
        // Reaching here, caret is already in column 0.
        if (!extend)
            return;
        if (System.currentTimeMillis() - Dispatcher.getLastEventMillis() > 1000) {
            // Timed out.
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_HOME);
            return;
        }
        if (editor.getLastCommand() == COMMAND_HOME_HOME)
            pos = new Position(editor.getBuffer().getFirstLine(), 0);
        else if (editor.getLastCommand() == COMMAND_HOME) {
            pos = new Position(editor.getTopLine(), 0);
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_HOME_HOME);
        } else {
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_HOME);
            return;
        }
        if (!pos.equals(editor.getDot()) || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol())
            editor.moveDotTo(pos);
    }

    public static void selectHome(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        final boolean extend = Editor.preferences().getBooleanProperty(Property.EXTEND_HOME);
        Position pos;
        if (editor.getMark() != null)
            pos = new Position(new Region(editor.getBuffer(), editor.getMark(), editor.getDot()).getBegin());
        else
            pos = new Position(editor.getDot());
        int indent = pos.getLine().getIndentation();
        if (extend) {
            if (pos.getOffset() > indent)
                pos.setOffset(indent);
            else
                pos.setOffset(0);
        } else {
            if (pos.getOffset() > indent)
                pos.setOffset(indent);
            else if (pos.getOffset() == indent)
                pos.setOffset(0);
            else
                pos.setOffset(indent);
        }
        if (editor.getMark() != null
                || !pos.equals(editor.getDot())
                || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol()) {
            selectToPosition(editor, pos);
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_SELECT_HOME);
            return;
        }
        // Reaching here, caret is already in column 0.
        if (!Editor.preferences().getBooleanProperty(Property.EXTEND_HOME))
            return;
        if (System.currentTimeMillis() - Dispatcher.getLastEventMillis() > 1000) {
            // Timed out.
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_SELECT_HOME);
            return;
        }
        if (editor.getLastCommand() == COMMAND_SELECT_HOME_HOME)
            pos = new Position(editor.getBuffer().getFirstLine(), 0);
        else if (editor.getLastCommand() == COMMAND_SELECT_HOME) {
            pos = new Position(editor.getTopLine(), 0);
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_SELECT_HOME_HOME);
        } else {
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_SELECT_HOME);
            return;
        }
        if (!pos.equals(editor.getDot()) || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol())
            selectToPosition(editor, pos);
    }

    public static void eol(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        if (editor.getMark() != null
                || editor.getDot().getOffset() != editor.getDot().getLineLength()
                || editor.getBuffer().getCol(editor.getDot()) != display.getCaretCol() + display.getShift())
            editor.moveDotTo(editor.getDot().getLine(), editor.getDot().getLineLength());
    }

    public static void end(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        Position pos;
        if (editor.getMark() != null)
            pos = new Position(new Region(editor.getBuffer(), editor.getMark(), editor.getDot()).getEnd());
        else
            pos = new Position(editor.getDot());
        pos.setOffset(pos.getLineLength());
        if (editor.getMark() != null
                || !pos.equals(editor.getDot())
                || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol()) {
            editor.moveDotTo(pos);
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END);
            return;
        }
        // Reaching here, caret is already at end of line.
        if (!Editor.preferences().getBooleanProperty(Property.EXTEND_END))
            return;
        if (System.currentTimeMillis() - Dispatcher.getLastEventMillis() > 1000) {
            // Timed out.
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END);
            return;
        }
        if (editor.getLastCommand() == COMMAND_END_END)
            pos = editor.getEob();
        else if (editor.getLastCommand() == COMMAND_END) {
            Line bottomLine = display.getBottomLine();
            if (bottomLine != null)
                pos = new Position(bottomLine, bottomLine.length());
            else
                pos = editor.getEob();
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END_END);
        } else {
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END);
            return;
        }
        if (!pos.equals(editor.getDot()) || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol())
            editor.moveDotTo(pos);
    }

    public static void selectEnd(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        Position pos;
        if (editor.getMark() != null)
            pos = new Position(new Region(editor.getBuffer(), editor.getMark(), editor.getDot()).getEnd());
        else
            pos = new Position(editor.getDot());
        pos.setOffset(pos.getLineLength());
        if (!pos.equals(editor.getDot()) || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol()) {
            selectToPosition(editor, pos);
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END);
            return;
        }
        // Reaching here, caret is already at end of line.
        if (!Editor.preferences().getBooleanProperty(Property.EXTEND_END))
            return;
        if (System.currentTimeMillis() - Dispatcher.getLastEventMillis() > 1000) {
            // Timed out.
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END);
            return;
        }
        if (editor.getLastCommand() == COMMAND_END_END)
            pos = editor.getEob();
        else if (editor.getLastCommand() == COMMAND_END) {
            Line bottomLine = display.getBottomLine();
            if (bottomLine != null)
                pos = new Position(bottomLine, bottomLine.length());
            else
                pos = editor.getEob();
            editor.setUpdateFlag(REFRAME);
            editor.setCurrentCommand(COMMAND_END_END);
        } else {
            editor.setCurrentCommand(COMMAND_END);
            return;
        }
        if (!pos.equals(editor.getDot()) || editor.getBuffer().getCol(pos) != display.getAbsoluteCaretCol())
            selectToPosition(editor, pos);
    }

    public static void bob(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (buffer.getFirstLine() != null) {
            editor.recordJump();
            editor.moveDotTo(buffer.jumpTarget(buffer.getFirstLine()), 0);
        }
    }

    public static void selectBob(Editor editor) {
        if (editor.getDot() == null)
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer.getFirstLine() == null)
            return;
        editor.beginSelectMotion();
        editor.getDot().moveTo(buffer.jumpTarget(buffer.getFirstLine()), 0);
        editor.moveCaretToDotCol();
        editor.setUpdateFlag(REPAINT);
    }

    public static void eob(Editor editor) {
        editor.recordJump();
        editor.moveDotTo(end(editor.getBuffer()));
    }

    public static void selectEob(Editor editor) {
        if (editor.getBuffer().getFirstLine() == null)
            return;
        editor.beginSelectMotion();
        editor.getDot().moveTo(end(editor.getBuffer()));
        editor.moveCaretToDotCol();
        editor.setUpdateFlag(REPAINT);
    }

    // The buffer's end, or the end of the line a jump to its last line lands on.
    private static Position end(Buffer buffer) {
        final Position end = buffer.getEnd();
        if (end == null)
            return null;
        final Line line = buffer.jumpTarget(end.getLine());
        return line == end.getLine() ? end : new Position(line, line.length());
    }

    public static void top(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (editor.getDotLine() != editor.getTopLine()) {
            editor.addUndo(SimpleEdit.MOVE);
            editor.updateDotLine();
            editor.getDot().setLine(editor.getTopLine());
            editor.moveDotToCaretCol();
        }
    }

    public static void bottom(Editor editor) {
        if (editor.getDot() == null)
            return;
        Line line = editor.getDisplay().getBottomLine();
        if (line != editor.getDotLine()) {
            editor.addUndo(SimpleEdit.MOVE);
            editor.updateDotLine();
            editor.getDot().setLine(line);
            editor.updateDotLine();
            editor.moveDotToCaretCol();
        }
    }

    public static void selectWord(Editor editor) {
        if (editor.getDot() == null)
            return;
        AWTEvent e = editor.getDispatcher().getLastEvent();
        CompoundEdit compoundEdit = null;
        if (e instanceof MouseEvent mouseEvent) {
            compoundEdit = editor.beginCompoundEdit();
            editor.mouseMoveDotToPoint(mouseEvent);
        }
        if (inWord(editor)) {
            editor.addUndo(SimpleEdit.MOVE);
            while (editor.getDotOffset() > 0) {
                editor.getDot().moveLeft();
                if (!inWord(editor)) {
                    editor.getDot().moveRight();
                    break;
                }
            }
            editor.setMarkAtDot();
            final int limit = editor.getDotLine().length();
            while (inWord(editor) && editor.getDotOffset() < limit)
                editor.getDot().moveRight();
            editor.moveCaretToDotCol();
        }
        if (compoundEdit != null)
            editor.endCompoundEdit(compoundEdit);
    }

    static boolean inWord(Editor editor) {
        return editor.getMode().isIdentifierPart(editor.getDotChar());
    }

    static boolean inWhitespace(Editor editor) {
        return Character.isWhitespace(editor.getDotChar());
    }

    private static void skipWhitespace(Editor editor) {
        while (inWhitespace(editor))
            if (!nextChar(editor))
                break;
    }

    private static void nextWord(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (inWord(editor)) {
            while (nextChar(editor))
                if (!inWord(editor))
                    break;
            skipWhitespace(editor);
        } else if (inWhitespace(editor)) {
            skipWhitespace(editor);
        } else {
            // Not in word or whitespace.
            while (nextChar(editor) && !inWord(editor) && !inWhitespace(editor))
                ;
            skipWhitespace(editor);
        }
    }

    private static void prevWord(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (!prevChar(editor))
            return;
        if (inWord(editor)) {
            while (prevChar(editor) && inWord(editor))
                ;
            if (!inWord(editor))
                nextChar(editor);
        } else if (inWhitespace(editor)) {
            while (prevChar(editor) && inWhitespace(editor))
                ;
            if (inWord(editor)) {
                while (prevChar(editor) && inWord(editor))
                    ;
                if (!inWord(editor))
                    nextChar(editor);
            } else {
                while (prevChar(editor) && !inWord(editor) && !inWhitespace(editor))
                    ;
                if (inWord(editor) || inWhitespace(editor))
                    nextChar(editor);
            }
        } else {
            // Not in word or whitespace.
            while (prevChar(editor)) {
                if (inWord(editor))
                    break;
                if (inWhitespace(editor))
                    break;
            }
            if (inWord(editor) || inWhitespace(editor))
                nextChar(editor);
        }
    }

    /**
     * Which rule a word motion follows.
     *
     * j's own is a block of text and then the run of blanks after it, so a
     * word ends at punctuation only when there is whitespace there.
     * <a href="editmodes.html">Vim's</a> sorts characters into keyword,
     * other non-blank and blank, and a word is a run of one class, so
     * {@code foo.bar} is three words. Neither is wrong; they suit different
     * habits, and vim's is finer grained.
     *
     * @param parameters "vim" for vim's rule, anything else for j's
     */
    static boolean wantsVim(String parameters) {
        return parameters != null && parameters.trim().equalsIgnoreCase("vim");
    }

    public static void wordRight(Editor editor) {
        wordRight(editor, null);
    }

    /** {@code wordRight vim} moves the way vim's {@code w} does. */
    public static void wordRight(Editor editor, String parameters) {
        if (editor.getDot() == null)
            return;
        editor.updateDotLine();
        editor.addUndo(SimpleEdit.MOVE);
        if (wantsVim(parameters)) {
            final Position to = Words.forwardToWordStart(new Position(editor.getDot()), editor.getMode(), false);
            if (to != null)
                editor.getDot().moveTo(to);
        } else {
            endOfBlock(editor);
            nextWord(editor);
        }
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    public static void wordLeft(Editor editor) {
        wordLeft(editor, null);
    }

    /** {@code wordLeft vim} moves the way vim's {@code b} does. */
    public static void wordLeft(Editor editor, String parameters) {
        if (editor.getDot() == null)
            return;
        editor.updateDotLine();
        editor.addUndo(SimpleEdit.MOVE);
        if (wantsVim(parameters)) {
            final Position to = Words.backwardToWordStart(new Position(editor.getDot()), editor.getMode(), false);
            if (to != null)
                editor.getDot().moveTo(to);
        } else {
            beginningOfBlock(editor);
            prevWord(editor);
        }
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    public static void selectWordRight(Editor editor) {
        selectWordRight(editor, null);
    }

    /** {@code selectWordRight vim} extends by vim's {@code w}. */
    public static void selectWordRight(Editor editor, String parameters) {
        if (editor.getDot() == null)
            return;
        editor.beginSelectMotion();
        editor.updateDotLine();
        if (wantsVim(parameters)) {
            final Position to = Words.forwardToWordStart(new Position(editor.getDot()), editor.getMode(), false);
            if (to != null)
                editor.getDot().moveTo(to);
        } else
            nextWord(editor);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    public static void selectWordLeft(Editor editor) {
        selectWordLeft(editor, null);
    }

    /** {@code selectWordLeft vim} extends by vim's {@code b}. */
    public static void selectWordLeft(Editor editor, String parameters) {
        if (editor.getDot() == null)
            return;
        editor.beginSelectMotion();
        editor.updateDotLine();
        if (wantsVim(parameters)) {
            final Position to = Words.backwardToWordStart(new Position(editor.getDot()), editor.getMode(), false);
            if (to != null)
                editor.getDot().moveTo(to);
        } else
            prevWord(editor);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }

    public static void selectAll(Editor editor) {
        final Display display = editor.getDisplay();
        if (editor.getDot() == null)
            return;
        editor.recordJump();
        editor.beginMotion();
        Line line = editor.getBuffer().getFirstLine();
        editor.getDot().moveTo(line, 0);
        display.setCaretCol(0);
        display.setShift(0);
        editor.setMarkAtDot();
        while (line.next() != null)
            line = line.next();
        editor.getDot().moveTo(line, line.length());
        editor.moveCaretToDotCol();
        display.setUpdateFlag(REPAINT);
    }
}
