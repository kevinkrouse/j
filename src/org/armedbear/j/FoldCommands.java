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
import java.util.regex.Pattern;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.perl.PerlMode;

/** Folding: hiding and showing runs of lines. */
public final class FoldCommands {
    private static final Pattern LABEL = Pattern.compile("^\\s*\\w+:");

    private FoldCommands() {}

    public static void fold(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (!foldRegionInternal(editor) && !foldExplicit(editor) && !foldByMode(editor))
            foldNearLine(editor, editor.getDotLine());
    }

    /** Unfolds the fold just below the caret's line, else folds there. */
    public static void toggleFold(Editor editor) {
        if (editor.getDot() == null)
            return;
        final Line next = editor.getDotLine().next();
        if (next != null && next.isHidden())
            unfold(editor, next);
        else
            fold(editor);
    }

    /**
     * Unfolds the fold just below the caret's line, as vim's zo does,
     * where unfold opens the next one wherever it is.
     */
    public static void unfoldHere(Editor editor) {
        if (editor.getDot() == null)
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
        editor.getMode().foldAll(editor);
    }

    // Folds what the mode says folding at the caret does, if it says.
    private static boolean foldByMode(Editor editor) {
        final Line[] range = editor.getMode().getFoldRange(editor, editor.getDotLine());
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
        editor.addUndo(SimpleEdit.FOLD);
        hide(editor, begin, end);
    }

    // Hides the lines from begin up to end and keeps the caret in sight.
    private static void hide(Editor editor, Line begin, Line end) {
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

    public static void foldNearLine(Editor editor, Line line) {
        while (line != null && line.isBlank())
            line = line.previous();
        if (line == null)
            return;
        editor.setWaitCursor();
        Line next = line.next();
        while (next != null && next.isBlank())
            next = next.next();
        if (next != null) {
            final String trim = line.trim();
            final Mode mode = editor.getMode();
            if (mode.foldsAtBraces()) {
                if (trim.endsWith("{")) {
                    if (!next.isHidden()) {
                        fold(editor, next);
                        return;
                    }
                }
                if (trim.startsWith("}")) {
                    // We're at the end of a code block. Find the start of
                    // the block and fold from there.
                    Position end =
                        new Position(line, line.getText().indexOf('}'));
                    Position start = CaretCommands.findMatchInternal(editor, end, 0);
                    if (start != null) {
                        foldNearLine(editor, start.getLine());
                        return;
                    }
                }
                if (next.trim().startsWith("}")) {
                    // Fold block containing current line.
                    Position end =
                        new Position(next, next.getText().indexOf('}'));
                    Position start = CaretCommands.findMatchInternal(editor, end, 0);
                    if (start != null) {
                        foldNearLine(editor, start.getLine());
                        return;
                    }
                } else if (next.trim().endsWith("{")) {
                    Line nextNext = next.next();
                    while (nextNext != null && nextNext.isBlank())
                        nextNext = nextNext.next();
                    if (nextNext != null && !nextNext.isHidden()) {
                        fold(editor, nextNext);
                        return;
                    }
                }
            } else if (mode.foldsAtTags()) {
                if (trim.startsWith("/>") || trim.startsWith("</")) {
                    Line prev = line.previous();
                    while (prev != null && prev.isBlank())
                        prev = prev.previous();
                    if (prev != null) {
                        int indent =
                            editor.getBuffer().getCol(line, line.getIndentation());
                        int prevIndent =
                            editor.getBuffer().getCol(prev, prev.getIndentation());
                        if (indent < prevIndent) {
                            fold(editor, prev);
                            return;
                        }
                    }
                } else if (trim.startsWith("<")) {
                    int indent =
                        editor.getBuffer().getCol(line, line.getIndentation());
                    int nextIndent =
                        editor.getBuffer().getCol(next, next.getIndentation());
                    if (indent < nextIndent) {
                        fold(editor, next);
                        return;
                    }
                }
            }
        }
        fold(editor, line);
    }

    private static void fold(Editor editor, Line target) {
        if (target == null)
            return;
        int indent = editor.getBuffer().getCol(target, target.getIndentation());
        if (indent == 0)
            return;
        Line begin = target;
        while (true) {
            Line prev = begin.previous();
            if (prev == null)
                break;
            if (
                prev.isBlank()
                    || editor.getMode().isCommentLine(prev)
                    ||
                    isLabelLine(editor, prev)
                    || isPreprocessorLine(editor, prev)
            ) {
                begin = prev;
                continue;
            }
            if (editor.getBuffer().getCol(prev, prev.getIndentation()) < indent)
                break;
            if (prev.getText().endsWith("{"))
                break;
            begin = prev;
        }
        Line end = target.next();
        while (end != null) {
            if (
                end.isBlank()
                    || editor.getMode().isCommentLine(end)
                    ||
                    isLabelLine(editor, end)
                    || isPreprocessorLine(editor, end)
            ) {
                end = end.next();
                continue;
            }
            if (editor.getBuffer().getCol(end, end.getIndentation()) < indent)
                break;
            end = end.next();
        }
        hideLines(editor, begin, end);
    }

    private static boolean isLabelLine(Editor editor, Line line) {
        Mode mode = editor.getMode();
        if (mode instanceof JavaMode || mode instanceof PerlMode)
            return LABEL.matcher(line.getText()).find();
        return false;
    }

    private static boolean isPreprocessorLine(Editor editor, Line line) {
        if (editor.getMode() instanceof CMode)
            if (line.trim().startsWith("#"))
                return true;

        return false;
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
        Mode mode = editor.getMode();
        if (mode instanceof JavaMode || mode instanceof PerlMode) {
            editor.setWaitCursor();
            List<LocalTag> tags = editor.getBuffer().getTags();
            if (tags != null) {
                editor.addUndo(SimpleEdit.FOLD);
                for (Line line = editor.getBuffer().getFirstLine(); line != null; line = line.next())
                    line.show();
                for (int i = 0; i < tags.size(); i++) {
                    LocalTag tag = tags.get(i);
                    if (tag.getType() == TAG_METHOD)
                        foldMethod(editor, tag.getLine());
                }
                unhideDotInAllFrames(editor.getBuffer());
            }
        }
    }

    private static void foldMethod(Editor editor, Line line) {
        foldOrUnfoldMethod(editor, line, true);
    }

    public static void unfoldMethod(Editor editor, Line line) {
        foldOrUnfoldMethod(editor, line, false);
    }

    private static void foldOrUnfoldMethod(Editor editor, Line line, boolean fold) {
        Mode mode = editor.getMode();
        while (line != null) {
            String s = line.trim();
            s = mode.trimSyntacticWhitespace(s);
            if (s.endsWith("{"))
                break;
            line = line.next();
        }
        if (line == null)
            return;
        if (line.next() == null)
            return; // Nothing to fold.
        Position start = new Position(line, line.length());
        Line begin = line.next();
        final SyntaxIterator it = mode.getSyntaxIterator(start);
        Position match = null;
        int count = 1;
        while (true) {
            char c = it.nextChar();
            if (c == SyntaxIterator.DONE)
                break;
            if (c == '{')
                ++count;
            else if (c == '}')
                --count;
            if (count == 0) {
                // Found it!
                match = it.getPosition();
                break;
            }
        }
        if (match == null)
            return;
        Line end = match.getLine();
        if (fold) {
            for (Line toBeHidden = begin; toBeHidden != end && toBeHidden != null; toBeHidden = toBeHidden.next())
                toBeHidden.hide();
        } else {
            for (Line toBeShown = begin; toBeShown != end && toBeShown != null; toBeShown = toBeShown.next())
                toBeShown.show();
        }
        editor.getBuffer().needsRenumbering = true;
    }
}
