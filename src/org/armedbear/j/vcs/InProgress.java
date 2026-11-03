/*
 * InProgress.java
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

/** What a repository is in the middle of that conflicts come from, until it is finished. */
public enum InProgress {
    MERGE("merge", false),
    REBASE("rebase", true),
    PATCH_SERIES("patch series", true),
    CHERRY_PICK("cherry-pick", true),
    REVERT("revert", true),
    /**
     * A stash pop j made that conflicted: continued, it drops the stash;
     * aborted, it is forgotten, the stash kept.
     */
    STASH_POP("stash pop", false),
    /** A stash apply j made that conflicted: over with its last conflict, or forgotten. */
    STASH_APPLY("stash apply", false);

    /** Its name in messages: "the rebase". */
    public final String label;
    /** Whether it has steps, one of which can be skipped. */
    public final boolean steps;

    InProgress(String label, boolean steps) {
        this.label = label;
        this.steps = steps;
    }

    /** Whether it is a stash's, which j finishes itself. */
    public boolean isStash() {
        return this == STASH_POP || this == STASH_APPLY;
    }
}
