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

import java.util.List;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.diff.FileDiff;

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
     *     "unmerged", "recent"
     */
    public record Section(String id, String title, List<Item> items) {}

    public sealed interface Item permits FileItem, CommitItem {}

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

    public record CommitItem(String hash, String subject, String refs) implements Item {}

    public StatusData {
        header = List.copyOf(header);
        sections = List.copyOf(sections);
    }

    public static StatusData failed(String error) {
        return new StatusData(List.of(), null, List.of(), error);
    }
}
