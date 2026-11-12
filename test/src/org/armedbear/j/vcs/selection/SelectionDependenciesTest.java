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

    // The registry of every backend, where the closure stops: it reaches each
    // backend's editor hooks by design.
    private static final Set<String> STOPS = Set.of("src/org/armedbear/j/vcs/VcsBackends.java");

    // Of j's main package, the types that aren't the editor's: its file
    // abstraction, its log and its constants, whose own references aren't
    // followed, as the main package is the editor's.
    private static final Set<String> NOT_EDITOR =
            Set.of("org.armedbear.j.File", "org.armedbear.j.Log", "org.armedbear.j.Constants");

    // A type reached that is Swing's, AWT's or the editor's.
    private static boolean editorType(String name) {
        if (name.startsWith("javax.swing") || name.startsWith("java.awt"))
            return true;
        return name.startsWith("org.armedbear.j.")
                && !name.startsWith("org.armedbear.j.vcs.")
                && !name.startsWith("org.armedbear.j.util.")
                && !NOT_EDITOR.contains(name);
    }

    // Followed: the vcs packages and j's utilities.
    private static boolean followed(String name) {
        return name.startsWith("org.armedbear.j.vcs.") || name.startsWith("org.armedbear.j.util.");
    }

    // The source file of a class, or of the class it is nested in; null if not in src.
    private static Path source(String name) {
        final String[] parts = name.split("\\.");
        for (int n = parts.length; n > 0; n--) {
            final Path p = Path.of("src", String.join("/", List.of(parts).subList(0, n)) + ".java");
            if (Files.isRegularFile(p))
                return p;
        }
        return null;
    }

    // src less its comments and string literals.
    private static String code(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    @Test
    public void theSelectionPackageAndTheBackendReachNoEditorType() throws Exception {
        // Imports, wildcards too; names written out; and a vcs or util
        // file's own package's types used by name.
        final Pattern imports = Pattern.compile("^import (?:static )?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);
        final Pattern qualified =
                Pattern.compile("\\b(org\\.armedbear\\.j\\.[\\w.]+|javax\\.swing\\.\\w+|java\\.awt\\.[\\w.]+)");
        final Pattern named = Pattern.compile("\\b([A-Z]\\w*)\\b");
        final Pattern pkg = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
        final List<Path> todo = new ArrayList<>();
        try (Stream<Path> listed = Files.list(Path.of("src/org/armedbear/j/vcs/selection"))) {
            listed.filter(p -> p.toString().endsWith(".java")).forEach(todo::add);
        }
        // What VcsRepository.backend() hands out.
        todo.add(Path.of("src/org/armedbear/j/vcs/VcsBackend.java"));
        final java.util.Set<Path> seen = new java.util.HashSet<>();
        final List<String> wrong = new ArrayList<>();
        while (!todo.isEmpty()) {
            final Path file = todo.remove(todo.size() - 1);
            if (STOPS.contains(file.toString().replace('\\', '/')) || !seen.add(file))
                continue;
            final String src = code(Files.readString(file));
            final List<String> names = new ArrayList<>();
            final Matcher i = imports.matcher(src);
            while (i.find())
                names.add(i.group(1));
            final String body = src.replaceAll("(?m)^(?:import|package) .*$", "");
            final Matcher q = qualified.matcher(body);
            while (q.find())
                names.add(q.group(1));
            for (String name : names) {
                // Background hands work back to the event thread, its one use of Swing.
                if (editorType(name)
                        && !(file.endsWith("Background.java") && name.equals("javax.swing.SwingUtilities")))
                    wrong.add(file + ": " + name);
                else if (followed(name)) {
                    final Path next = source(name);
                    if (next != null)
                        todo.add(next);
                }
            }
            final Matcher p = pkg.matcher(src);
            if (p.find() && followed(p.group(1) + ".")) {
                final Path dir = file.getParent();
                final Matcher n = named.matcher(body);
                while (n.find()) {
                    final Path same = dir.resolve(n.group(1) + ".java");
                    if (Files.isRegularFile(same))
                        todo.add(same);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }
}
