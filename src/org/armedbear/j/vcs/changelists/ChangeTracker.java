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
 * a change with lines that were in a list stays in it, its new lines in the
 * list of those beside them; one with none, brought back by undo, in the
 * list it had, found by its lines; else the active list, or the file's if
 * it is kept whole. Placed first as the lists keep the file, and kept again
 * as the file is saved. Told of the changes each time the file's signs are
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

    /** The changes now placed in lists; kept if saved, the buffer as its file is. */
    public void update(List<Now> now, boolean saved) {
        final Changelists c = repository.lists();
        final Assignment kept = c.assignment(path);
        final List<Placed> next = known(started ? follow(now, c, kept) : Anchors.place(c, path, null, diff(now)), c);
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
    // else the file's if kept whole, else the active list.
    private List<Placed> follow(List<Now> now, Changelists c, Assignment kept) {
        final String otherwise = kept != null && kept.whole() != null ? kept.whole() : c.active();
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
            // A new line in the list of the nearest above it, else below.
            for (int k = 1; k < lists.length; k++)
                if (lists[k] == null)
                    lists[k] = lists[k - 1];
            for (int k = lists.length - 2; k >= 0; k--)
                if (lists[k] == null)
                    lists[k] = lists[k + 1];
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
    private boolean keep(Assignment kept) {
        // Kept whole while every change is in its list.
        final boolean whole = kept != null
                && kept.whole() != null
                && placed.stream().allMatch(p -> p.list().equals(kept.whole()) && p.parts().isEmpty());
        final Assignment a = placed.isEmpty() ? null : Anchors.assignment(placed, whole ? kept : null);
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
