/*
 * Snapshot.java
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

package org.armedbear.j.vcs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A repository's state, read as a whole. A backend extends it with what its
 * system has besides changed files: git's branch and stash, say.
 */
public class Snapshot {
    /**
     * Another branch the one checked out is compared with, and how far
     * apart they are: commits only on this one (ahead) and only on the
     * other (behind), both -1 if the other is gone or not yet pushed.
     *
     * @param source where it was found, for a parent; null otherwise
     */
    public record Relation(String name, String source, int ahead, int behind) {
        public boolean gone() {
            return ahead < 0;
        }
    }

    private final List<FileChange> changes;
    private final Map<String, FileChange> byPath = new HashMap<>();
    private final String error;
    private final Relation upstream;
    private final Relation push;
    private final Relation parent;

    public Snapshot(List<FileChange> changes) {
        this(changes, null);
    }

    /** error says why the snapshot is empty, or is null. */
    public Snapshot(List<FileChange> changes, String error) {
        this(changes, error, null, null, null);
    }

    /** The upstream, push target and parent may each be null. */
    protected Snapshot(List<FileChange> changes, Relation upstream, Relation push, Relation parent) {
        this(changes, null, upstream, push, parent);
    }

    private Snapshot(List<FileChange> changes, String error, Relation upstream, Relation push, Relation parent) {
        this.changes = List.copyOf(changes);
        this.error = error;
        this.upstream = upstream;
        this.push = push;
        this.parent = parent;
        for (FileChange change : this.changes)
            byPath.put(change.path(), change);
    }

    public static Snapshot failed(String error) {
        return new Snapshot(List.of(), error);
    }

    public List<FileChange> changes() {
        return changes;
    }

    /** The change to the file at path (relative, '/'-separated), or null. */
    public FileChange change(String path) {
        return byPath.get(path);
    }

    /** Why the system could not be read, or null if it was. */
    public String error() {
        return error;
    }

    /** The branch this one pulls from, or null. */
    public Relation upstream() {
        return upstream;
    }

    /** Where this branch pushes to, which may be its upstream; or null. */
    public Relation push() {
        return push;
    }

    /** The branch this one is to be merged into, or null. */
    public Relation parent() {
        return parent;
    }
}
