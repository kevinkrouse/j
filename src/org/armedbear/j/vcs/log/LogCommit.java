/*
 * LogCommit.java
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

import java.util.List;

/**
 * A commit as the log lists it.
 *
 * @param id its full id
 * @param parents its parents' ids, the first first; none for a root
 * @param time when it was authored, in seconds since the epoch
 * @param refs the branches, tags and HEAD at it
 * @param graphParents the parents the graph joins it to: in a path's log,
 *     the commits listed that it comes from, not its own parents
 */
public record LogCommit(String id, List<String> parents, String author, String email, long time, List<LogRef> refs,
        String subject, List<String> graphParents) {
    public LogCommit {
        parents = List.copyOf(parents);
        refs = List.copyOf(refs);
        graphParents = List.copyOf(graphParents);
    }

    public LogCommit(
            String id,
            List<String> parents,
            String author,
            String email,
            long time,
            List<LogRef> refs,
            String subject) {
        this(id, parents, author, email, time, refs, subject, parents);
    }

    /** The first parent, or null for a root. */
    public String firstParent() {
        return parents.isEmpty() ? null : parents.get(0);
    }

    public boolean isMerge() {
        return parents.size() > 1;
    }
}
