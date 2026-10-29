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
 * @param repositories the names of those of several shown; none for all of them
 * @param tags whether the commits only tags reach are shown too, with no
 *     revisions given
 * @param stashes whether the stashes are shown too, each off the commit it
 *     was made on, with nothing narrowing it
 */
public record LogQuery(List<String> revisions, String author, String path, String since, String grep, String pickaxe,
        boolean follow, List<String> repositories, boolean tags, boolean stashes) {
    /** Everything. */
    public static final LogQuery ALL = new LogQuery(List.of(), null, null, null, null, null, false);

    public LogQuery {
        revisions = List.copyOf(revisions);
        repositories = List.copyOf(repositories);
    }

    public LogQuery(
            List<String> revisions,
            String author,
            String path,
            String since,
            String grep,
            String pickaxe,
            boolean follow) {
        this(revisions, author, path, since, grep, pickaxe, follow, List.of(), false, false);
    }

    /** A file's history, through its renames. */
    public static LogQuery file(String path) {
        return new LogQuery(List.of(), null, path, null, null, null, true);
    }

    /**
     * Whether it leaves out commits between those it lists, by author or
     * text, or following a file, whose parents git does not rewrite, so
     * that a commit's parents may not be listed, and no graph can be drawn
     * of them.
     */
    public boolean skipsCommits() {
        return author != null || grep != null || pickaxe != null || follow && path != null;
    }

    /** Whether anything narrows it. */
    public boolean isFiltered() {
        return !revisions.isEmpty()
                || author != null
                || path != null
                || since != null
                || grep != null
                || pickaxe != null
                || !repositories.isEmpty();
    }

    /** From revision alone, or every branch for null. */
    public LogQuery withRevision(String revision) {
        return new LogQuery(revision == null
                ? List.of()
                : List.of(revision), author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    /** From revisions, or every branch for none. */
    public LogQuery withRevisions(List<String> revisions) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    public LogQuery withAuthor(String author) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    /** Under path, following a file's renames when follow says it is one. */
    public LogQuery withPath(String path, boolean follow) {
        return new LogQuery(revisions,
                author,
                path,
                since,
                grep,
                pickaxe,
                path != null && follow,
                repositories,
                tags,
                stashes);
    }

    public LogQuery withSince(String since) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    public LogQuery withGrep(String grep) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    public LogQuery withPickaxe(String pickaxe) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    /** Of the repositories named, or all of them for none. */
    public LogQuery withRepositories(List<String> repositories) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    /** With the commits only tags reach, or without. */
    public LogQuery withTags(boolean tags) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    /** With the stashes, or without. */
    public LogQuery withStashes(boolean stashes) {
        return new LogQuery(revisions, author, path, since, grep, pickaxe, follow, repositories, tags, stashes);
    }

    /** Whether it shows the stashes: asked for, and {@link #canShowStashes}. */
    public boolean showsStashes() {
        return stashes && canShowStashes();
    }

    /**
     * Whether it could show the stashes: nothing but the repositories
     * narrowing it, as a stash's base may otherwise be left out.
     */
    public boolean canShowStashes() {
        return withRepositories(List.of()).withTags(false).withStashes(false).equals(ALL);
    }
}
