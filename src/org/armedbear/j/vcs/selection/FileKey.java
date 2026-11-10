/*
 * FileKey.java
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

import java.util.Objects;
import org.armedbear.j.vcs.VcsRepository;

/** A literal working-copy path in an opaque backend section, independent of display keys. */
public record FileKey(VcsRepository repository, String section, String path) {
    public FileKey {
        Objects.requireNonNull(repository);
        Objects.requireNonNull(section);
        Objects.requireNonNull(path);
    }

    public Section scope() {
        return new Section(repository, section);
    }

    /** A backend's section of one working copy; neither field is parsed or serialized. */
    public record Section(VcsRepository repository, String section) {
        public Section {
            Objects.requireNonNull(repository);
            Objects.requireNonNull(section);
        }
    }
}
