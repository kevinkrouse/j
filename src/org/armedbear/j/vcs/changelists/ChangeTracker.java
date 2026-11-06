/*
 * ChangeTracker.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Part;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;

/**
 * An open file's changes in their lists as it is edited, IntelliJ's rule:
 * a change with lines that were in a list stays in it, a new line between
 * two of a list's in it, one at its edge the active list's; one with none,
 * brought back by undo, in the list it had, found by its lines; else the
 * active list. Placed first as the lists keep the file, and kept again,
 * change by change, as the file is saved. Told of the changes each time the file's signs are
 * worked out. On the event thread.
 */
public final class ChangeTracker {
    /**
     * A change now: run of hunk, the buffer's lines it adds, and with none,
     * the line its removed lines were under.
     */
    public record Now(Hunk hunk, Hunk.Run run, List<Line> lines, Line under) {}

    // How many changes gone it remembers for undo.
    private static final int GONE = 200;

    private final RepositoryLists repository;
    private final String path;
    // The lists of the lines changes add, and of the lines changes of lines
    // only removed are under, as last placed.
    private final Map<Line, String> lineLists = new IdentityHashMap<>();
    private final Map<Line, String> underLists = new IdentityHashMap<>();
    // Changes gone, by hash, the latest last, for an undo that brings one back.
    private final Map<String, Placed> gone = new LinkedHashMap<>();
    private List<Placed> placed = List.of();
    // The changes as last told, which placed is of, and whether the buffer
    // was then as its file.
    private List<Now> now = List.of();
    private boolean saved;
    // The texts, by line, of the lines in and beside the changes as last told.
    private final Map<Line, String> seen = new IdentityHashMap<>();
    private boolean started;

    public ChangeTracker(RepositoryLists repository, String path) {
        this.repository = repository;
        this.path = path;
    }

    /** The list of the change that adds line, or that lines only removed are under it; null for none. */
    public String listOf(Line line) {
        final String l = lineLists.get(line);
        return l != null ? l : underLists.get(line);
    }

    /** The changes as last placed. */
    public List<Placed> placed() {
        return placed;
    }

    /** Places the changes again from the lists as kept, as when the file was opened, at the next update. */
    public void reload() {
        started = false;
    }

    /**
     * The changes now placed in lists, lines all the buffer's lines; kept
     * if saved, the buffer as its file is.
     */
    public void update(List<Now> now, boolean saved, List<Line> lines) {
        final Changelists c = repository.lists();
        final Assignment kept = c.assignment(path);
        final List<Placed> next = known(started ? follow(now, c) : Anchors.place(c, path, null, diff(now)), c);
        started = true;
        // Those gone remembered, by their lines.
        final Map<String, Placed> nowByHash = new HashMap<>();
        for (Placed p : next)
            nowByHash.put(Anchors.hash(p.hunk(), p.run()), p);
        for (Placed p : placed) {
            final String hash = Anchors.hash(p.hunk(), p.run());
            if (!nowByHash.containsKey(hash)) {
                gone.remove(hash);
                gone.put(hash, p);
            }
        }
        while (gone.size() > GONE)
            gone.remove(gone.keySet().iterator().next());
        placed(next, now);
        this.saved = saved;
        // The texts of the lines in and beside the changes, a line a diff
        // takes into one by aligning it otherwise, its text the same, known
        // from one edited or new.
        seen.clear();
        for (Now n : now)
            if (!n.lines().isEmpty()) {
                // The change's lines, and as many beside it each way, at
                // least a few: those a diff may align it over.
                final int span = Math.max(3, n.lines().size());
                final int first = n.run().newRow();
                for (int row = Math.max(0, first - span);
                        row < Math.min(lines.size(), first + n.lines().size() + span);
                        row++)
                    seen.put(lines.get(row), lines.get(row).getText());
            }
        if (saved && (c.lists().size() > 1 || kept != null))
            keep(kept);
    }

    /** What a move did: nothing of the lines changed, moved and kept, or moved but not kept. */
    public enum Moved {
        NOTHING, MOVED, NOT_KEPT
    }

    /**
     * Moves to list to the changes of lines, the buffer's as last told, or
     * those lines alone of changes that add more; a change removing lines
     * only, whole, if the line it is under is one of them. Kept if the
     * buffer was as its file, else as it is saved.
     */
    public Moved move(Set<Line> lines, String to) {
        final List<Placed> next = movedTo(lines, to);
        if (next.equals(placed))
            return Moved.NOTHING;
        placed(next, now);
        return !saved || keep(repository.lists().assignment(path)) ? Moved.MOVED : Moved.NOT_KEPT;
    }

    /** Whether moving lines to to would change anything. */
    public boolean wouldMove(Set<Line> lines, String to) {
        return !movedTo(lines, to).equals(placed);
    }

    // The changes as placed, those of lines in to.
    private List<Placed> movedTo(Set<Line> lines, String to) {
        final List<Placed> next = new ArrayList<>(placed);
        for (int i = 0; i < now.size(); i++) {
            final Now n = now.get(i);
            final Placed p = placed.get(i);
            if (n.lines().isEmpty()) {
                if (n.under() != null && lines.contains(n.under()))
                    next.set(i, new Placed(n.hunk(), n.run(), to, List.of()));
                continue;
            }
            final String[] lists = new String[n.lines().size()];
            boolean hit = false;
            for (int k = 0; k < lists.length; k++) {
                hit |= lines.contains(n.lines().get(k));
                lists[k] = lines.contains(n.lines().get(k)) ? to : Anchors.listOf(p, k);
            }
            if (hit)
                next.set(i, Anchors.withLists(n.hunk(), n.run(), lists));
        }
        return next;
    }

    /**
     * The list from renamed to, or deleted, to the default: its changes,
     * those since gone too, in to, as the lists now have them.
     */
    public void relist(String from, String to) {
        final List<Placed> next = new ArrayList<>();
        for (Placed p : placed)
            next.add(relisted(p, from, to));
        placed(next, now);
        gone.replaceAll((hash, p) -> relisted(p, from, to));
    }

    private static Placed relisted(Placed p, String from, String to) {
        final List<Part> parts = new ArrayList<>();
        for (Part part : p.parts())
            parts.add(part.list().equals(from) ? new Part(part.from(), part.to(), to) : part);
        return new Placed(p.hunk(), p.run(), p.list().equals(from) ? to : p.list(), parts);
    }

    // next, its lists those c has: one gone since, as another writer's
    // delete, the default.
    private static List<Placed> known(List<Placed> next, Changelists c) {
        final List<Placed> out = new ArrayList<>();
        for (Placed p : next) {
            Placed q = p;
            if (c.list(q.list()) == null)
                q = relisted(q, q.list(), Changelists.DEFAULT);
            for (Part part : q.parts())
                if (c.list(part.list()) == null)
                    q = relisted(q, part.list(), Changelists.DEFAULT);
            out.add(q);
        }
        return out;
    }

    /** The lines of the change line is of, or the line it is under; none for none. */
    public Set<Line> changeAt(Line line) {
        for (Now n : now)
            if (n.lines().contains(line) || n.lines().isEmpty() && n.under() == line) {
                final Set<Line> out = Collections.newSetFromMap(new IdentityHashMap<>());
                out.addAll(n.lines().isEmpty() ? List.of(line) : n.lines());
                return out;
            }
        return Set.of();
    }

    // next placed, now's changes: their lines' lists known.
    private void placed(List<Placed> next, List<Now> now) {
        lineLists.clear();
        underLists.clear();
        for (int i = 0; i < next.size(); i++) {
            final Placed p = next.get(i);
            final Now n = now.get(i);
            for (int k = 0; k < n.lines().size(); k++)
                lineLists.put(n.lines().get(k), Anchors.listOf(p, k));
            if (n.lines().isEmpty() && n.under() != null)
                underLists.put(n.under(), p.list());
        }
        placed = next;
        this.now = now;
    }

    // The changes now, each in the list of its lines that were in one, of
    // most of them, those in others its parts; else its list when it went;
    // else the active list.
    private List<Placed> follow(List<Now> now, Changelists c) {
        final String otherwise = c.active();
        final List<Placed> out = new ArrayList<>();
        for (Now n : now) {
            if (n.lines().isEmpty()) {
                final String under = n.under() == null ? null : underLists.get(n.under());
                out.add(under != null ? new Placed(n.hunk(), n.run(), under, List.of()) : before(n, otherwise));
                continue;
            }
            final String[] lists = new String[n.lines().size()];
            boolean any = false;
            for (int k = 0; k < lists.length; k++) {
                lists[k] = lineLists.get(n.lines().get(k));
                any |= lists[k] != null;
            }
            if (!any) {
                out.add(before(n, otherwise));
                continue;
            }
            // A line the diff only aligned into the change, there before
            // with the same text, as over a twin blank line or brace, the
            // list of those beside it; a new or edited line inside a list's
            // lines, between two of them, in it; one at either edge,
            // outside, the active list's.
            final String[] known = lists.clone();
            for (int k = 0; k < lists.length; k++) {
                if (known[k] != null)
                    continue;
                final Line l = n.lines().get(k);
                if (l.getText().equals(seen.get(l))) {
                    String near = null;
                    for (int d = 1; near == null && (k - d >= 0 || k + d < lists.length); d++)
                        near = k - d >= 0 && known[k - d] != null ? known[k - d]
                                : k + d < lists.length ? known[k + d]
                                : null;
                    lists[k] = near;
                    continue;
                }
                String above = null;
                for (int j = k - 1; j >= 0 && above == null; j--)
                    above = known[j];
                String below = null;
                for (int j = k + 1; j < lists.length && below == null; j++)
                    below = known[j];
                lists[k] = above != null && above.equals(below) ? above : c.active();
            }
            out.add(Anchors.withLists(n.hunk(), n.run(), lists));
        }
        return out;
    }

    // n in the list it had when it went, with its parts, else in otherwise.
    private Placed before(Now n, String otherwise) {
        final Placed was = gone.get(Anchors.hash(n.hunk(), n.run()));
        return was != null
                ? new Placed(n.hunk(), n.run(), was.list(), was.parts())
                : new Placed(n.hunk(), n.run(), otherwise, List.of());
    }

    // The changes as kept, if not as they were: none, the file unchanged.
    // Kept, if changed: whether it is, or needn't be.
    // Change by change: a file kept whole, as an older store has it, is
    // no longer.
    private boolean keep(Assignment kept) {
        final Assignment a = placed.isEmpty() ? null : Anchors.assignment(placed);
        return a == null
                ? kept == null || repository.update(c -> c.withFile(path, a))
                : a.equals(kept) || repository.update(c -> c.withFile(path, a));
    }

    // The changes now as a diff, each hunk once, in order.
    private FileDiff diff(List<Now> now) {
        final List<Hunk> hunks = new ArrayList<>();
        for (Now n : now)
            if (hunks.isEmpty() || hunks.get(hunks.size() - 1) != n.hunk())
                hunks.add(n.hunk());
        return new FileDiff(List.of(), path, path, false, false, false, false, hunks, false);
    }
}
