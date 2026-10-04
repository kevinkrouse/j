/*
 * ClipboardCommands.java
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
import java.util.Arrays;
import java.util.List;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.util.Utilities;

/** Copying, killing and pasting, through the kill ring and the system clipboard. */
public final class ClipboardCommands {
    // The last column selection killed, for pasteColumn.
    private static String killedColumn;

    private ClipboardCommands() {}

    public static void copyPath(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer) {
            String path = ((DirectoryBuffer) editor.getBuffer()).getPathAtDot();
            if (path != null) {
                Editor.getKillRing().appendNew(path);
                Editor.getKillRing().copyLastKillToSystemClipboard();
                editor.status("Path copied to clipboard");
            }
        }
    }

    public static void copyRegion(Editor editor) {
        if (editor.getDot() == null)
            return;
        String message = null;
        if (editor.getMark() != null) {
            Region r = new Region(editor);
            if (editor.isColumnSelection()) {
                killedColumn = r.toString();
                message = "Column selection stored";
            } else {
                Editor.getKillRing().appendNew(r.toString());
                message = "Region copied to clipboard";
            }
        } else if (editor.getBuffer().getMark() != null) {
            Region r = new Region(editor.getBuffer(), editor.getDot(), editor.getBuffer().getMark());
            Editor.getKillRing().appendNew(r.toString());
            message = "Region copied to clipboard";
        } else if (!editor.getDotLine().isBlank()) {
            Editor.getKillRing().appendNew(editor.getDotLine().getText() + System.getProperty("line.separator"));
            message = "Line copied to clipboard";
        } else
            return; // Nothing to do.
        if (!editor.isColumnSelection())
            Editor.getKillRing().copyLastKillToSystemClipboard();
        if (message != null)
            editor.status(message);
    }

    public static void copyAppend(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }

        if (editor.getDot() == null)
            return;

        String message = null;

        if (editor.getMark() != null) {
            Region r = new Region(editor.getBuffer(), editor.getMark(), editor.getDot());
            Editor.getKillRing().appendToCurrent(r.toString());
            message = "Region appended to clipboard";
        } else if (!editor.getDotLine().isBlank()) {
            Editor.getKillRing().appendToCurrent(editor.getDotLine().getText() + System.getProperty("line.separator"));
            message = "Line appended to clipboard";
        } else
            return; // Nothing to do.

        Editor.getKillRing().copyLastKillToSystemClipboard();

        if (message != null)
            editor.status(message);
    }

    // This really is a kill!
    public static void killRegion(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        editor.getBuffer().withWriteLock(() -> {
            killRegionInternal(editor);
        });
    }

    private static void killRegionInternal(Editor editor) {
        if (editor.getMark() == null)
            editor.setMark(editor.getBuffer().getMark());
        if (editor.getMark() != null) {
            if (editor.getMarkLine() != editor.getDotLine() || editor.getMarkOffset() != editor.getDotOffset()) {
                // A hard update is only necessary if the region spans a line
                // boundary.
                boolean hard = editor.getDotLine() != editor.getMarkLine();
                if (editor.isColumnSelection()) {
                    Region r = new Region(editor);
                    killedColumn = r.toString();
                    editor.deleteColumn(r);
                } else {
                    Region r = new Region(editor);
                    String kill = r.toString();
                    if (editor.getLastCommand() == COMMAND_KILL)
                        Editor.getKillRing().appendToCurrent(kill);
                    else
                        Editor.getKillRing().appendNew(kill);
                    Editor.getKillRing().copyLastKillToSystemClipboard();

                    // Save undo information before calling Region.delete so
                    // modified flag will be correct if we revert.
                    CompoundEdit compoundEdit = editor.beginCompoundEdit();
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.getDot().moveTo(r.getBegin());
                    editor.addUndoDeleteRegion(r);

                    // Sets buffer modified flag.
                    r.delete();

                    editor.endCompoundEdit(compoundEdit);
                }
                editor.moveCaretToDotCol();
                if (hard)
                    editor.getBuffer().repaint();
                else
                    Editor.updateInAllEditors(editor.getDotLine());
                editor.setCurrentCommand(COMMAND_KILL);
            }
            editor.setMark(null);
        } else {
            // No selection.  Use current line.
            final Line dotLine = editor.getDotLine();
            final Line nextLine = dotLine.next();

            // Last line is a special case.
            if (nextLine == null) {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.getDot().setOffset(0);
                killLine(editor);
                editor.endCompoundEdit(compoundEdit);
                editor.setCurrentCommand(COMMAND_KILL);
                return;
            }

            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveTo(dotLine, 0);
            editor.setMark(new Position(nextLine, 0));

            Region r = new Region(editor);
            String kill = r.toString();
            if (editor.getLastCommand() == COMMAND_KILL)
                Editor.getKillRing().appendToCurrent(kill);
            else
                Editor.getKillRing().appendNew(kill);
            Editor.getKillRing().copyLastKillToSystemClipboard();

            // Save undo information before calling Region.delete so
            // modified flag will be correct if we revert.
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveTo(r.getBegin());
            editor.addUndoDeleteRegion(r);

            // Sets buffer modified flag.
            r.delete();

            editor.addUndo(SimpleEdit.MOVE);
            editor.setMark(null);
            editor.endCompoundEdit(compoundEdit);
            editor.moveCaretToDotCol();
            editor.getBuffer().repaint();
            editor.setCurrentCommand(COMMAND_KILL);
        }
    }

    public static void killAppend(Editor editor) {
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        editor.setLastCommand(COMMAND_KILL); // Force append.
        killRegion(editor);
    }

    // Copies text from dot to end of line to kill ring and then deletes that
    // text. If dot is already at end of line, deletes newline and copies it
    // to kill ring.
    public static void killLine(Editor editor) {
        if (!editor.checkReadOnly())
            return;

        if (editor.getDot().getOffset() == editor.getDot().getLineLength() && editor.getDot().getNextLine() == null)
            return;

        CompoundEdit compoundEdit = editor.beginCompoundEdit();

        editor.beginMotion();

        if (editor.getDotOffset() < editor.getDotLine().length()) {
            editor.setMarkAtDot();
            if (editor.getDot().getLine().isBlank() && editor.getDot().getNextLine() != null)
                editor.getDot().moveTo(editor.getDot().getNextLine(), 0);
            else
                editor.getDot().setOffset(editor.getDot().getLineLength());
        } else if (editor.getDot().getOffset() == editor.getDot().getLineLength()) {
            editor.fillToCaret(); // We might be beyond the end of the actual text on the line.
            editor.setMarkAtDot();
            editor.getDot().moveTo(editor.getDot().getNextLine(), 0);
        }

        killRegion(editor);

        editor.endCompoundEdit(compoundEdit);
    }

    public static void deleteWordRight(Editor editor) {
        deleteOrKillWordRight(editor, false);
    }

    public static void killWordRight(Editor editor) {
        deleteOrKillWordRight(editor, true);
    }

    private static void deleteOrKillWordRight(Editor editor, boolean isKill) {
        if (!editor.checkReadOnly())
            return;
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.beginMotion();
        editor.fillToCaret();
        editor.setMarkAtDot();
        if (MotionCommands.inWord(editor)) {
            while (MotionCommands.inWord(editor) && MotionCommands.nextChar(editor))
                ;
            while (MotionCommands.inWhitespace(editor) && MotionCommands.nextChar(editor))
                ;
        } else if (MotionCommands.inWhitespace(editor)) {
            while (MotionCommands.inWhitespace(editor) && MotionCommands.nextChar(editor))
                ;
        } else {
            while (
                !MotionCommands.inWhitespace(editor)
                    && !MotionCommands.inWord(editor)
                    && MotionCommands.nextChar(editor)
            )
                ;
            while (MotionCommands.inWhitespace(editor) && MotionCommands.nextChar(editor))
                ;
        }
        if (isKill)
            killRegion(editor);
        else
            editor.deleteRegion();
        editor.endCompoundEdit(compoundEdit);
    }

    public static void deleteWordLeft(Editor editor) {
        deleteOrKillWordLeft(editor, false);
    }

    public static void killWordLeft(Editor editor) {
        deleteOrKillWordLeft(editor, true);
    }

    private static void deleteOrKillWordLeft(Editor editor, boolean isKill) {
        if (!editor.checkReadOnly())
            return;
        if (editor.getDotOffset() == 0 && editor.getDotLine().previous() == null)
            return;
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.beginMotion();
        editor.setMarkAtDot();
        MotionCommands.prevChar(editor);
        if (MotionCommands.inWord(editor)) {
            while (editor.getDotOffset() > 0 && MotionCommands.inWord(editor) && MotionCommands.prevChar(editor))
                ;
            if (!MotionCommands.inWord(editor))
                MotionCommands.nextChar(editor);
        } else if (MotionCommands.inWhitespace(editor)) {
            while (MotionCommands.inWhitespace(editor) && MotionCommands.prevChar(editor))
                ;
            if (!MotionCommands.inWhitespace(editor))
                MotionCommands.nextChar(editor);
        } else {
            while (
                !MotionCommands.inWhitespace(editor)
                    && !MotionCommands.inWord(editor)
                    && MotionCommands.prevChar(editor)
            )
                ;
            while (MotionCommands.inWhitespace(editor) && MotionCommands.prevChar(editor))
                ;
            MotionCommands.nextChar(editor);
        }
        if (isKill)
            killRegion(editor);
        else
            editor.deleteRegion();
        editor.endCompoundEdit(compoundEdit);
    }

    public static boolean canPaste(Editor editor) {
        if (editor.getBuffer().isReadOnly())
            return false;
        if (Editor.getKillRing().size() > 0)
            return true;
        return KillRing.getText(KillRing.systemClipboard()) != null;
    }

    public static void paste(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        editor.setWaitCursor();
        Editor.getKillRing().takeClipboard();
        // Even if we already have the text to be inserted, we MUST call
        // killRing.pop() here so that killRing.indexOfNextPop and
        // killRing.lastPaste are set correctly.
        final String toBeInserted = Editor.getKillRing().pop();
        if (toBeInserted != null) {
            paste(editor, toBeInserted);
            editor.setCurrentCommand(COMMAND_PASTE);
        }
        editor.setDefaultCursor();
    }

    public static void cyclePaste(Editor editor) {
        if (editor.getLastCommand() == COMMAND_PASTE) {
            editor.setWaitCursor();
            String s = Editor.getKillRing().popNext();
            if (s != null) {
                EditCommands.undo(editor);
                paste(editor, s);
                editor.setCurrentCommand(COMMAND_PASTE);
            }
            editor.setDefaultCursor();
        } else
            paste(editor);
    }

    public static void mousePaste(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (!editor.checkReadOnly())
            return;
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        AWTEvent e = editor.getDispatcher().getLastEvent();
        if (!(e instanceof MouseEvent))
            return;
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        if (editor.getMark() != null) {
            Region r = new Region(editor);
            Editor.getKillRing().appendNew(r.toString());
            Editor.getKillRing().copyLastKillToSystemClipboard();
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMark(null);
        }
        editor.mouseMoveDotToPoint((MouseEvent) e);
        paste(editor);
        editor.endCompoundEdit(compoundEdit);
    }

    public static void promoteLastPaste() {
        Editor.getKillRing().promoteLastPaste();
    }

    public static void paste(Editor editor, String toBeInserted) {
        paste(editor, toBeInserted, false);
    }

    public static void paste(Editor editor, String toBeInserted, boolean leavePasteSelected) {
        if (!editor.checkReadOnly())
            return;
        if (toBeInserted == null || toBeInserted.length() == 0)
            return;
        if (!editor.getBuffer().withWriteLock(() -> {
            pasteInternal(editor, toBeInserted, leavePasteSelected);
        }))
            return;
        editor.setUpdateFlag(REFRAME);
    }

    private static void pasteInternal(Editor editor, String toBeInserted, boolean leavePasteSelected) {
        final Display display = editor.getDisplay();
        final Mode mode = editor.getBuffer().getMode();
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        if (
            editor.getMark() == null
                && Utilities.isLinePaste(toBeInserted)
                &&
                mode.acceptsLinePaste(editor)
                && editor.getBuffer().getBooleanProperty(Property.AUTO_PASTE_LINES)
        ) {
            // We want to the caret to be in the same column when we're done.
            final int absCaretCol = display.getAbsoluteCaretCol();

            final Line prevLine = editor.getDotLine().previous();

            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().setOffset(0);
            Position begin = editor.getDot().copy();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal(toBeInserted);

            if (prevLine != null && mode.canIndentPaste()) {
                // Indent inserted lines according to context.

                // Make sure line flags are correct.
                if (editor.getFormatter().parseBuffer())
                    editor.getBuffer().repaint();

                // Dot is at the beginning of the line following the inserted
                // block.
                Position savedDot = editor.getDot().copy();

                // First move dot to start of inserted block.
                editor.addUndo(SimpleEdit.MOVE);
                editor.getDot().moveTo(prevLine.next(), 0);

                while (editor.getDot().getLine() != null && editor.getDot().getLine() != savedDot.getLine()) {
                    if (!editor.getDot().getLine().isBlank())
                        IndentCommands.indentLineInternal(editor);
                    editor.addUndo(SimpleEdit.MOVE);
                    editor.getDot().moveTo(editor.getDot().getNextLine(), 0);
                }

                // Restore dot.
                editor.setDot(savedDot);
            }

            if (leavePasteSelected) {
                editor.setMark(begin);
                final Line dotLine = editor.getDotLine();
                for (Line line = begin.getLine(); line != null; line = line.nextVisible()) {
                    editor.update(line);
                    if (line == dotLine)
                        break;
                }
            } else {
                // Restore caret column.
                editor.addUndo(SimpleEdit.MOVE);
                display.setCaretCol(absCaretCol - display.getShift());
                editor.moveDotToCaretCol();
            }
        } else {
            if (editor.getMark() != null)
                editor.deleteRegion();
            editor.fillToCaret();
            Position begin = editor.getDot().copy();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal(toBeInserted);
            editor.moveCaretToDotCol();
            if (leavePasteSelected) {
                editor.setMark(begin);
                final Line dotLine = editor.getDotLine();
                for (Line line = begin.getLine(); line != null; line = line.nextVisible()) {
                    editor.update(line);
                    if (line == dotLine)
                        break;
                }
            }
        }
        editor.endCompoundEdit(compoundEdit);
        editor.getBuffer().modified();
        if (editor.getFormatter().parseBuffer())
            editor.getBuffer().repaint();
    }

    public static void pasteColumn(Editor editor) {
        if (!editor.checkReadOnly())
            return;
        if (killedColumn == null || killedColumn.length() == 0)
            return;
        editor.getBuffer().withWriteLock(() -> {
            pasteColumnInternal(editor, killedColumn);
        });
    }

    // A block at the caret's column, one piece a line, as Block.put does it;
    // the caret ends after the last piece.
    private static void pasteColumnInternal(Editor editor, String toBeInserted) {
        final int col = editor.getDisplay().getAbsoluteCaretCol();
        final List<String> pieces =
            Arrays.asList(toBeInserted.split("\n", -1));
        final Line top = editor.getDotLine();
        Block.put(editor, top, col, pieces);
        Line last = top;
        for (int i = 1; i < pieces.size() && last.next() != null; i++)
            last = last.next();
        final String piece = pieces.get(pieces.size() - 1);
        final int start = Block.positionAt(editor.getBuffer(), last, col).getOffset();
        editor.getDot().moveTo(last, Math.min(last.length(), start + piece.length()));
        editor.moveCaretToDotCol();
    }
}
