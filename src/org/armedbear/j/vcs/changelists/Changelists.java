/*
 * Changelists.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A repository's changelists, which sort its unstaged changes into named
 * candidate commits: the lists, in order, the default always first; the
 * active one, which a change no list has goes to; and the assignments of
 * files whole, or of their changes, by path. Immutable: each change makes
 * another.
 */
public record Changelists(List<Changelist> lists, String active, Map<String, Assignment> files) {
    /** The list that is always there, which a deleted list's changes go to. */
    public static final String DEFAULT = "Default";

    /** A list: its name and the draft of its commit's message. */
    public record Changelist(String name, String comment) {}

    /**
     * A file's assignment: whole, to the list whole names; else its changes,
     * each to a list, any other change of it to the active list.
     */
    public record Assignment(String whole, List<Change> changes) {
        public Assignment {
            changes = List.copyOf(changes);
        }

        public static Assignment whole(String list) {
            return new Assignment(list, List.of());
        }

        public static Assignment of(List<Change> changes) {
            return new Assignment(null, changes);
        }
    }

    /**
     * A change of a file, a run of its diff's lines, as it was when kept:
     * the hash of its removed and added lines (Anchors.hash); its rows in
     * the work tree, from row, rows of them, none for lines only removed,
     * and in the index, from oldRow, oldRows of them, none for lines only
     * added; its list, but for its parts in others.
     */
    public record Change(String hash, int row, int rows, int oldRow, int oldRows, String list, List<Part> parts) {
        public Change {
            parts = List.copyOf(parts);
        }
    }

    /** Rows from to before to of a change, counted from its first, in list. */
    public record Part(int from, int to, String list) {}

    public Changelists {
        lists = List.copyOf(lists);
        // In the order given, as the file has them.
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    /** No lists but the default, which is active, and nothing assigned. */
    public static Changelists initial() {
        return new Changelists(List.of(new Changelist(DEFAULT, "")), DEFAULT, Map.of());
    }

    /** Why name can't name any changelist, or null if it can. */
    public static String badName(String name) {
        return name.chars().anyMatch(Character::isISOControl)
                ? "A changelist's name can't have control characters"
                : null;
    }

    /** Why a new list can't be named name: a bad name, or one a list has; null if it can. */
    public String refusedName(String name) {
        final String bad = badName(name);
        return bad != null ? bad : list(name) != null ? "There is a changelist " + name + " already" : null;
    }

    /** The list named name, or null. */
    public Changelist list(String name) {
        for (Changelist l : lists)
            if (l.name().equals(name))
                return l;
        return null;
    }

    /** path's assignment, or null for none. */
    public Assignment assignment(String path) {
        return files.get(path);
    }

    /** With a new list, name, after the others; this if there is one already. */
    public Changelists withList(String name) {
        if (list(name) != null)
            return this;
        final List<Changelist> ls = new ArrayList<>(lists);
        ls.add(new Changelist(name, ""));
        return new Changelists(ls, active, files);
    }

    /** With name, a list, active. */
    public Changelists withActive(String name) {
        if (list(name) == null)
            throw new IllegalArgumentException("no list " + name);
        return new Changelists(lists, name, files);
    }

    /** With the list name's draft comment. */
    public Changelists withComment(String name, String comment) {
        final List<Changelist> ls = new ArrayList<>();
        for (Changelist l : lists)
            ls.add(l.name().equals(name) ? new Changelist(name, comment) : l);
        return new Changelists(ls, active, files);
    }

    /** With the list from renamed to, its assignments and active with it. */
    public Changelists renamed(String from, String to) {
        if (from.equals(DEFAULT) || list(to) != null)
            throw new IllegalArgumentException("can't rename " + from + " to " + to);
        final List<Changelist> ls = new ArrayList<>();
        for (Changelist l : lists)
            ls.add(l.name().equals(from) ? new Changelist(to, l.comment()) : l);
        return new Changelists(ls, active.equals(from) ? to : active, relisted(from, to));
    }

    /** Without the list name, its changes the default's, and active then if it was. */
    public Changelists without(String name) {
        if (name.equals(DEFAULT))
            throw new IllegalArgumentException("the default list stays");
        final List<Changelist> ls = new ArrayList<>(lists);
        ls.removeIf(l -> l.name().equals(name));
        return new Changelists(ls, active.equals(name) ? DEFAULT : active, relisted(name, DEFAULT));
    }

    /** With path assigned as a says, or not at all for null. */
    public Changelists withFile(String path, Assignment a) {
        final Map<String, Assignment> fs = new LinkedHashMap<>(files);
        if (a == null)
            fs.remove(path);
        else
            fs.put(path, a);
        return new Changelists(lists, active, fs);
    }

    /** With from's assignment path to's, as git renamed the file. */
    public Changelists withFileRenamed(String from, String to) {
        final Assignment a = files.get(from);
        return a == null ? this : withFile(from, null).withFile(to, a);
    }

    // The assignments, those to from to's.
    private Map<String, Assignment> relisted(String from, String to) {
        final Map<String, Assignment> fs = new LinkedHashMap<>();
        for (Map.Entry<String, Assignment> e : files.entrySet()) {
            final Assignment a = e.getValue();
            final List<Change> cs = new ArrayList<>();
            for (Change c : a.changes()) {
                final List<Part> ps = new ArrayList<>();
                for (Part p : c.parts())
                    ps.add(p.list().equals(from) ? new Part(p.from(), p.to(), to) : p);
                cs.add(
                    new Change(c.hash(),
                            c.row(),
                            c.rows(),
                            c.oldRow(),
                            c.oldRows(),
                            c.list().equals(from) ? to : c.list(),
                            ps));
            }
            fs.put(e.getKey(), new Assignment(from.equals(a.whole()) ? to : a.whole(), cs));
        }
        return fs;
    }
}
