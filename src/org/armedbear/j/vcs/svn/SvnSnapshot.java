/*
 * SvnSnapshot.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import java.util.List;
import java.util.Map;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Snapshot;

/** A working copy's changes as svn status gives them, with the changelist each changed file is in. */
final class SvnSnapshot extends Snapshot {
    private final Map<String, String> changelists;

    SvnSnapshot(List<FileChange> changes, Map<String, String> changelists) {
        super(changes, null, null, null);
        this.changelists = Map.copyOf(changelists);
    }

    /** The svn changelist path is in, or null. */
    String changelist(String path) {
        return changelists.get(path);
    }
}
