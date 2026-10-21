/*
 * ChangeMarks.java
 *
 * Copyright (C) 2002 Peter Graves
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.signs.HunkCommands;
import org.armedbear.j.vcs.signs.HunkPreview;
import org.armedbear.j.vcs.status.StatusActions;

public final class ChangeMarks {
    public static void nextChange(Editor editor) {
        if (editor.getDot() != null)
            byHunk(editor, true);
    }

    public static void previousChange(Editor editor) {
        if (editor.getDot() != null)
            byHunk(editor, false);
    }

    /**
     * The lines the buffer's hunks start on, in order: where nextChange and
     * previousChange go: the mode's, as a diff's hunks; else the version
     * control's, or the first lines of j's own runs of changed lines.
     */
    public static List<Line> hunkStarts(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        // Line numbers are stale after a split until renumbered.
        if (buffer.needsRenumbering())
            buffer.renumber();
        final List<Line> given = buffer.getChangeStarts();
        final List<Line> own = given != null ? given : editor.getMode().getChangeStarts(buffer);
        if (own != null)
            return own;
        final Signs signs = buffer.getSigns();
        final List<Line> starts = new ArrayList<>();
        if (signs == null) {
            if (buffer.isNewFile())
                return starts;
            for (Line l = buffer.getFirstLine(); l != null; l = l.next())
                if (l.isModified() && !l.isHidden() && (l.previous() == null || !l.previous().isModified()))
                    starts.add(l);
            return starts;
        }
        // The starts the buffer still has, in its order: one may be a line
        // since deleted.
        final Set<Line> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(signs.starts());
        for (Line l = buffer.getFirstLine(); l != null && starts.size() < wanted.size(); l = l.next())
            if (wanted.contains(l) && !l.isHidden())
                starts.add(l);
        return starts;
    }

    // To the first line of the next or previous hunk, around at the ends.
    private static void byHunk(Editor editor, boolean forward) {
        final List<Line> starts = hunkStarts(editor);
        if (starts.isEmpty()) {
            editor.status("No changes");
            return;
        }
        final int at = editor.getDotLineNumber();
        Line found = null;
        for (Line l : starts) {
            final int n = l.lineNumber();
            if (forward && n > at) {
                found = l;
                break;
            }
            if (!forward && n < at)
                found = l;
        }
        // Past the last or first, around to the other end.
        if (found == null) {
            found = forward ? starts.get(0) : starts.get(starts.size() - 1);
            editor.status(forward ? "Wrapped to the first change" : "Wrapped to the last change");
        }
        editor.moveDotTo(found, 0);
        // An open preview shows the hunk gone to in its place.
        if (HunkPreview.showing(editor)) {
            editor.replacingCaretPopup();
            previewChange(editor);
        }
    }

    // Whether the version control's hunks are the buffer's, to stage and reset.
    private static boolean tracked(Editor editor) {
        return editor.getBuffer().getSigns() != null && editor.getBuffer().getSignsUpdater() != null;
    }

    /**
     * Shows the change at the caret, its removed and added lines, in a
     * window under it until the next command.
     */
    public static void previewChange(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (tracked(editor)) {
            HunkCommands.previewHunk(editor);
            return;
        }
        final List<List<String>> sides = sinceLoaded(editor);
        if (sides == null) {
            HunkPreview.close(editor);
            editor.status("No change here");
            return;
        }
        // After the command, as the version control's shows: the editor
        // closes its caret popup when a command ends.
        final Buffer buffer = editor.getBuffer();
        final int modCount = buffer.getModCount();
        final Line line = editor.getDotLine();
        SwingUtilities.invokeLater(() -> {
            // Moved on meanwhile.
            if (editor.getBuffer() != buffer || buffer.getModCount() != modCount || editor.getDotLine() != line)
                return;
            HunkPreview.show(editor, false, false, sides.get(0), sides.get(1));
        });
    }

    // The run of changed lines at the caret, as loaded and as now; null if
    // the caret's line is unchanged.
    private static List<List<String>> sinceLoaded(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        Line first = editor.getDotLine();
        if (buffer.getSigns() != null || buffer.isNewFile() || !first.isModified())
            return null;
        while (first.previous() != null && first.previous().isModified())
            first = first.previous();
        final List<String> old = new ArrayList<>();
        final List<String> now = new ArrayList<>();
        for (Line l = first; l != null && l.isModified(); l = l.next()) {
            now.add(l.getText());
            // As revertLines puts it back: a new line goes, a changed one
            // gets its text, maybe several lines', back.
            if (!l.isNew() && l.getOriginalText() != null)
                old.addAll(List.of(l.getOriginalText().split("\n", -1)));
        }
        return List.of(old, now);
    }

    /** A click on a change mark: its change's preview, and a popup of what to do with it. */
    public static void changePopup(Editor editor) {
        if (editor.getDot() == null)
            return;
        previewChange(editor);
        TransientPopup.open(editor, popup(tracked(editor)));
    }

    /** What to do with a change: stage and unstage too where the version control tracks it. */
    public static Transient popup(boolean tracked) {
        final List<Command> change = new ArrayList<>();
        if (tracked) {
            change.add(Command.of("s", "vcsStageHunk", "stage"));
            change.add(Command.of("u", "vcsUnstageHunk", "unstage"));
        }
        change.add(Command.of("r", "revertLines", "revert"));
        return new Transient("change",
                "Change",
                "changes.html",
                List.of(
                    Group.of("Change", change.toArray(Command[]::new)),
                    Group.of(
                        "Go",
                        new Command("n", "nextChange", "next", true),
                        new Command("p", "previousChange", "previous", true))));
    }

    public static void revertLines(Editor editor) {
        final Position dot = editor.getDot(); // Alias.
        if (dot == null)
            return;
        // In the status's diff, the hunk or lines discarded, after asking.
        if (StatusActions.takesHunkCommands(editor)) {
            StatusActions.run(editor, StatusActions.Op.DISCARD);
            return;
        }
        // In a file the version control tracks, back as staged.
        if (editor.getBuffer().getSigns() != null && editor.getBuffer().getSignsUpdater() != null) {
            HunkCommands.resetHunk(editor);
            return;
        }
        if (editor.isColumnSelection()) {
            editor.notSupportedForColumnSelections();
            return;
        }
        final Position mark = editor.getMark(); // Alias.
        final Line dotLine = editor.getDotLine();
        Line before, last;
        if (mark != null) {
            Region r = new Region(editor);
            before = r.getBeginLine().previous();
            last = r.getEndLine();
        } else {
            before = dotLine.previous();
            last = dotLine;
        }
        // Find last unmodified line above dot or beginning of marked region.
        while (before != null && before.isModified())
            before = before.previous();
        // Find last modified line in current group of changed lines.
        for (Line line = last.next(); line != null; line = line.next()) {
            if (line.isModified())
                last = line;
            else
                break;
        }
        // Make sure at least one of the lines in question is in fact modified.
        boolean modified = false;
        for (Line line = last; line != before; line = line.previous()) {
            if (line.isModified()) {
                modified = true;
                break;
            }
        }
        if (!modified)
            return; // Nothing to revert.
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.addUndo(SimpleEdit.MOVE);
        if (mark != null) {
            editor.setMark(null);
            editor.setUpdateFlag(REPAINT);
        }
        dot.moveTo(last, 0);
        revertLine(editor, dot.getLine());
        while (dot.getPreviousLine() != before) {
            editor.addUndo(SimpleEdit.MOVE);
            dot.moveTo(dot.getPreviousLine(), 0);
            revertLine(editor, dot.getLine());
        }
        editor.moveCaretToDotCol();
        editor.endCompoundEdit(compoundEdit);
    }

    private static void revertLine(Editor editor, Line line) {
        if (!line.isModified())
            return;
        final Buffer buffer = editor.getBuffer();
        buffer.withWriteLock(() -> {
            if (line.isNew())
                revertNewLine(editor, line);
            else
                revertChangedLine(editor, line);
        });
    }

    private static void revertNewLine(Editor editor, final Line dotLine) {
        editor.adjustMarkers(dotLine);
        final Line prev = dotLine.previous();
        final Line next = dotLine.next();
        if (prev == null && next == null)
            return;
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        compoundEdit.addEdit(new UndoMove(editor));
        final Position dot = editor.getDot(); // Alias.
        boolean insertBefore;
        if (next != null) {
            dot.moveTo(next, 0);
            insertBefore = true;
        } else {
            dot.moveTo(prev, 0);
            insertBefore = false;
        }
        editor.moveCaretToDotCol();
        final Buffer buffer = editor.getBuffer();
        compoundEdit.addEdit(new UndoRemoveLine(editor, insertBefore));
        if (prev != null)
            prev.setNext(next);
        else
            buffer.setFirstLine(next);
        if (next != null)
            next.setPrevious(prev);
        buffer.renumber();
        buffer.modified();
        editor.endCompoundEdit(compoundEdit);
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getTopLine() == dotLine)
                ed.setTopLine(dot.getLine());
        }
        buffer.repaint();
    }

    private static void revertChangedLine(Editor editor, final Line dotLine) {
        final Buffer buffer = editor.getBuffer();
        final Position dot = editor.getDot(); // Alias.
        final String originalText = dotLine.getOriginalText();
        if (originalText != null) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            final int index = originalText.indexOf('\n');
            if (index >= 0) {
                // Multi-line change.
                final Line begin = dotLine;
                final Line end = dotLine.next();
                editor.addUndo(SimpleEdit.LINE_EDIT);
                dotLine.setText(originalText.substring(0, index));
                buffer.modified();
                editor.addUndo(SimpleEdit.MOVE);
                dot.setOffset(dotLine.length());
                editor.addUndo(SimpleEdit.INSERT_STRING);
                buffer.insertString(dot, originalText.substring(index));
                for (Line line = begin; line != end; line = line.next())
                    line.unmodified();
            } else {
                // Single line change.
                editor.addUndo(SimpleEdit.LINE_EDIT);
                dotLine.setText(originalText);
                dotLine.unmodified();
                Editor.updateInAllEditors(dotLine);
                buffer.modified();
                if (dot.getOffset() > dotLine.length()) {
                    editor.addUndo(SimpleEdit.MOVE);
                    dot.setOffset(dotLine.length());
                }
            }
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
        }
    }

    public static void changes() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        final File file = buffer.getFile();
        if (file == null || file.isRemote())
            return;
        final File tempFile = Utilities.getTempFile();
        if (tempFile == null)
            return;
        if (buffer.writeFile(tempFile)) {
            StringBuilder sb = new StringBuilder("diff -u ");
            sb.append(Utilities.maybeQuote(file.canonicalPath()));
            sb.append(' ');
            sb.append(Utilities.maybeQuote(tempFile.canonicalPath()));
            final String cmd = sb.toString();
            ShellCommand shellCommand = new ShellCommand(cmd);
            shellCommand.run();
            // Kill existing diff output buffer if any for same parent buffer.
            for (Buffer b : Editor.getBufferList()) {
                if (b instanceof DiffOutputBuffer diffOutputBuffer) {
                    if (diffOutputBuffer.getParentBuffer() == buffer) {
                        if (diffOutputBuffer.getVCType() == 0) {
                            b.kill();
                            break; // There should be one at most.
                        }
                    }
                }
            }
            String output = shellCommand.getOutput();
            if (output.length() == 0) {
                MessageDialog.showMessageDialog(editor, "No changes", buffer.getFile().getName());
            } else {
                DiffOutputBuffer outputBuffer = new DiffOutputBuffer(buffer, output, 0);
                sb.setLength(0);
                sb.append("diff -ub ");
                sb.append(file.getName());
                sb.append(' ');
                sb.append(tempFile.canonicalPath());
                outputBuffer.setTitle(sb.toString());
                editor.makeNext(outputBuffer);
                editor.activateInOtherWindow(outputBuffer);
            }
        }
        if (tempFile.isFile())
            tempFile.delete();
    }
}
