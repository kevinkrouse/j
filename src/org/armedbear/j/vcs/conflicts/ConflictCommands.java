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

import static org.armedbear.j.ToolBarIcon.ICON_CHANGE_NEXT;
import static org.armedbear.j.ToolBarIcon.ICON_CHANGE_PREVIOUS;
import static org.armedbear.j.ToolBarIcon.ICON_CONFLICT_BASE;
import static org.armedbear.j.ToolBarIcon.ICON_CONFLICT_BOTH;
import static org.armedbear.j.ToolBarIcon.ICON_CONFLICT_OURS;
import static org.armedbear.j.ToolBarIcon.ICON_CONFLICT_THEIRS;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.JPopupMenu;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.ChangeMarks;
import org.armedbear.j.Dispatcher;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.ToolBar;
import org.armedbear.j.vcs.conflicts.Conflicts.Kept;

/**
 * Taking a side of a conflict in the editor: the four commands, and what
 * the links over each opener run. Each replaces the region's lines with
 * the side's, as one edit, undone whole; the caret goes on to the next
 * conflict, around past the last, or with none left stays at the first
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

    public static void vcsConflictNext(Editor editor) {
        go(editor, true);
    }

    public static void vcsConflictPrevious(Editor editor) {
        go(editor, false);
    }

    /**
     * The conflict group for tb: previous and next, and the four takes,
     * the whole group shown only while the buffer at() has conflicts.
     * Within it the takes are disabled, not hidden, so the toolbar does
     * not shift as the caret moves: ours, theirs and both only with the
     * caret in a region, base only in one that has a base.
     */
    public static void addToolBarGroup(ToolBar tb, Supplier<Editor> at) {
        final Supplier<Conflicts> here = tb.perUpdate(() -> {
            final Editor editor = at.get();
            return editor == null || editor.getBuffer() == null ? null : editor.getBuffer().getConflicts();
        });
        final BooleanSupplier shown = () -> {
            final Conflicts c = here.get();
            return c != null && !c.openers().isEmpty();
        };
        final BooleanSupplier inRegion = () -> {
            final Conflicts c = here.get();
            final Editor editor = at.get();
            return c != null && editor != null && editor.getDot() != null && c.regionAt(editor.getDotLine()) != null;
        };
        final BooleanSupplier inBase = () -> {
            final Conflicts c = here.get();
            final Editor editor = at.get();
            if (c == null || editor == null || editor.getDot() == null)
                return false;
            final ConflictMarkers.Region r = c.regionAt(editor.getDotLine());
            return r != null && r.hasBase();
        };
        tb.addSeparator(shown);
        tb.addButton("Previous Conflict", ICON_CHANGE_PREVIOUS, "vcsConflictPrevious").visibleWhen(shown);
        tb.addButton("Next Conflict", ICON_CHANGE_NEXT, "vcsConflictNext").visibleWhen(shown);
        tb.addButton("Keep Ours", ICON_CONFLICT_OURS, "vcsConflictOurs").visibleWhen(shown).enabledWhen(inRegion);
        tb.addButton("Keep Theirs", ICON_CONFLICT_THEIRS, "vcsConflictTheirs").visibleWhen(shown).enabledWhen(inRegion);
        tb.addButton("Keep Both", ICON_CONFLICT_BOTH, "vcsConflictBoth").visibleWhen(shown).enabledWhen(inRegion);
        tb.addButton("Keep Base", ICON_CONFLICT_BASE, "vcsConflictBase").visibleWhen(shown).enabledWhen(inBase);
    }

    /**
     * The conflict at the caret in editor's context menu: the four takes,
     * named from the region's own labels as the links are, and next and
     * previous. Nothing when the caret is outside a conflict.
     */
    public static void addContextMenuItems(Editor editor, JPopupMenu popup) {
        final Conflicts conflicts = editor.getBuffer() == null ? null : editor.getBuffer().getConflicts();
        if (conflicts == null || editor.getDot() == null)
            return;
        final Kept k = conflicts.keptAt(editor.getDotLine());
        if (k == null)
            return;
        popup.addSeparator();
        final Dispatcher d = editor.getDispatcher();
        AbstractMode
                .addContextMenuItem("Keep " + Conflicts.label(k.region().ours(), "ours"), Take.OURS.command, popup, d);
        AbstractMode.addContextMenuItem(
            "Keep " + Conflicts.label(k.region().theirs(), "theirs"),
            Take.THEIRS.command,
            popup,
            d);
        AbstractMode.addContextMenuItem("Keep Both", Take.BOTH.command, popup, d);
        if (k.region().hasBase())
            AbstractMode.addContextMenuItem("Keep Base", Take.BASE.command, popup, d);
        AbstractMode.addContextMenuItem("Next Conflict", "vcsConflictNext", popup, d);
        AbstractMode.addContextMenuItem("Previous Conflict", "vcsConflictPrevious", popup, d);
    }

    // To the next or previous conflict's opener, around at the ends. The
    // caret inside a region is past its opener, so next from inside goes
    // to the region after it.
    private static void go(Editor editor, boolean forward) {
        final Conflicts conflicts = editor.getBuffer() == null ? null : editor.getBuffer().getConflicts();
        ChangeMarks.goAround(editor, conflicts == null ? List.of() : conflicts.openers(), forward, "conflict");
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
        // Where the caret goes after: the next conflict, read before the
        // edit, as the scan that follows it comes later.
        final Line next = buffer.getConflicts() == null ? null : buffer.getConflicts().nextOpener(k);
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
            // The caret at the first line put back, if no conflict is left.
            editor.setDot(buffer.getLine(first), 0);
        } else {
            editor.endCompoundEdit(compound);
        }
        if (next != null)
            editor.moveDotTo(next, 0);
    }

    private static List<String> texts(List<Line> lines) {
        return lines.stream().map(Line::getText).toList();
    }
}
