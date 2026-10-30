/*
 * StatusData.java
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

package org.armedbear.j.vcs.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.LogRef;

/**
 * What a status buffer shows of one repository, as its backend read it: the
 * header ("Head:", "Upstream:"), what is in progress, and the sections.
 *
 * @param header label and value pairs, in order
 * @param inProgress "Merge in progress", say; null if nothing is
 * @param error why the repository could not be read, or null
 */
public record StatusData(List<Field> header, String inProgress, List<Section> sections, String error) {
    public record Field(String label, String value) {}

    /**
     * @param id stable across refreshes: "untracked", "unstaged", "staged",
     *     "unmerged", STASHES, "recent", or one of RANGES
     * @param total how many it has, of which items are the first
     * @param range the revisions its commits are, for the log; or null
     */
    public record Section(String id, String title, List<Item> items, int total, String range) {
        public Section(String id, String title, List<Item> items) {
            this(id, title, items, items.size(), null);
        }
    }

    /**
     * The sections of commits on one branch and not another: from the
     * upstream, to it, to the push target, from the parent, and not in it.
     * Closed to begin with.
     */
    public static final Set<String> RANGES =
            Set.of("unpulled", "unpushed", "unpushed-push", "unmerged-parent", "not-in-parent");

    /** The section of stashes, each a StashItem. */
    public static final String STASHES = "stashes";

    /** Whether section id's items are commits. */
    public static boolean isCommits(String id) {
        return id.equals("recent") || RANGES.contains(id);
    }

    public sealed interface Item permits FileItem, CommitItem, StashItem {}

    /**
     * A changed file in a section.
     *
     * @param letter the status letter shown: M, A, D, R, C, T, U or ?
     * @param staged whether its diff is the index's against HEAD
     * @param diff its diff, if read; null if not asked for, or there is none
     */
    public record FileItem(FileChange change, char letter, boolean staged, FileDiff diff) implements Item {
        public String path() {
            return change.path();
        }

        /** Whether it has a diff to show when expanded. */
        public boolean hasDiff() {
            return !change.isUntracked() && !change.isIgnored() && !change.isConflicted();
        }
    }

    public record CommitItem(String hash, String subject, List<LogRef> refs) implements Item {
        public CommitItem {
            refs = List.copyOf(refs);
        }

        /** "abc1234 (main) subject", as listed. */
        public String text() {
            final String label = label();
            return hash + " " + (label.isEmpty() ? "" : "(" + label + ") ") + subject;
        }

        /** Its refs as git decorates it: "HEAD -> main, tag: v1, origin/main". */
        public String label() {
            final List<String> out = new ArrayList<>();
            for (LogRef r : refs)
                out.add(
                    r.kind() == LogRef.Kind.TAG ? "tag: " + r.name()
                            : r.head() && r.kind() == LogRef.Kind.BRANCH ? "HEAD -> " + r.name()
                            : r.name());
            return String.join(", ", out);
        }
    }

    /**
     * A stash: a commit, as git keeps one, with the files it holds.
     *
     * @param name what it is called now, as "stash@{0}"
     * @param hash its commit's full hash
     * @param subject "On main: message", say
     * @param files its files with their diffs, if read; null if not asked for
     */
    public record StashItem(String name, String hash, String subject, List<FileItem> files) implements Item {
        /** As a commit, for its details. */
        public CommitItem commit() {
            return new CommitItem(hash, subject, List.of(new LogRef(name, LogRef.Kind.STASH, false)));
        }
    }

    public StatusData {
        header = List.copyOf(header);
        sections = List.copyOf(sections);
    }

    public static StatusData failed(String error) {
        return new StatusData(List.of(), null, List.of(), error);
    }
}
