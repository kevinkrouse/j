/*
 * ListedChanges.java
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.armedbear.j.vcs.changelists.Anchors;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Change;
import org.armedbear.j.vcs.changelists.Changelists.Changelist;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Member;

/**
 * A repository's unstaged files sorted into its changelists, as the status
 * shows them: each list, in order, with its files, a file split across
 * lists in each, with its lines there. A file whose diff is unread is in
 * its list kept whole, or of most of its kept changes, else the active one.
 */
record ListedChanges(List<Changelist> lists, String active, Map<String, List<Member>> members) {
    /** The unstaged files, files, in c's lists, their diffs' hunks by id as ids gives them. */
    static ListedChanges of(Changelists c, List<FileItem> files, Function<FileDiff, Map<String, Hunk>> ids) {
        final Map<String, List<Member>> members = new LinkedHashMap<>();
        for (Changelist l : c.lists())
            members.put(l.name(), new ArrayList<>());
        for (FileItem f : files) {
            final Map<String, Map<String, BitSet>> byList = lines(c, f, ids);
            if (byList.size() == 1) {
                members.get(byList.keySet().iterator().next()).add(new Member(f, null));
                continue;
            }
            for (Map.Entry<String, Map<String, BitSet>> e : byList.entrySet())
                members.get(e.getKey()).add(new Member(f, e.getValue()));
        }
        return new ListedChanges(c.lists(), c.active(), members);
    }

    // f's changed lines by list, then hunk id; a list with all of f its own.
    private static Map<String, Map<String, BitSet>> lines(
            Changelists c,
            FileItem f,
            Function<FileDiff, Map<String, Hunk>> ids) {
        final Map<String, Map<String, BitSet>> out = new LinkedHashMap<>();
        if (f.diff() == null || f.diff().hunks().isEmpty()) {
            out.put(listOf(c, f), Map.of());
            return out;
        }
        final Map<Hunk, String> idOf = new IdentityHashMap<>();
        for (Map.Entry<String, Hunk> e : ids.apply(f.diff()).entrySet())
            idOf.put(e.getValue(), e.getKey());
        for (Placed p : Anchors.place(c, f.path(), f.change().origPath(), f.diff())) {
            final Hunk.Run r = p.run();
            final String id = idOf.get(p.hunk());
            for (int i = r.start(); i < r.end(); i++) {
                // An added line's own list; a removed one's, its added one's
                // if it has one, else the change's.
                final int added = i < r.added() ? (i - r.start() < r.paired() ? i - r.start() : -1) : i - r.added();
                final String list = known(c, added < 0 ? p.list() : Anchors.listOf(p, added));
                out.computeIfAbsent(list, k -> new LinkedHashMap<>()).computeIfAbsent(id, k -> new BitSet()).set(i);
            }
        }
        // In a list's order, as the lists are shown.
        final Map<String, Map<String, BitSet>> ordered = new LinkedHashMap<>();
        for (Changelist l : c.lists())
            if (out.containsKey(l.name()))
                ordered.put(l.name(), out.get(l.name()));
        return ordered;
    }

    // The list of a file whose changes aren't read: its kept whole, of most
    // of its kept rows, else the active one if none are kept.
    static String listOf(Changelists c, FileItem f) {
        Assignment a = c.assignment(f.path());
        if (a == null && f.change().origPath() != null)
            a = c.assignment(f.change().origPath());
        if (a == null)
            return c.active();
        return known(c, a.whole() != null ? a.whole() : Anchors.most(a));
    }

    // name, if c has the list, else the default, as one deleted since.
    private static String known(Changelists c, String name) {
        return c.list(name) != null ? name : Changelists.DEFAULT;
    }
}
