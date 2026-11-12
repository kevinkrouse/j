/*
 * SelectionDependenciesTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The selection package's own imports: the repository, diffs and status
 * data, no editor, Swing, AWT or presentation type. VcsRepository still
 * reaches the editor through VcsBackend's older hooks (phase 12).
 */
public class SelectionDependenciesTest {
    private static final Set<String> ALLOWED = Set.of(
        "org.armedbear.j.vcs.VcsRepository",
        "org.armedbear.j.vcs.diff.DiffLine",
        "org.armedbear.j.vcs.diff.FileDiff",
        "org.armedbear.j.vcs.diff.Hunk",
        "org.armedbear.j.vcs.diff.Patch",
        "org.armedbear.j.vcs.status.StatusData",
        "org.armedbear.j.vcs.status.StatusData.FileItem");

    @Test
    public void theSelectionPackageImportsOnlyTheRepositoryDiffsAndStatusData() throws Exception {
        final Pattern imports = Pattern.compile("^import (?:static )?([\\w.]+);", Pattern.MULTILINE);
        final List<String> wrong = new ArrayList<>();
        final List<Path> files;
        try (Stream<Path> listed = Files.list(Path.of("src/org/armedbear/j/vcs/selection"))) {
            files = listed.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty());
        for (Path file : files) {
            final Matcher m = imports.matcher(Files.readString(file));
            while (m.find()) {
                final String name = m.group(1);
                if (name.startsWith("java.util") || name.startsWith("java.nio") || name.startsWith("java.security"))
                    continue;
                if (name.startsWith("org.armedbear.j.vcs.selection.") || ALLOWED.contains(name))
                    continue;
                wrong.add(file.getFileName() + ": " + name);
            }
        }
        assertEquals(List.of(), wrong);
    }
}
