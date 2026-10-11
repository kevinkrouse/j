/*
 * FileChange.java
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

/**
 * A file the working copy differs in. Paths are relative to the repository
 * root, separated by '/'.
 *
 * @param origPath the path it was renamed or copied from, or null
 * @param staged   the change staged for the next commit, or null; a system
 *                 with no staging area leaves this null
 * @param unstaged the change in the working tree, or null
 * @param code     the system's own short status ("M ", "??", "UU"), as shown
 */
public record FileChange(String path, String origPath, Kind staged, Kind unstaged, String code) {
    public enum Kind {
        MODIFIED, ADDED, DELETED, RENAMED, COPIED, TYPE_CHANGED, UNTRACKED, IGNORED, CONFLICTED
    }

    public boolean isConflicted() {
        return staged == Kind.CONFLICTED || unstaged == Kind.CONFLICTED;
    }

    public boolean isUntracked() {
        return unstaged == Kind.UNTRACKED;
    }

    public boolean isIgnored() {
        return unstaged == Kind.IGNORED;
    }
}
