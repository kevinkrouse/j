/*
 * FoldCommands.java
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

import java.util.List;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.perl.PerlMode;

/** Folding: hiding and showing runs of lines. */
public final class FoldCommands {
    private FoldCommands() {}

    /**
     * Folds the selection, an explicit fold's markers, or the innermost
     * open fold around the caret's line, as vim's zc: on a fold's first
     * line, that fold; on one already closed, the fold around it.
     */
    public static void fold(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (editor.getBuffer().getFolds().setFoldAt(editor, editor.getDotLine(), false))
            return;
        if (!foldRegionInternal(editor) && !foldExplicit(editor) && !foldByMode(editor))
            foldAt(editor, editor.getDotLine());
    }

    /** Folds the innermost open fold around line, past one it heads that is closed; says if there is none. */
    public static void foldAt(Editor editor, Line line) {
        final Buffer buffer = editor.getBuffer();
        final Folds folds = buffer.getFolds();
        // Folds of the buffer's own, as a status's.
        if (folds.setFoldAt(editor, line, false))
            return;
        final Line[] around = foldAround(buffer, line, true, Integer.MAX_VALUE, l -> folds.isFoldStart(buffer, l));
        final Line[] below = around == null ? null : folds.getFoldBelow(buffer, around[0]);
        if (below == null) {
            editor.status("No fold here");
            return;
        }
        hideLines(editor, below[0], below[1].next());
    }

    /**
     * Closes the folds a file opens with, as the preferences say: its
     * header comment, its imports, its class's documentation; not one the
     * caret is in.
     */
    public static void foldOnOpen(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (ownFolds(editor) || buffer.getType() != Buffer.TYPE_NORMAL || editor.getDot() == null)
            return;
        final Folds folds = buffer.getFolds();
        final int dot = editor.getDotLineNumber();
        boolean hid = false;
        for (Folds.Initial kind : Folds.Initial.values()) {
            if (!buffer.getBooleanProperty(kind.property()))
                continue;
            final Line head = folds.initialFold(buffer, kind);
            final Line[] below = head == null ? null : folds.getFoldBelow(buffer, head);
            if (below == null || below.length != 2 || dot >= below[0].lineNumber() && dot <= below[1].lineNumber())
                continue;
            for (Line l = below[0]; l != below[1].next(); l = l.next())
                l.hide();
            hid = true;
        }
        if (hid)
            buffer.renumber();
    }

    /**
     * The lines after line in the run of lines member takes that line
     * starts, blank lines among them but not after; null if line starts
     * none, follows another of the run, or is alone.
     */
    public static Line[] runBelow(Line line, java.util.function.Predicate<Line> member) {
        if (!member.test(line))
            return null;
        Line previous = line.previous();
        while (previous != null && previous.isBlank())
            previous = previous.previous();
        if (previous != null && member.test(previous))
            return null;
        Line last = line;
        for (Line l = line.next(); l != null && (l.isBlank() || member.test(l)); l = l.next())
            if (!l.isBlank())
                last = l;
        return last == line ? null : new Line[] { line.next(), last };
    }

    /** Whether line is its buffer's first that isn't blank. */
    public static boolean isFirst(Line line) {
        for (Line l = line.previous(); l != null; l = l.previous())
            if (!l.isBlank())
                return false;
        return true;
    }

    /**
     * The first and last lines of the innermost open fold around line, a
     * closing brace after it included; null if there is none within reach
     * lines above. A fold line heads that is closed is passed over to the
     * one around it when pastClosed, else there is none.
     */
    public static Line[] foldAround(
            Buffer buffer,
            Line line,
            boolean pastClosed,
            int reach,
            java.util.function.Predicate<Line> isFoldStart) {
        final Folds folds = buffer.getFolds();
        for (Line start = line; start != null && reach-- >= 0; start = start.previous()) {
            if (start.isHidden() || !isFoldStart.test(start))
                continue;
            if (folds.isFolded(start)) {
                if (start == line && !pastClosed)
                    return null;
                continue;
            }
            final Line[] below = folds.getFoldBelow(buffer, start);
            if (below == null || below.length != 2)
                continue;
            final Line last = throughClosing(buffer, start, below[1]);
            if (last.lineNumber() >= line.lineNumber())
                return new Line[] { start, last };
        }
        return null;
    }

    /** Unfolds the fold just below the caret's line, else folds there. */
    public static void toggleFold(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (editor.getBuffer().getFolds().setFoldAt(editor, editor.getDotLine(), null))
            return;
        final Line next = editor.getDotLine().next();
        if (next != null && next.isHidden())
            unfold(editor, next);
        else
            fold(editor);
    }

    /** Unfolds what line heads if it is folded, else folds it, as the mode says. */
    public static void toggleFoldAt(Editor editor, Line line) {
        final Folds folds = editor.getBuffer().getFolds();
        if (folds.isFolded(line)) {
            unfold(editor, line.next());
            return;
        }
        final Line[] range = folds.getFoldBelow(editor.getBuffer(), line);
        if (range != null)
            hideLines(editor, range[0], range[1].next());
    }

    /**
     * The line after last when it closes what line heads, a closing
     * bracket at line's indent, as a method's brace; else last.
     */
    public static Line throughClosing(Buffer buffer, Line line, Line last) {
        final Line next = last.next();
        if (next == null || buffer.getIndentation(next) != buffer.getIndentation(line))
            return last;
        final String text = next.getText().strip();
        return !text.isEmpty() && "})]".indexOf(text.charAt(0)) >= 0 ? next : last;
    }

    /** Whether the next line that is not blank is indented more than line. */
    public static boolean isIndentedBelow(Buffer buffer, Line line) {
        if (line.isBlank())
            return false;
        Line next = line.next();
        while (next != null && next.isBlank())
            next = next.next();
        return next != null && buffer.getIndentation(next) > buffer.getIndentation(line);
    }

    /**
     * The lines after line indented more than it, with the blank lines among
     * them but not those after the last; null if there are none.
     */
    public static Line[] indentedBelow(Buffer buffer, Line line) {
        if (!isIndentedBelow(buffer, line))
            return null;
        final int indent = buffer.getIndentation(line);
        Line last = line;
        for (Line l = line.next(); l != null; l = l.next()) {
            if (l.isBlank())
                continue;
            if (buffer.getIndentation(l) <= indent)
                break;
            last = l;
        }
        return new Line[] { line.next(), last };
    }

    /**
     * Unfolds the fold just below the caret's line, as vim's zo does,
     * where unfold opens the next one wherever it is.
     */
    public static void unfoldHere(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (editor.getBuffer().getFolds().setFoldAt(editor, editor.getDotLine(), true))
            return;
        final Line next = editor.getDotLine().next();
        if (next != null && next.isHidden())
            unfold(editor, next);
        else
            editor.status("No fold here");
    }

    /** Folds everything that can be, as the mode says. */
    public static void foldAll(Editor editor) {
        if (editor.getDot() == null)
            return;
        editor.getBuffer().getFolds().foldAll(editor);
    }

    // Folds what the mode says folding at the caret does, if it says.
    private static boolean foldByMode(Editor editor) {
        final Line[] range = editor.getBuffer().getFolds().getFoldRange(editor, editor.getDotLine());
        if (range == null)
            return false;
        if (range.length == 2)
            hideLines(editor, range[0], range[1].next());
        else
            editor.status("Nothing to fold");
        return true;
    }

    /**
     * Unfolds everything, then hides the lines from begin on that shown does
     * not take, as one fold that undo puts back: an outline of a buffer.
     */
    public static void showOnly(Editor editor, Line begin, java.util.function.Predicate<Line> shown) {
        if (ownFolds(editor))
            return;
        editor.addUndo(SimpleEdit.FOLD);
        for (Line line = editor.getBuffer().getFirstLine(); line != null; line = line.next())
            line.show();
        for (Line line = begin; line != null; line = line.next())
            if (!shown.test(line))
                line.hide();
        editor.getBuffer().renumber();
        unhideDotInAllFrames(editor.getBuffer());
    }

    /**
     * Hides the lines from begin up to end, or to the end of the buffer if
     * end is null, as one fold that undo puts back.
     */
    public static void hideLines(Editor editor, Line begin, Line end) {
        // Folds of the buffer's own, as a log's runs: the one begin is in.
        if (editor.getBuffer().getFolds().setFoldAt(editor, begin, false))
            return;
        editor.addUndo(SimpleEdit.FOLD);
        hide(editor, begin, end);
    }

    // Hides the lines from begin up to end and keeps the caret in sight;
    // nothing in a buffer whose folds are its own, which hide its lines.
    private static void hide(Editor editor, Line begin, Line end) {
        if (ownFolds(editor))
            return;
        for (Line line = begin; line != end; line = line.next())
            line.hide();
        editor.getBuffer().renumber();
        unhideDotInAllFrames(editor.getBuffer());
    }

    public static void foldRegion(Editor editor) {
        if (editor.getDot() == null)
            return;
        foldRegionInternal(editor);
    }

    private static boolean foldRegionInternal(Editor editor) {
        if (editor.getMark() == null)
            return false;
        if (editor.getDot().getLine() == editor.getMark().getLine())
            return false;
        if (editor.getDot().getOffset() > 0)
            return false;
        if (editor.getMark().getOffset() > 0)
            return false;
        Region r = new Region(editor.getBuffer(), editor.getMark(), editor.getDot());
        if (editor.getBuffer().getFolds().setFoldAt(editor, r.getBegin().getLine().next(), false)) {
            editor.setMark(null);
            return true;
        }
        editor.addUndo(SimpleEdit.FOLD);
        editor.setMark(null);
        hide(editor, r.getBegin().getLine().next(), r.getEnd().getLine());
        return true;
    }

    private static boolean foldExplicit(Editor editor) {
        final Line dotLine = editor.getDotLine();
        String text = dotLine.getText();
        Line begin = null;
        Line end = null;
        if (text.indexOf(EXPLICIT_FOLD_END) >= 0) {
            // Current line contains an end marker.
            int count = 1;
            end = dotLine.next();
            begin = dotLine.previous();
            while (begin != null) {
                text = begin.getText();
                if (text.indexOf(EXPLICIT_FOLD_START) >= 0) {
                    --count;
                    if (count == 0) {
                        begin = begin.next();
                        break;
                    }
                } else if (text.indexOf(EXPLICIT_FOLD_END) >= 0) {
                    ++count;
                }
                begin = begin.previous();
            }
        } else if (text.indexOf(EXPLICIT_FOLD_START) >= 0) {
            int count = 1;
            begin = dotLine.next();
            if (begin == null)
                return false;
            end = begin.next();
            while (end != null) {
                text = end.getText();
                if (text.indexOf(EXPLICIT_FOLD_START) >= 0) {
                    ++count;
                } else if (text.indexOf(EXPLICIT_FOLD_END) >= 0) {
                    --count;
                    if (count == 0) {
                        end = end.next();
                        break;
                    }
                }
                end = end.next();
            }
        }
        if (begin == null)
            return false;
        hideLines(editor, begin, end);
        return true;
    }

    // Whether the buffer's folds are its own, not its mode's, as a
    // comparison's unchanged runs: not lines hidden by structure.
    private static boolean ownFolds(Editor editor) {
        return editor.getBuffer().getFolds() != editor.getBuffer().getMode();
    }

    // BUG! This method does more than its name suggests...
    public static void unhideDotInAllFrames(Buffer buffer) {
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            if (ed.getBuffer() == buffer) {
                // Make sure dot is visible.
                if (ed.getDot().isHidden()) {
                    ed.setMark(null);
                    Line line = ed.getDotLine().previousVisible();
                    if (line != null) {
                        ed.setDot(line, 0);
                        ed.moveCaretToDotCol();
                    }
                }
                ed.setUpdateFlag(REFRAME);
                ed.reframe();
                ed.getDisplay().repaint();
            }
        }
    }

    public static void unfold(Editor editor) {
        if (editor.getDot() == null)
            return;
        Line dotLine = editor.getDotLine();
        Line begin = null;
        if (dotLine.isHidden()) {
            begin = dotLine;
            while (begin.previous() != null && begin.previous().isHidden())
                begin = begin.previous();
        } else {
            // Look for next fold.
            begin = dotLine.next();
            while (begin != null && !begin.isHidden())
                begin = begin.next();
        }
        if (begin == null)
            return;
        if (!begin.isHidden())
            return;
        if (editor.getBuffer().getFolds().setFoldAt(editor, begin, true))
            return;
        Line end = begin;
        while (true) {
            Line line = end.next();
            if (line == null)
                break;
            if (!line.isHidden())
                break;
            end = line;
        }
        if (begin != null && end != null) {
            editor.addUndo(SimpleEdit.FOLD);
            for (Line line = begin; line != end.next(); line = line.next())
                line.unhide();
            editor.getBuffer().renumber();
            for (int i = 0; i < Editor.getEditorCount(); i++) {
                Editor ed = Editor.getEditor(i);
                if (ed.getBuffer() == editor.getBuffer())
                    ed.getDisplay().repaint();
            }
        }
    }

    public static void unfold(Editor editor, Line line) {
        if (line == null)
            return;
        if (!line.isHidden())
            return;
        // Folds of the buffer's own: opened as they say, not by showing lines.
        if (editor.getBuffer().getFolds().setFoldAt(editor, line, true))
            return;
        Line begin = line;
        while (begin.previous() != null && begin.previous().isHidden())
            begin = begin.previous();
        Line end = line.next();
        while (end != null && end.isHidden())
            end = end.next();
        editor.addUndo(SimpleEdit.FOLD);
        for (Line l = begin; l != end; l = l.next())
            l.unhide();
        editor.getBuffer().renumber();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            if (ed.getBuffer() == editor.getBuffer())
                ed.getDisplay().repaint();
        }
    }

    static void show(Editor editor, Line target) {
        if (target == null)
            return;
        if (!target.isHidden())
            return;
        if (editor.getBuffer().getFolds().setFoldAt(editor, target, true))
            return;
        Line begin = target;
        while (begin.previous() != null && begin.previous().isHidden())
            begin = begin.previous();
        Line end = target.next();
        while (end != null && end.isHidden())
            end = end.next();
        for (Line line = begin; line != end; line = line.next())
            line.show();
        editor.getBuffer().renumber();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            if (ed.getBuffer() == editor.getBuffer())
                ed.getDisplay().repaint();
        }
    }

    public static void unfoldAll(Editor editor) {
        if (editor.getBuffer().getFolds().setAllFolds(editor, true))
            return;
        editor.addUndo(SimpleEdit.FOLD);
        for (Line line = editor.getBuffer().getFirstLine(); line != null; line = line.next())
            line.show();
        editor.getBuffer().renumber();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            if (ed.getBuffer() == editor.getBuffer())
                ed.getDisplay().repaint();
        }
    }

    public static void foldMethods(Editor editor) {
        if (ownFolds(editor))
            return;
        Mode mode = editor.getMode();
        if (mode instanceof JavaMode || mode instanceof PerlMode) {
            editor.setWaitCursor();
            List<LocalTag> tags = editor.getBuffer().getTags();
            if (tags != null) {
                editor.addUndo(SimpleEdit.FOLD);
                for (Line line = editor.getBuffer().getFirstLine(); line != null; line = line.next())
                    line.show();
                final Buffer buffer = editor.getBuffer();
                final Folds folds = buffer.getFolds();
                for (LocalTag tag : tags) {
                    if (tag.getType() != TAG_METHOD)
                        continue;
                    final Line[] below = folds.getFoldBelow(buffer, tag.getLine());
                    if (below != null && below.length == 2)
                        for (Line l = below[0]; l != below[1].next(); l = l.next())
                            l.hide();
                }
                buffer.renumber();
                unhideDotInAllFrames(editor.getBuffer());
            }
        }
    }

    /** Shows what the method whose tag is on line hides, nested folds too, as going to it wants. */
    public static void unfoldMethod(Editor editor, Line line) {
        if (ownFolds(editor))
            return;
        final Buffer buffer = editor.getBuffer();
        final Line[] below = buffer.getFolds().getFoldBelow(buffer, line);
        if (below == null || below.length != 2)
            return;
        for (Line l = below[0]; l != below[1].next(); l = l.next())
            l.show();
        buffer.renumber();
    }
}
