/*
 * ComparedPicks.java
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

package org.armedbear.j.vcs.status;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Region;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/**
 * A status's picks in its side-by-side comparison: where each hunk of the
 * file compared, and each of its changed lines, is on each side, and the
 * picks made by those rows. Rows count from 0. On the event thread.
 */
final class ComparedPicks {
    /**
     * A box in a side: a hunk's, i -1, or, the hunk picked in part, a
     * changed line's, i its index; as picked.
     */
    record Box(String id, int i, PickState state) {}

    private final VcsStatusBuffer status;
    // The rows of the diff last asked of, worked out once a diff.
    private FileDiff rowsOf;
    private Rows rows;

    ComparedPicks(VcsStatusBuffer status) {
        this.status = status;
    }

    /** The id of the compared hunk that holds the old side's row n if old, else the new side's, context and all; null for none. */
    String hunkAt(boolean old, int n) {
        final Rows r = rows();
        if (r == null)
            return null;
        for (HunkRows h : r.hunks())
            if (h.holds(old, n))
                return h.id();
        return null;
    }

    /**
     * The box at the old side's row n if old, else the new side's: a
     * hunk's at its first changed row, or, with none there, the row after
     * its lines on the other; or, while it is picked in part, a box at each
     * of its changed rows. Null for none.
     */
    Box boxAt(boolean old, int n) {
        final Rows r = rows();
        if (r == null)
            return null;
        final String fk = status.diffFile();
        final ChangedLine c = r.changed(old).get(n);
        if (c != null && status.picks().hunkState(fk, c.id()) == PickState.SOME)
            return new Box(c.id(),
                    c.i(),
                    status.picks().linePicked(fk, c.id(), c.i()) ? PickState.ALL : PickState.NONE);
        final String id = r.firsts(old).get(n);
        return id == null ? null : new Box(id, -1, status.picks().hunkState(fk, id));
    }

    /** Picks or unpicks what box is. */
    void toggle(Box box) {
        final StatusDiffBuffer d = status.getDiff();
        if (box.i() < 0)
            status.picks().toggleHunk(status.diffFile(), box.id(), d.repository(), d.section(), d.item());
        else {
            final BitSet one = new BitSet();
            one.set(box.i());
            status.picks().toggleLines(status.diffFile(), d.repository(), d.section(), d.item(), Map.of(box.id(), one));
        }
        status.showPicks();
    }

    /**
     * Picks the hunk at editor's caret in a side, or the changed lines a
     * selection there covers; or unpicks them.
     */
    void pick(Editor editor, boolean old) {
        final Rows r = rows();
        final StatusDiffBuffer d = status.getDiff();
        if (Region.isSelection(editor)) {
            final Map<String, BitSet> idx = new LinkedHashMap<>();
            if (r != null)
                for (Line l : Region.selectedLines(editor)) {
                    final ChangedLine c = r.changed(old).get(l.lineNumber());
                    if (c != null)
                        idx.computeIfAbsent(c.id(), k -> new BitSet()).set(c.i());
                }
            if (idx.isEmpty()) {
                editor.status(nothingHere());
                return;
            }
            status.picks().toggleLines(status.diffFile(), d.repository(), d.section(), d.item(), idx);
            editor.setMark(null);
            status.showPicks();
            return;
        }
        final String id = hunkAt(old, editor.getDotLineNumber());
        if (id == null) {
            editor.status(nothingHere());
            return;
        }
        status.picks().toggleHunk(status.diffFile(), id, d.repository(), d.section(), d.item());
        status.showPicks();
    }

    /** Picks what each of boxes is if on, else unpicks it. */
    void set(List<Box> boxes, boolean on) {
        final StatusDiffBuffer d = status.getDiff();
        final Map<String, BitSet> idx = new LinkedHashMap<>();
        for (Box b : boxes)
            if (b.i() < 0)
                status.picks().setHunk(status.diffFile(), b.id(), d.repository(), d.section(), d.item(), on);
            else
                idx.computeIfAbsent(b.id(), k -> new BitSet()).set(b.i());
        status.picks().setLines(status.diffFile(), d.repository(), d.section(), d.item(), idx, on);
        status.showPicks();
    }

    // Why nothing is picked here.
    private String nothingHere() {
        return status.comparedEdited()
                ? "Save " + status.getDiff().item().path() + " to pick its changes here"
                : "No change to pick here";
    }

    // The compared file's rows, or null if its hunks can't be picked.
    private Rows rows() {
        final FileItem item = status.comparedItem();
        if (item == null)
            return null;
        if (item.diff() != rowsOf) {
            rowsOf = item.diff();
            rows = Rows.of(status.picks().ids(item.diff()));
        }
        return rows;
    }

    // A hunk's rows on each side: its first changed line's, and its own,
    // context and all, from start to before end.
    private record HunkRows(String id, int oldFirst, int oldStart, int oldEnd, int newFirst, int newStart, int newEnd) {
        boolean holds(boolean old, int n) {
            return old ? n >= oldStart && n < oldEnd : n >= newStart && n < newEnd;
        }
    }

    // A hunk's changed line, i its index.
    private record ChangedLine(String id, int i) {}

    // A diff's hunks' rows; the hunks by their first rows, and their
    // changed lines by their rows, on each side.
    private record Rows(List<HunkRows> hunks, Map<Integer, String> oldFirsts, Map<Integer, String> newFirsts,
            Map<Integer, ChangedLine> oldChanged, Map<Integer, ChangedLine> newChanged) {
        Map<Integer, String> firsts(boolean old) {
            return old ? oldFirsts : newFirsts;
        }

        Map<Integer, ChangedLine> changed(boolean old) {
            return old ? oldChanged : newChanged;
        }

        static Rows of(Map<String, Hunk> ids) {
            final Rows r =
                    new Rows(new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
            for (Map.Entry<String, Hunk> e : ids.entrySet()) {
                final Hunk h = e.getValue();
                final int oldStart = row(h.oldStart(), h.oldCount());
                final int newStart = row(h.newStart(), h.newCount());
                int or = oldStart;
                int nr = newStart;
                int oldFirst = -1;
                int newFirst = -1;
                boolean found = false;
                for (int i = 0; i < h.lines().size(); i++) {
                    final DiffLine l = h.lines().get(i);
                    if (!l.isContext() && !found) {
                        found = true;
                        oldFirst = or;
                        newFirst = nr;
                    }
                    if (l.isRemoved())
                        r.oldChanged().put(or, new ChangedLine(e.getKey(), i));
                    if (l.isAdded())
                        r.newChanged().put(nr, new ChangedLine(e.getKey(), i));
                    if (!l.isAdded())
                        or++;
                    if (!l.isRemoved())
                        nr++;
                }
                final HunkRows hr = new HunkRows(e.getKey(),
                        found ? oldFirst : or,
                        oldStart,
                        oldStart + h.oldCount(),
                        found ? newFirst : nr,
                        newStart,
                        newStart + h.newCount());
                r.hunks().add(hr);
                r.oldFirsts().putIfAbsent(hr.oldFirst(), hr.id());
                r.newFirsts().putIfAbsent(hr.newFirst(), hr.id());
            }
            return r;
        }

        // A side's first row, from 0, of a hunk's lines from start, count
        // of them: with none, as a new file's empty side, the row after the
        // line start names.
        private static int row(int start, int count) {
            return count == 0 ? start : start - 1;
        }
    }
}
