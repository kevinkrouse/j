/*
 * GitSnapshot.java
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

package org.armedbear.j.vcs.git;

import java.util.List;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Snapshot;

/** A git repository's state: its changed files, branch, upstream and stash. */
public final class GitSnapshot extends Snapshot {
    /** What git is in the middle of. */
    public enum Operation {
        NONE, MERGE, REBASE, AM, CHERRY_PICK, REVERT, BISECT
    }

    private final String head;
    private final String branch;
    private final int stashes;
    private final Operation operation;

    /**
     * @param head     HEAD's commit, or null before the first commit
     * @param branch   the branch checked out, or null if HEAD is detached
     */
    public GitSnapshot(
            List<FileChange> changes,
            String head,
            String branch,
            Relation upstream,
            Relation push,
            Relation parent,
            int stashes,
            Operation operation) {
        super(changes, upstream, push, parent);
        this.head = head;
        this.branch = branch;
        this.stashes = stashes;
        this.operation = operation;
    }

    /** This snapshot with its push target and parent. */
    GitSnapshot with(Relation push, Relation parent) {
        return new GitSnapshot(changes(), head, branch, upstream(), push, parent, stashes, operation);
    }

    public String head() {
        return head;
    }

    public String branch() {
        return branch;
    }

    public boolean isDetached() {
        return branch == null;
    }

    /** How many stashes there are, or -1 if this git does not say. */
    public int stashes() {
        return stashes;
    }

    public Operation operation() {
        return operation;
    }
}
