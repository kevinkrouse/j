/*
 * MarkdownTasks.java
 *
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

package org.armedbear.j.mode.markdown;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.InputHandler;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.UndoLineEdit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.undo.CompoundEdit;

/**
 * The task command: sets the state of the task boxes on the lines of the
 * selection, or on the caret's line, as one edit.
 *
 * <pre>
 *   - [ ] not started    - [/] in progress    - [x] done    - [-] cancelled
 * </pre>
 *
 * With no argument it moves them on, not started to in progress to done and
 * round again, as the first box among them says; a cancelled one starts
 * again. "todo", "doing" and "done" set that state, and "cancel" cancels
 * them, or if they all are, starts them again. A list item without a box
 * gets one, and a line of text becomes a task.
 */
public final class MarkdownTasks
{
    private static final char TODO = ' ';
    private static final char DOING = '/';
    private static final char DONE = 'x';
    private static final char CANCELLED = '-';

    // Quote markers and indentation, a list marker, and what follows it.
    private static final Pattern LIST_ITEM = Pattern.compile(
        "^((?:[ \\t]*>[ \\t]?)*[ \\t]*)([-*+]|\\d{1,9}[.)])([ \\t]+|$)");
    private static final Pattern BOX =
        Pattern.compile("\\[([ xX/-])\\](?=[ \\t]|$)");
    private static final Pattern PREFIX =
        Pattern.compile("^(?:[ \\t]*>[ \\t]?)*[ \\t]*");

    private MarkdownTasks() {}

    public static void task()
    {
        task(null);
    }

    public static void task(String arg)
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly() || editor.getDot() == null)
            return;
        final String state = arg == null ? "" : arg.trim();
        if (!state.isEmpty() && !state.equals("todo") && !state.equals("doing")
            && !state.equals("done") && !state.equals("cancel")) {
            editor.status("task: expected todo, doing, done or cancel, not \""
                          + state + "\"");
            return;
        }
        final Buffer buffer = editor.getBuffer();
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            setTasks(editor, buffer, state);
        }
        finally {
            buffer.unlockWrite();
        }
    }

    private static void setTasks(Editor editor, Buffer buffer, String state)
    {
        Line first = editor.getDotLine();
        Line last = first;
        if (editor.getMark() != null) {
            final Region r = new Region(buffer, editor.getDot(), editor.getMark());
            first = r.getBeginLine();
            last = r.getEndLine();
            // j's selection of whole lines ends at the start of the next.
            // Vim's takes in the character at each end, so the line too.
            if (last != first && r.getEndOffset() == 0 && !takesInEnds(editor))
                last = last.previous();
        }
        final char target = target(first, last, state);

        CompoundEdit compoundEdit = null;
        for (Line line = first; ; line = line.next()) {
            final String text = line.getText();
            final Change change = change(text, target);
            if (change != null) {
                if (compoundEdit == null) {
                    compoundEdit = editor.beginCompoundEdit();
                    editor.addUndo(SimpleEdit.MOVE);
                }
                buffer.addEdit(new UndoLineEdit(buffer, line));
                line.setText(text.substring(0, change.at) + change.inserted
                             + text.substring(change.at + change.removed));
                shift(editor.getDot(), line, change);
                shift(editor.getMark(), line, change);
                Editor.updateInAllEditors(buffer, line);
            }
            if (line == last)
                break;
        }
        if (compoundEdit != null) {
            buffer.modified();
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
        }
    }

    // Whether a selection takes in the characters at its ends, as one made
    // with a caret on a character, vim's, does.
    private static boolean takesInEnds(Editor editor)
    {
        final InputHandler handler = editor.getInputHandler();
        return handler != null
            && handler.getCaretShape() != InputHandler.CaretShape.BAR;
    }

    // The state to give every task from first to last.
    private static char target(Line first, Line last, String state)
    {
        switch (state) {
            case "todo":
                return TODO;
            case "doing":
                return DOING;
            case "done":
                return DONE;
            case "cancel": {
                for (Line line = first; ; line = line.next()) {
                    final char c = stateOf(line.getText());
                    if (c != 0 && c != CANCELLED)
                        return CANCELLED;
                    if (line == last)
                        return allBlankOrNotTasks(first, last) ? CANCELLED : TODO;
                }
            }
            default:
                break;
        }
        for (Line line = first; ; line = line.next()) {
            switch (stateOf(line.getText())) {
                case TODO:
                    return DOING;
                case DOING:
                    return DONE;
                case DONE:
                case CANCELLED:
                    return TODO;
                default:
                    break;
            }
            if (line == last)
                return TODO;
        }
    }

    private static boolean allBlankOrNotTasks(Line first, Line last)
    {
        for (Line line = first; ; line = line.next()) {
            if (stateOf(line.getText()) != 0)
                return false;
            if (line == last)
                return true;
        }
    }

    // The state of the task on a line, ' ', '/', 'x', '-', or 0 if it has
    // no box.
    private static char stateOf(String text)
    {
        final Matcher m = LIST_ITEM.matcher(text);
        if (!m.lookingAt())
            return 0;
        final Matcher box = BOX.matcher(text).region(m.end(), text.length());
        if (!box.lookingAt())
            return 0;
        final char c = text.charAt(box.start(1));
        return c == 'X' ? DONE : c;
    }

    // Replace removed characters at at with inserted.
    private static final class Change
    {
        final int at;
        final int removed;
        final String inserted;

        Change(int at, int removed, String inserted)
        {
            this.at = at;
            this.removed = removed;
            this.inserted = inserted;
        }
    }

    // What makes text's task target, or null if nothing does: a blank line,
    // or one already so.
    private static Change change(String text, char target)
    {
        final Matcher m = LIST_ITEM.matcher(text);
        if (m.lookingAt()) {
            final Matcher box = BOX.matcher(text).region(m.end(), text.length());
            if (box.lookingAt()) {
                final int at = box.start(1);
                return text.charAt(at) == target ? null
                    : new Change(at, 1, String.valueOf(target));
            }
            // "- item" or a bare "-".
            if (m.group(3).isEmpty())
                return new Change(m.end(), 0, " [" + target + "]");
            return new Change(m.end(), 0, "[" + target + "] ");
        }
        if (text.trim().isEmpty())
            return null;
        final Matcher prefix = PREFIX.matcher(text);
        prefix.lookingAt();
        return new Change(prefix.end(), 0, "- [" + target + "] ");
    }

    // Keeps a position on line with the text it was at.
    private static void shift(Position pos, Line line, Change change)
    {
        if (pos == null || pos.getLine() != line)
            return;
        final int offset = pos.getOffset();
        if (offset >= change.at + change.removed)
            pos.setOffset(offset + change.inserted.length() - change.removed);
        else if (offset > change.at)
            pos.setOffset(change.at);
    }
}
