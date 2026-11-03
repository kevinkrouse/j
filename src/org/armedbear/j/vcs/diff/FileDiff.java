/*
 * FileDiff.java
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

package org.armedbear.j.vcs.diff;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One file's part of a unified diff. Text is the diff's bytes as ISO-8859-1,
 * so a patch made from it is byte for byte what the diff said.
 *
 * @param header   the lines before the first hunk ("diff --git", "---", ...)
 * @param oldPath  the old path as the diff spells it, or null if the file is new
 * @param newPath  the new path, or null if the file is deleted
 * @param isCombined a combined diff ("diff --cc") of two parents, as an
 *                 unmerged file's against ours and theirs: its hunks
 *                 combined, read only
 * @param onlyWhitespace read ignoring whitespace, none but in whitespace
 */
public record FileDiff(List<String> header, String oldPath, String newPath, boolean isNew, boolean isDeleted,
        boolean isBinary, boolean isCombined, List<Hunk> hunks, boolean onlyWhitespace) {
    /** A diff with no hunks: no change to the file's content. */
    public static final FileDiff EMPTY =
            new FileDiff(List.of(), null, null, false, false, false, false, List.of(), false);

    /** An unmerged file's combined diff that git gave none of, as for a side that deleted it. */
    public static final FileDiff NO_COMBINED =
            new FileDiff(List.of(), null, null, false, false, false, true, List.of(), false);

    /** Read ignoring whitespace, a diff with no hunks: no change but to whitespace. */
    public static final FileDiff ONLY_WHITESPACE =
            new FileDiff(List.of(), null, null, false, false, false, false, List.of(), true);

    public FileDiff {
        header = List.copyOf(header);
        hunks = List.copyOf(hunks);
    }

    /** The path the file has now: the new one, or the old one if deleted. As ISO-8859-1. */
    public String rawPath() {
        return newPath != null ? newPath : oldPath;
    }

    /** {@link #rawPath} decoded as UTF-8, for showing. */
    public String path() {
        return decode(rawPath());
    }

    public boolean isRename() {
        return oldPath != null && newPath != null && !oldPath.equals(newPath);
    }

    /** ISO-8859-1 text, as the diff holds it, read as the UTF-8 it usually is. */
    public static String decode(String raw) {
        return raw == null ? null : new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
}
