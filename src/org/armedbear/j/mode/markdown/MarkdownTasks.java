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

import java.util.regex.Matcher;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.FollowLink;
import org.armedbear.j.InputHandler;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.TextLink;
import org.armedbear.j.UndoLineEdit;

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
public final class MarkdownTasks {
    private static final char TODO = ' ';
    private static final char DOING = '/';
    private static final char DONE = 'x';
    private static final char CANCELLED = '-';

    private MarkdownTasks() {}

    public static void task() {
        task(null);
    }

    /**
     * Ctrl+Enter in Markdown mode: on a link, follows it, or says what its
     * reference lacks; on a list item or a selection, task; else nothing,
     * where task would make a line of text a task.
     */
    public static void followLinkOrTask() {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        if (editor.getMark() == null) {
            final TextLink link =
                MarkdownLinks.find(editor.getBuffer(), dot.getLine(), dot.getOffset());
            if (link != null) {
                if (link.getTarget() != null)
                    FollowLink.follow(editor, link.getTarget());
                else
                    editor.status(link.getProblem());
                return;
            }
            final String text = taskText(editor.getBuffer(), dot.getLine());
            if (text == null || item(text) == null) {
                editor.status("No link or task here");
                return;
            }
        }
        task(null);
    }

    public static void task(String arg) {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly() || editor.getDot() == null)
            return;
        final String state = arg == null ? "" : arg.trim();
        if (
            !state.isEmpty()
                && !state.equals("todo")
                && !state.equals("doing")
                && !state.equals("done")
                && !state.equals("cancel")
        ) {
            editor.status(
                "task: expected todo, doing, done or cancel, not \""
                    + state + "\""
            );
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

    private static void setTasks(Editor editor, Buffer buffer, String state) {
        if (isMarkdown(buffer) && buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
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
        final char target = target(buffer, first, last, state);

        CompoundEdit compoundEdit = null;
        for (Line line = first;; line = line.next()) {
            final String text = taskText(buffer, line);
            final Change change = text != null ? change(text, target) : null;
            if (change != null) {
                if (compoundEdit == null) {
                    compoundEdit = editor.beginCompoundEdit();
                    editor.addUndo(SimpleEdit.MOVE);
                }
                buffer.addEdit(new UndoLineEdit(buffer, line));
                line.setText(
                    text.substring(0, change.at) + change.inserted
                        + text.substring(change.at + change.removed)
                );
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
    private static boolean takesInEnds(Editor editor) {
        final InputHandler handler = editor.getInputHandler();
        return handler != null
            && handler.getCaretShape() != InputHandler.CaretShape.BAR;
    }

    // The state to give every task from first to last.
    private static char target(Buffer buffer, Line first, Line last, String state) {
        switch (state) {
            case "todo":
                return TODO;
            case "doing":
                return DOING;
            case "done":
                return DONE;
            case "cancel": {
                for (Line line = first;; line = line.next()) {
                    final char c = stateOf(buffer, line);
                    if (c != 0 && c != CANCELLED)
                        return CANCELLED;
                    if (line == last)
                        return allBlankOrNotTasks(buffer, first, last) ? CANCELLED : TODO;
                }
            }
            default:
                break;
        }
        for (Line line = first;; line = line.next()) {
            switch (stateOf(buffer, line)) {
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

    private static boolean allBlankOrNotTasks(Buffer buffer, Line first, Line last) {
        for (Line line = first;; line = line.next()) {
            if (stateOf(buffer, line) != 0)
                return false;
            if (line == last)
                return true;
        }
    }

    // The state of the task on a line, ' ', '/', 'x', '-', or 0 if it has
    // no box.
    private static char stateOf(Buffer buffer, Line line) {
        final String text = taskText(buffer, line);
        final Item item = text != null ? item(text) : null;
        if (item == null || item.box < 0)
            return 0;
        final char c = text.charAt(item.box);
        return c == 'X' ? DONE : c;
    }

    private static boolean isMarkdown(Buffer buffer) {
        return buffer.getMode() instanceof MarkdownMode;
    }

    // A line's text, or null for one of code in Markdown, where "- item" is
    // YAML or a diff, not a list.
    private static String taskText(Buffer buffer, Line line) {
        if (
            isMarkdown(buffer)
                && (MarkdownFormatter.isCode(line)
                    || MarkdownFormatter.isIndentedCodeBlock(line))
        )
            return null;
        return line.getText();
    }

    // A line's list item: where its marker ends, where its text begins, and
    // its box's mark, if it has a box. As MarkdownFormatter reads one.
    private static final class Item {
        int markerEnd;
        int textBegin;
        int box = -1;
    }

    private static Item item(String text) {
        final int begin = quoteEnd(text);
        final Matcher m = MarkdownFormatter.LIST_ITEM.matcher(text).region(begin, text.length());
        if (!m.lookingAt())
            return null;
        final Item item = new Item();
        item.markerEnd = m.end(1);
        item.textBegin = m.end();
        final Matcher box =
            MarkdownFormatter.TASK_BOX.matcher(text).region(m.end(), text.length());
        if (box.lookingAt())
            item.box = box.start(1);
        return item;
    }

    // Where a line's quote markers end.
    private static int quoteEnd(String text) {
        final Matcher m = MarkdownFormatter.QUOTE_PREFIX.matcher(text);
        return m.lookingAt() ? m.end() : 0;
    }

    // Replace removed characters at at with inserted.
    private static final class Change {
        final int at;
        final int removed;
        final String inserted;

        Change(int at, int removed, String inserted) {
            this.at = at;
            this.removed = removed;
            this.inserted = inserted;
        }
    }

    // What makes text's task target, or null if nothing does: a blank line,
    // or one already so.
    private static Change change(String text, char target) {
        final Item item = item(text);
        if (item != null) {
            if (item.box >= 0) {
                return text.charAt(item.box) == target
                    ? null
                    : new Change(item.box, 1, String.valueOf(target));
            }
            // A bare "-", or "- item".
            if (item.textBegin == item.markerEnd)
                return new Change(item.markerEnd, 0, " [" + target + "]");
            return new Change(item.textBegin, 0, "[" + target + "] ");
        }
        if (text.trim().isEmpty())
            return null;
        // Within the quote, where its indentation ends.
        int at = quoteEnd(text);
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t'))
            ++at;
        return new Change(at, 0, "- [" + target + "] ");
    }

    // Keeps a position on line with the text it was at.
    private static void shift(Position pos, Line line, Change change) {
        if (pos == null || pos.getLine() != line)
            return;
        final int offset = pos.getOffset();
        if (offset >= change.at + change.removed)
            pos.setOffset(offset + change.inserted.length() - change.removed);
        else if (offset > change.at)
            pos.setOffset(change.at);
    }
}
