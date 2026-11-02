/*
 * ConflictCommands.java
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

package org.armedbear.j.vcs.conflicts;

import java.util.ArrayList;
import java.util.List;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.vcs.conflicts.Conflicts.Kept;

/**
 * Taking a side of a conflict in the editor: the four commands, and what
 * the links over each opener run. Each replaces the region's lines with
 * the side's, as one edit, undone whole; the caret is left at the first
 * line put back.
 */
public final class ConflictCommands {

    /** A side of a conflict, and the command that takes it. */
    public enum Take {
        OURS("vcsConflictOurs"), THEIRS("vcsConflictTheirs"), BOTH("vcsConflictBoth"), BASE("vcsConflictBase");

        public final String command;

        Take(String command) {
            this.command = command;
        }
    }

    private ConflictCommands() {}

    public static void vcsConflictOurs(Editor editor) {
        takeAt(editor, Take.OURS);
    }

    public static void vcsConflictTheirs(Editor editor) {
        takeAt(editor, Take.THEIRS);
    }

    public static void vcsConflictBoth(Editor editor) {
        takeAt(editor, Take.BOTH);
    }

    public static void vcsConflictBase(Editor editor) {
        takeAt(editor, Take.BASE);
    }

    // The region at the caret, taken.
    private static void takeAt(Editor editor, Take take) {
        final Buffer buffer = editor.getBuffer();
        final Conflicts conflicts = buffer == null ? null : buffer.getConflicts();
        if (conflicts == null || editor.getDot() == null) {
            editor.status("No conflict here");
            return;
        }
        final Kept k = conflicts.keptAt(editor.getDotLine());
        if (k == null) {
            editor.status("No conflict here");
            return;
        }
        takeRegion(editor, k, take);
    }

    // A link clicked: its region, if its opener is still where it was; if
    // edits moved it on, the scan says so.
    static void takeLink(Editor editor, Conflicts conflicts, Line opener, Take take) {
        if (editor == null || editor.getBuffer() != conflicts.buffer())
            return;
        final Kept k = conflicts.byOpener(opener);
        if (k == null) {
            conflicts.scan();
            editor.status("That conflict changed");
            return;
        }
        takeRegion(editor, k, take);
    }

    // The region's opener-to-closer lines replaced by take's, one edit.
    private static void takeRegion(Editor editor, Kept k, Take take) {
        if (take == Take.BASE && !k.region().hasBase()) {
            editor.status("This conflict has no base");
            return;
        }
        if (!editor.checkReadOnly())
            return;
        final Buffer buffer = editor.getBuffer();
        final Line last = buffer.getLastLine();
        final Position from = new Position(k.opener(), 0);
        // The closer's line goes whole: up to the next line's start, or,
        // the last line, its end, which has no newline after it.
        final boolean closerIsLast = k.closer() == last;
        final Position to = closerIsLast ? new Position(last, last.length()) : new Position(k.closer().next(), 0);
        final List<String> text = switch (take) {
            case OURS -> texts(k.ours());
            case THEIRS -> texts(k.theirs());
            case BOTH -> {
                final List<String> all = new ArrayList<>(texts(k.ours()));
                all.addAll(texts(k.theirs()));
                yield all;
            }
            case BASE -> texts(k.base());
        };
        editor.setMark(null);
        final int first = k.opener().lineNumber();
        final CompoundEdit compound = editor.beginCompoundEdit();
        editor.deleteRegion(from, to);
        if (!text.isEmpty()) {
            final StringBuilder sb = new StringBuilder(String.join("\n", text));
            if (!closerIsLast)
                sb.append('\n');
            // The caret is at from after the delete; insert there.
            editor.insertString(sb.toString());
            editor.endCompoundEdit(compound);
            // The caret at the first line put back.
            editor.setDot(buffer.getLine(first), 0);
        } else {
            editor.endCompoundEdit(compound);
        }
    }

    private static List<String> texts(List<Line> lines) {
        return lines.stream().map(Line::getText).toList();
    }
}
