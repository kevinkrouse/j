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
    private final List<FileChange> changes;
    private final Map<String, FileChange> byPath = new HashMap<>();
    private final String error;

    public Snapshot(List<FileChange> changes) {
        this(changes, null);
    }

    /** error says why the snapshot is empty, or is null. */
    public Snapshot(List<FileChange> changes, String error) {
        this.changes = List.copyOf(changes);
        this.error = error;
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
}
