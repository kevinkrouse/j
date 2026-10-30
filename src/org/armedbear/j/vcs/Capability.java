/*
 * Capability.java
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

/** What a backend's system can do, so shared tools leave out what it can't. */
public enum Capability {
    /** An index between the working tree and the next commit. */
    STAGING,
    STASH,
    BRANCHES,
    /** Commits with more than one parent. */
    MERGE_GRAPH,
    /** Changelists that split a file's changes, kept by j. */
    HUNK_CHANGELISTS,
    /** The system's own changelists, of whole files. */
    NATIVE_CHANGELISTS,
    /** Committing part of a file's changes. */
    PARTIAL_COMMIT,
    /** The commit each line was last changed in. */
    BLAME
}
