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
import org.armedbear.j.vcs.VcsRepository;

/**
 * A commit as the log lists it.
 *
 * @param id its full id
 * @param parents its parents' ids, the first first; none for a root
 * @param time when it was authored, in seconds since the epoch
 * @param refs the branches, tags and HEAD at it
 * @param graphParents the parents the graph joins it to: in a path's log,
 *     the commits listed that it comes from, not its own parents
 * @param committed when it was committed, in seconds since the epoch: the
 *     order of several repositories' commits
 * @param repository the repository it is in; null in tests of none
 */
public record LogCommit(String id, List<String> parents, String author, String email, long time, List<LogRef> refs,
        String subject, List<String> graphParents, long committed, VcsRepository repository) {
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
        this(id, parents, author, email, time, refs, subject, parents, time, null);
    }

    /** A commit's id as short as titles show it. */
    public static String shortId(String id) {
        return id.length() > 7 ? id.substring(0, 7) : id;
    }

    /** It in repository. */
    public LogCommit in(VcsRepository repository) {
        return new LogCommit(id, parents, author, email, time, refs, subject, graphParents, committed, repository);
    }

    /** Whether it is other: the same commit in the same repository. */
    public boolean isSame(LogCommit other) {
        return other != null && id.equals(other.id) && repository == other.repository;
    }

    /** The first parent, or null for a root. */
    public String firstParent() {
        return parents.isEmpty() ? null : parents.get(0);
    }

    public boolean isMerge() {
        return parents.size() > 1;
    }

    /** Whether it is a stash, badged with its name, as the log reads one. */
    public boolean isStash() {
        for (LogRef r : refs)
            if (r.kind() == LogRef.Kind.STASH)
                return true;
        return false;
    }
}
