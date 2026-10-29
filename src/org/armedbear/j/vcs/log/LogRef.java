/*
 * LogRef.java
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

package org.armedbear.j.vcs.log;

/**
 * A branch, tag, stash or HEAD at a commit, by its short name: "main",
 * "origin/main", "v1.0", "stash@{0}".
 *
 * @param head whether HEAD is it: the branch checked out, or HEAD itself
 *     when detached
 */
public record LogRef(String name, Kind kind, boolean head) {
    public enum Kind {
        HEAD, BRANCH, REMOTE, TAG, STASH
    }
}
