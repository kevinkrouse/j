/*
 * RowPicks.java
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
import java.util.function.Supplier;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Region;
import org.armedbear.j.util.PickColumn.Trail;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/**
 * A status's picks of a file by the rows of its diff's sides: where each
 * change, a run of a hunk's changed lines, is on each side, and each of its
 * lines, and the picks made by those rows; as side by side has them, and as
 * the file's own buffer, its new side, has them by its signs. A box picks a
 * change, as the editor's hunk commands take one, not git's hunk, which may
 * hold several. A modified line's removal is picked with its addition, its
 * box the addition's. Rows count from 0. On the event thread.
 */
final class RowPicks {
    /** The file: its repository, section, item, read, and its line's key in the status. */
    record Of(VcsRepository repository, String section, FileItem item, String key) {}

    /**
     * A box in a side: the lines it picks, by hunk id, a change's, or one
     * line's if line, or a sign's row's, those whose signs are there; as
     * picked.
     */
    record Box(Map<String, BitSet> lines, boolean line, PickState state) {
        /** The hunk of its first lines. */
        String id() {
            return lines.keySet().iterator().next();
        }
    }

    private final RepositoryStatus status;
    // The file now, null when its picks can't be shown.
    private final Supplier<Of> of;
    // Why nothing is picked where nothing is there to pick.
    private final Supplier<String> nothingHere;
    // The rows of the diff last asked of, worked out once a diff.
    private FileDiff rowsOf;
    private Rows rows;

    RowPicks(RepositoryStatus status, Supplier<Of> of, Supplier<String> nothingHere) {
        this.status = status;
        this.of = of;
        this.nothingHere = nothingHere;
    }

    /**
     * The changed lines, by hunk id, of the change at the new side's row n
     * as signs draw them, or the one nearest it in its hunk; null for none.
     */
    Map<String, BitSet> changeAt(int n) {
        final Rows r = rows(of.get());
        final Change c = r == null ? null : r.changeAt(false, true, n);
        return c == null ? null : c.picks();
    }

    /** How much of the change changeAt(n) gives is picked; NONE for none. */
    PickState changeState(int n) {
        final Map<String, BitSet> idx = changeAt(n);
        return idx == null ? PickState.NONE : state(of.get(), idx);
    }

    /**
     * The box at the old side's row n if old, else the new side's: a
     * change's at its first changed row, on the new side, or on the old
     * with no line added; or, while it is picked in part, a box at each
     * added row, and at each removed row with no line added for it. Null
     * for none.
     */
    Box boxAt(boolean old, int n) {
        final Of f = of.get();
        final Rows r = rows(f);
        if (r == null)
            return null;
        final ChangedLine c = (old ? r.oldBoxes() : r.newChanged()).get(n);
        if (c != null && state(f, c.change().picks()) == PickState.SOME) {
            final BitSet one = new BitSet();
            one.set(c.i());
            return new Box(Map.of(c.change().id(), one),
                    true,
                    status.picks().linePicked(f.key(), c.change().id(), c.i()) ? PickState.ALL : PickState.NONE);
        }
        final Change first = r.firsts(old).get(n);
        return first == null ? null : new Box(first.picks(), false, state(f, first.picks()));
    }

    /**
     * The box at the new side's row n as signs draw its changes: a
     * change's at its first sign, or, while it is picked in part, a box at
     * each row with a sign, for the changes whose signs are there. Null for
     * none.
     */
    Box signBoxAt(int n) {
        final Of f = of.get();
        final Rows r = rows(f);
        if (r == null)
            return null;
        final List<ChangedLine> signed = r.bySign().get(n);
        if (signed != null && state(f, signed.get(0).change().picks()) == PickState.SOME) {
            final Map<String, BitSet> idx = new LinkedHashMap<>();
            r.signs(n, n, idx);
            return new Box(idx, true, state(f, idx));
        }
        final Change first = r.signFirsts().get(n);
        return first == null ? null : new Box(first.picks(), false, state(f, first.picks()));
    }

    /**
     * Where the old side's row n if old, else the new side's, or with
     * signs, the row as signs draw them, is on the line from a change's
     * box down through its lines, the change picked whole; null for none.
     */
    Trail trailAt(boolean old, boolean signs, int n) {
        final Of f = of.get();
        final Rows r = rows(f);
        final Change c = r == null ? null : (signs ? r.signRuns() : old ? r.oldRuns() : r.newRuns()).get(n);
        if (c == null || state(f, c.picks()) != PickState.ALL)
            return null;
        return n == c.first(old, signs) ? Trail.FROM : n == c.last(old, signs) ? Trail.TO : Trail.THROUGH;
    }

    /** Whether the changed line on the old side's row n if old, else the new side's, is picked. */
    boolean isPicked(boolean old, int n) {
        final Of f = of.get();
        final Rows r = rows(f);
        final ChangedLine c = r == null ? null : r.changed(old).get(n);
        return c != null && status.picks().linePicked(f.key(), c.change().id(), c.i());
    }

    /** Picks or unpicks what box is. */
    void toggle(Box box) {
        set(List.of(box), box.state() != PickState.ALL);
    }

    /**
     * Picks the change at editor's caret in a side, or the nearest it in
     * its hunk, or the changed lines a selection there covers; or unpicks
     * them.
     */
    void pick(Editor editor, boolean old) {
        final Of f = of.get();
        final Rows r = rows(f);
        final Map<String, BitSet> idx = new LinkedHashMap<>();
        final boolean selection = Region.isSelection(editor);
        if (selection && r != null)
            for (Line l : Region.selectedLines(editor)) {
                final ChangedLine c = r.changed(old).get(l.lineNumber());
                if (c != null)
                    idx.computeIfAbsent(c.change().id(), k -> new BitSet()).set(c.i());
            }
        else if (r != null) {
            final Change c = r.changeAt(old, false, editor.getDotLineNumber());
            if (c != null)
                idx.putAll(c.picks());
        }
        if (idx.isEmpty()) {
            editor.status(nothingHere.get());
            return;
        }
        status.picks().toggleLines(f.key(), f.repository(), f.section(), f.item(), idx);
        if (selection)
            editor.setMark(null);
        status.showPicks();
    }

    /** Picks what each of boxes is if on, else unpicks it. */
    void set(List<Box> boxes, boolean on) {
        final Of f = of.get();
        if (f == null)
            return;
        final Map<String, BitSet> idx = new LinkedHashMap<>();
        for (Box b : boxes)
            for (Map.Entry<String, BitSet> e : b.lines().entrySet())
                idx.computeIfAbsent(e.getKey(), k -> new BitSet()).or(e.getValue());
        status.picks().setLines(f.key(), f.repository(), f.section(), f.item(), idx, on);
        status.showPicks();
    }

    /**
     * How much of the changes whose signs are on the new side's rows from
     * to to is picked; null for none there. A fold's box, in the file's
     * own buffer.
     */
    PickState signsState(int from, int to) {
        final Map<String, BitSet> idx = signs(from, to);
        return idx == null ? null : state(of.get(), idx);
    }

    // How much of the lines idx, by hunk id, of f is picked.
    private PickState state(Of f, Map<String, BitSet> idx) {
        return status.picks().linesState(f.key(), idx);
    }

    /** Picks the changes whose signs are on the new side's rows from to to if on, else unpicks them. */
    void setSigns(int from, int to, boolean on) {
        setSigns(List.of(new int[] { from, to }), on);
    }

    /** Picks the changes whose signs are on the new side's rows in each of ranges, from and to, if on, else unpicks them. */
    void setSigns(List<int[]> ranges, boolean on) {
        final Of f = of.get();
        if (f == null)
            return;
        final Map<String, BitSet> idx = new LinkedHashMap<>();
        for (int[] range : ranges)
            rows(f).signs(range[0], range[1], idx);
        if (idx.isEmpty())
            return;
        status.picks().setLines(f.key(), f.repository(), f.section(), f.item(), idx, on);
        status.showPicks();
    }

    /** The changed lines, by hunk id, whose signs are on rows from to to; null for none. */
    Map<String, BitSet> signs(int from, int to) {
        final Of f = of.get();
        if (f == null)
            return null;
        final Map<String, BitSet> out = new LinkedHashMap<>();
        rows(f).signs(from, to, out);
        return out.isEmpty() ? null : out;
    }

    // f's rows, or null for no file.
    private Rows rows(Of f) {
        if (f == null)
            return null;
        if (f.item().diff() != rowsOf) {
            rowsOf = f.item().diff();
            rows = Rows.of(status.picks().ids(f.item().diff()));
        }
        return rows;
    }

    // A hunk's rows on each side, context and all, from start to before
    // end, and its changes.
    private record HunkRows(int oldStart, int oldEnd, int newStart, int newEnd, List<Change> changes) {
        boolean holds(boolean old, int n) {
            return old ? n >= oldStart && n < oldEnd : n >= newStart && n < newEnd;
        }
    }

    // A run of the hunk id's changed lines, lines their indexes, and its
    // rows: on the old side, those removed, or with none, the row it is
    // before; on the new, those added, or the row it is before; and those
    // of its signs.
    private record Change(String id, BitSet lines, int oldFirst, int oldLast, int newFirst, int newLast, int signFirst,
            int signLast) {
        Map<String, BitSet> picks() {
            return Map.of(id, lines);
        }

        int first(boolean old, boolean signs) {
            return signs ? signFirst : old ? oldFirst : newFirst;
        }

        int last(boolean old, boolean signs) {
            return signs ? signLast : old ? oldLast : newLast;
        }

        // How far its rows on a side are from row n, 0 if they hold it.
        int from(boolean old, boolean signs, int n) {
            return n < first(old, signs) ? first(old, signs) - n : Math.max(0, n - last(old, signs));
        }
    }

    // A change's line, i its index in its hunk.
    private record ChangedLine(Change change, int i) {}

    // A diff's hunks' rows: the changes by the rows of their boxes, on the
    // new side if they add lines, else on the old; their changed lines by
    // their rows, and those with boxes, the added and the removed with no
    // line added for them; by their signs' rows, the changed lines with a
    // sign on each and the changes with their first there; and the changes
    // by each row from their box to their last line, on each side and as
    // signs draw them.
    private record Rows(List<HunkRows> hunks, Map<Integer, Change> oldFirsts, Map<Integer, Change> newFirsts,
            Map<Integer, ChangedLine> oldChanged, Map<Integer, ChangedLine> newChanged,
            Map<Integer, ChangedLine> oldBoxes, Map<Integer, List<ChangedLine>> bySign, Map<Integer, Change> signFirsts,
            Map<Integer, Change> oldRuns, Map<Integer, Change> newRuns, Map<Integer, Change> signRuns) {
        Map<Integer, Change> firsts(boolean old) {
            return old ? oldFirsts : newFirsts;
        }

        Map<Integer, ChangedLine> changed(boolean old) {
            return old ? oldChanged : newChanged;
        }

        // Adds to out the changed lines, by hunk id, whose signs are on
        // rows from to to.
        void signs(int from, int to, Map<String, BitSet> out) {
            for (int row = from; row <= to; row++)
                for (ChangedLine c : bySign.getOrDefault(row, List.of()))
                    out.computeIfAbsent(c.change().id(), k -> new BitSet()).set(c.i());
        }

        // The change at row n of the old side if old, else the new, or as
        // signs draw them; else the nearest it in the hunk holding n; null
        // for none.
        Change changeAt(boolean old, boolean signs, int n) {
            for (HunkRows h : hunks)
                for (Change c : h.changes())
                    if (c.from(old, signs, n) == 0)
                        return c;
            for (HunkRows h : hunks)
                if (h.holds(old && !signs, n)) {
                    Change nearest = null;
                    for (Change c : h.changes())
                        if (nearest == null || c.from(old, signs, n) < nearest.from(old, signs, n))
                            nearest = c;
                    return nearest;
                }
            return null;
        }

        static Rows of(Map<String, Hunk> ids) {
            final Rows r = new Rows(new ArrayList<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>());
            for (Map.Entry<String, Hunk> e : ids.entrySet()) {
                final Hunk h = e.getValue();
                final int oldStart = h.oldRow();
                final int newStart = h.newRow();
                final BitSet paired = paired(h);
                final int[] signRows = StatusPicks.signRows(h);
                final List<Change> changes = new ArrayList<>();
                for (Hunk.Run run : h.runs()) {
                    final int oldLast = run.oldRow() + run.removedCount() - 1;
                    final int newLast = run.newRow() + run.addedCount() - 1;
                    int signFirst = Integer.MAX_VALUE;
                    int signLast = -1;
                    for (int k = run.start(); k < run.end(); k++) {
                        signFirst = Math.min(signFirst, signRows[k]);
                        signLast = Math.max(signLast, signRows[k]);
                    }
                    final BitSet lines = new BitSet();
                    lines.set(run.start(), run.end());
                    final Change c = new Change(e.getKey(),
                            lines,
                            run.oldRow(),
                            Math.max(run.oldRow(), oldLast),
                            run.newRow(),
                            Math.max(run.newRow(), newLast),
                            signFirst,
                            signLast);
                    changes.add(c);
                    // Its lines by their rows.
                    for (int k = run.start(); k < run.end(); k++) {
                        final ChangedLine l = new ChangedLine(c, k);
                        if (k < run.added()) {
                            final int row = run.oldRow() + k - run.start();
                            r.oldChanged().put(row, l);
                            if (!paired.get(k))
                                r.oldBoxes().put(row, l);
                        } else
                            r.newChanged().put(run.newRow() + k - run.added(), l);
                        r.bySign().computeIfAbsent(signRows[k], x -> new ArrayList<>()).add(l);
                    }
                    if (run.addedCount() > 0) {
                        r.newFirsts().putIfAbsent(run.newRow(), c);
                        run(r.newRuns(), c, run.newRow(), newLast);
                    } else {
                        r.oldFirsts().putIfAbsent(run.oldRow(), c);
                        run(r.oldRuns(), c, run.oldRow(), oldLast);
                    }
                    r.signFirsts().putIfAbsent(signFirst, c);
                    run(r.signRuns(), c, signFirst, signLast);
                }
                final int oldEnd = oldStart + h.oldCount();
                r.hunks().add(new HunkRows(oldStart, oldEnd, newStart, newStart + h.newCount(), changes));
            }
            return r;
        }

        // runs given c's rows first to last, if more than one.
        private static void run(Map<Integer, Change> runs, Change c, int first, int last) {
            for (int n = first; last > first && n <= last; n++)
                runs.putIfAbsent(n, c);
        }

        // h's removed lines with lines added for them.
        private static BitSet paired(Hunk h) {
            final BitSet out = new BitSet();
            for (Hunk.Run run : h.runs())
                out.set(run.start(), run.start() + run.paired());
            return out;
        }
    }
}
