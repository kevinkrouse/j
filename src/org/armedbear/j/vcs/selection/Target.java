/*
 * Target.java
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

package org.armedbear.j.vcs.selection;

import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/** Whole files or part of a hunk, independent of the surface that selected them. */
public sealed interface Target permits Target.Files, Target.Part {
    VcsRepository repository();

    String section();

    record Files(VcsRepository repository, String section, List<FileItem> files) implements Target {
        public Files {
            files = List.copyOf(files);
        }
    }

    /** selection is null for a whole hunk. */
    record Part(VcsRepository repository, String section, FileItem file, Hunk hunk, LineSet selection)
            implements Target {
        public static Part of(VcsRepository repository, String section, FileItem file, Hunk hunk, BitSet lines) {
            return new Part(repository, section, file, hunk, lines == null ? null : new LineSet(lines));
        }

        /** A writable copy for the existing patch and selection builders. */
        public BitSet lines() {
            return selection == null ? null : selection.toBitSet();
        }

        public Part withLines(BitSet lines) {
            return of(repository, section, file, hunk, lines);
        }
    }

    /** A file's membership in a list; only is null for the whole file. */
    record Member(FileItem file, Map<String, BitSet> only) {
        public Member {
            only = copy(only);
        }

        @Override
        public Map<String, BitSet> only() {
            return copy(only);
        }

        private static Map<String, BitSet> copy(Map<String, BitSet> source) {
            if (source == null)
                return null;
            Map<String, BitSet> result = new LinkedHashMap<>();
            source.forEach((key, lines) -> result.put(key, (BitSet) lines.clone()));
            return Collections.unmodifiableMap(result);
        }
    }
}
