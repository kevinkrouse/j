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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
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
        final List<Placed> next = started ? follow(now, c, kept) : Anchors.place(c, path, null, diff(now));
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
        if (saved && (c.lists().size() > 1 || kept != null))
            keep(kept);
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
    private void keep(Assignment kept) {
        final Assignment a = placed.isEmpty() ? null : Anchors.assignment(placed, kept);
        if (a == null ? kept != null : !a.equals(kept))
            repository.update(c -> c.withFile(path, a));
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
