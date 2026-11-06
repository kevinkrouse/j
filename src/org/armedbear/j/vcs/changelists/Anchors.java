/*
 * Anchors.java
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

package org.armedbear.j.vcs.changelists;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Change;
import org.armedbear.j.vcs.changelists.Changelists.Part;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;

/**
 * A file's changes as its diff has them now put in the lists its kept
 * changes were in: each to the kept change with its lines (the same hash),
 * twins in order down the file, nearest by their rows in the index, which
 * edits in the work tree don't move; else to the one it overlaps most,
 * in the index, else in the work tree, its parts dropped; else to the
 * active list. A file kept whole has every change in its list, and a file
 * deleted, in the list of most of its kept lines.
 */
public final class Anchors {
    private Anchors() {}

    /** A change, run, of a hunk of a file's diff, in list, but for its parts in others. */
    public record Placed(Hunk hunk, Hunk.Run run, String list, List<Part> parts) {}

    /** The hash of run's removed and added lines, which a change kept is found by. */
    public static String hash(Hunk hunk, Hunk.Run run) {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (int i = run.start(); i < run.end(); i++) {
            final DiffLine l = hunk.lines().get(i);
            md.update((byte) l.kind());
            // The diff's text is its bytes, each a char.
            md.update(l.text().getBytes(StandardCharsets.ISO_8859_1));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest(), 0, 16);
    }

    // A change now and its hash.
    private record Now(Hunk hunk, Hunk.Run run, String hash) {}

    /**
     * The changes of diff, path's unstaged diff, or with from, the path git
     * renamed it from, each placed in its list as c keeps them; none for no
     * diff. The diff must be the whole file's, whitespace shown: a change
     * it leaves out is dropped once its placing is kept.
     */
    public static List<Placed> place(Changelists c, String path, String from, FileDiff diff) {
        if (diff == null)
            return List.of();
        Assignment a = c.assignment(path);
        if (a == null && from != null)
            a = c.assignment(from);
        final List<Now> now = new ArrayList<>();
        for (Hunk h : diff.hunks())
            for (Hunk.Run r : h.runs())
                now.add(new Now(h, r, hash(h, r)));
        final List<Placed> out = new ArrayList<>();
        // Kept whole, or deleted: one list for all.
        final String all = a == null ? c.active() : a.whole() != null ? a.whole() : diff.isDeleted() ? most(a) : null;
        if (all != null) {
            for (Now n : now)
                out.add(new Placed(n.hunk(), n.run(), all, List.of()));
            return out;
        }
        final Map<Now, Change> found = matched(now, a.changes());
        for (Now n : now) {
            final Change same = found.get(n);
            if (same != null) {
                out.add(new Placed(n.hunk(), n.run(), same.list(), same.parts()));
                continue;
            }
            final Change over = overlapping(a.changes(), n.run());
            out.add(new Placed(n.hunk(), n.run(), over != null ? over.list() : c.active(), List.of()));
        }
        return out;
    }

    /** The list of the k-th line p adds, a part's if one has it, else p's. */
    public static String listOf(Placed p, int k) {
        for (Part part : p.parts())
            if (k >= part.from() && k < part.to())
                return part.list();
        return p.list();
    }

    /**
     * run of hunk, whose added lines are in lists, one for each: in the
     * list of most of them, the rest its parts.
     */
    public static Placed withLists(Hunk hunk, Hunk.Run run, String[] lists) {
        final Map<String, Integer> count = new HashMap<>();
        for (String l : lists)
            count.merge(l, 1, Integer::sum);
        String most = lists[0];
        for (Map.Entry<String, Integer> e : count.entrySet())
            if (e.getValue() > count.get(most))
                most = e.getKey();
        final List<Part> parts = new ArrayList<>();
        for (int k = 0; k < lists.length;) {
            int end = k + 1;
            while (end < lists.length && lists[end].equals(lists[k]))
                end++;
            if (!lists[k].equals(most))
                parts.add(new Part(k, end, lists[k]));
            k = end;
        }
        return new Placed(hunk, run, most, parts);
    }

    /**
     * placed, kept again: kept whole, as it was; else each change with its
     * hash and rows now.
     */
    public static Assignment assignment(List<Placed> placed, Assignment kept) {
        if (kept != null && kept.whole() != null)
            return kept;
        final List<Change> cs = new ArrayList<>();
        for (Placed p : placed) {
            final Hunk.Run r = p.run();
            cs.add(
                new Change(hash(p.hunk(), r),
                        r.newRow(),
                        r.addedCount(),
                        r.oldRow(),
                        r.removedCount(),
                        p.list(),
                        p.parts()));
        }
        return Assignment.of(cs);
    }

    // The kept change each change now has the lines of: of twins, those
    // now and those kept matched in order down the file, as many as can be,
    // their rows in the index nearest.
    private static Map<Now, Change> matched(List<Now> now, List<Change> kept) {
        final Map<String, List<Now>> nowBy = new LinkedHashMap<>();
        for (Now n : now)
            nowBy.computeIfAbsent(n.hash(), k -> new ArrayList<>()).add(n);
        final Map<String, List<Change>> keptBy = new HashMap<>();
        for (Change k : kept)
            keptBy.computeIfAbsent(k.hash(), x -> new ArrayList<>()).add(k);
        final Map<Now, Change> out = new HashMap<>();
        for (Map.Entry<String, List<Now>> e : nowBy.entrySet()) {
            final List<Now> ns = e.getValue();
            final List<Change> ks = new ArrayList<>(keptBy.getOrDefault(e.getKey(), List.of()));
            ks.sort(Comparator.comparingInt(Change::oldRow).thenComparingInt(Change::row));
            // best[i][j]: of ns' first i and ks' first j, the most matched,
            // then the least distance; each step skips one or matches both.
            final int[][] count = new int[ns.size() + 1][ks.size() + 1];
            final long[][] distance = new long[ns.size() + 1][ks.size() + 1];
            for (int i = 0; i <= ns.size(); i++)
                for (int j = 0; j <= ks.size(); j++) {
                    if (i == 0 || j == 0)
                        continue;
                    int bc = count[i - 1][j];
                    long bd = distance[i - 1][j];
                    if (count[i][j - 1] > bc || count[i][j - 1] == bc && distance[i][j - 1] < bd) {
                        bc = count[i][j - 1];
                        bd = distance[i][j - 1];
                    }
                    final int mc = count[i - 1][j - 1] + 1;
                    final long md = distance[i - 1][j - 1] + apart(ns.get(i - 1), ks.get(j - 1));
                    if (mc > bc || mc == bc && md < bd) {
                        bc = mc;
                        bd = md;
                    }
                    count[i][j] = bc;
                    distance[i][j] = bd;
                }
            for (int i = ns.size(), j = ks.size(); i > 0 && j > 0;) {
                final long md = distance[i - 1][j - 1] + apart(ns.get(i - 1), ks.get(j - 1));
                if (count[i][j] == count[i - 1][j - 1] + 1 && distance[i][j] == md) {
                    out.put(ns.get(i - 1), ks.get(j - 1));
                    i--;
                    j--;
                } else if (count[i][j] == count[i - 1][j] && distance[i][j] == distance[i - 1][j])
                    i--;
                else
                    j--;
            }
        }
        return out;
    }

    // How far apart n and k are in the index.
    private static int apart(Now n, Change k) {
        return Math.abs(k.oldRow() - n.run().oldRow());
    }

    // The kept change r overlaps most in the index, else in the work tree;
    // null for none.
    private static Change overlapping(List<Change> kept, Hunk.Run r) {
        for (boolean old : new boolean[] { true, false }) {
            Change best = null;
            int most = 0;
            for (Change k : kept) {
                final int o = old
                        ? overlap(k.oldRow(), k.oldRows(), r.oldRow(), r.removedCount())
                        : overlap(k.row(), k.rows(), r.newRow(), r.addedCount());
                if (o > most) {
                    most = o;
                    best = k;
                }
            }
            if (best != null)
                return best;
        }
        return null;
    }

    // How many rows two runs of rows share, each at least one row: with
    // none, the row after where its lines would be.
    private static int overlap(int row, int rows, int otherRow, int otherRows) {
        final int start = Math.max(row, otherRow);
        final int end = Math.min(row + Math.max(1, rows), otherRow + Math.max(1, otherRows));
        return Math.max(0, end - start);
    }

    /** The list of most of a's kept rows, the default if none. */
    public static String most(Assignment a) {
        final Map<String, Integer> rows = new HashMap<>();
        for (Change k : a.changes())
            rows.merge(k.list(), Math.max(1, Math.max(k.rows(), k.oldRows())), Integer::sum);
        String best = null;
        for (Map.Entry<String, Integer> e : rows.entrySet())
            if (best == null || e.getValue() > rows.get(best))
                best = e.getKey();
        return best != null ? best : Changelists.DEFAULT;
    }
}
