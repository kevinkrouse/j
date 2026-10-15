/*
 * LogQuery.java
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
 * Which commits a log shows: every branch, tag and HEAD, or the
 * revisions given, narrowed by the filters set. A null filter is unset.
 *
 * @param revisions branches or tags to start from; none for all of them
 * @param since a date the system understands ("2 weeks ago", "2024-01-31")
 * @param grep text the message has
 * @param pickaxe text a change adds or removes
 * @param follow a single path's history through renames
 */
public record LogQuery(List<String> revisions, String author, String path, String since, String grep, String pickaxe,
        boolean follow) {
    /** Everything. */
    public static final LogQuery ALL = new LogQuery(List.of(), null, null, null, null, null, false);

    public LogQuery {
        revisions = List.copyOf(revisions);
    }

    /** A file's history, through its renames. */
    public static LogQuery file(String path) {
        return new LogQuery(List.of(), null, path, null, null, null, true);
    }

    /** Whether anything narrows it. */
    public boolean isFiltered() {
        return !revisions.isEmpty()
                || author != null
                || path != null
                || since != null
                || grep != null
                || pickaxe != null;
    }

    /** From revision alone, or every branch and tag for null. */
    public LogQuery withRevision(String revision) {
        return new LogQuery(revision == null
                ? List.of()
                : List.of(revision), author, path, since, grep, pickaxe, follow);
    }

    public LogQuery withAuthor(String author) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow);
    }

    /** Under path, following a file's renames when follow says it is one. */
    public LogQuery withPath(String path, boolean follow) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, path != null && follow);
    }

    public LogQuery withSince(String since) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow);
    }

    public LogQuery withGrep(String grep) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow);
    }

    public LogQuery withPickaxe(String pickaxe) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow);
    }
}
