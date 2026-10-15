/*
 * Repositories.java
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

package org.armedbear.j.vcs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.ProjectRoot;

/** The repositories j knows of, one {@link VcsRepository} per root. */
public final class Repositories {
    private static final Map<Path, VcsRepository> repositories = new ConcurrentHashMap<>();
    // Directory -> the root of the repository holding it, if any.
    private static final Map<Path, Optional<Path>> roots = new HashMap<>();

    private Repositories() {}

    /** The repository holding a local file or directory, or null. */
    public static VcsRepository forFile(File file) {
        if (file == null || !file.isLocal())
            return null;
        File dir = file.isDirectory() ? file : file.getParentFile();
        if (dir == null)
            return null;
        return forDirectory(Path.of(dir.canonicalPath()));
    }

    /** The repository holding dir, an absolute, canonical path, or null. */
    public static VcsRepository forDirectory(Path dir) {
        Optional<Path> root;
        synchronized (roots) {
            root = roots.get(dir);
        }
        if (root == null) {
            root = Optional.ofNullable(findRoot(dir));
            synchronized (roots) {
                roots.put(dir, root);
            }
        }
        return root.map(r -> repositories.get(r)).orElse(null);
    }

    // The nearest directory a backend recognizes, registering its repository.
    private static Path findRoot(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            VcsBackend backend = backendAt(d);
            if (backend != null) {
                register(d, backend);
                return d;
            }
        }
        return null;
    }

    private static VcsBackend backendAt(Path dir) {
        File f = File.getInstance(dir.toString());
        if (f == null)
            return null;
        for (VcsBackend backend : VcsBackends.all()) {
            if (backend.isRoot(f))
                return backend;
        }
        return null;
    }

    private static VcsRepository register(Path root, VcsBackend backend) {
        return repositories.computeIfAbsent(root, r -> {
            VcsRepository repository = new VcsRepository(r, backend);
            RepositoryWatcher.watch(repository);
            return repository;
        });
    }

    /**
     * The project's repositories, sorted by path: the project root's own,
     * and, under a .j-project directory, each of its immediate subdirectories
     * that is a repository root.
     */
    public static List<VcsRepository> forProject(File projectRoot) {
        List<VcsRepository> list = new ArrayList<>();
        if (projectRoot == null || !projectRoot.isLocal())
            return list;
        Path root = Path.of(projectRoot.canonicalPath());
        VcsRepository own = forDirectory(root);
        if (own != null)
            list.add(own);
        if (Files.isDirectory(root.resolve(ProjectRoot.MARKER))) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path link : dirs) {
                    // One repository per directory, however it is reached.
                    Path sub = link.toRealPath();
                    VcsBackend backend = backendAt(sub);
                    if (backend != null) {
                        VcsRepository repository = register(sub, backend);
                        if (!list.contains(repository))
                            list.add(repository);
                    }
                }
            }
            catch (IOException e) {
                Log.debug(e);
            }
        }
        list.sort((a, b) -> a.root().compareTo(b.root()));
        return list;
    }

    /**
     * Its color among the project's repositories: its place in their sorted
     * list, so it is the same each session; -1 if it is not one of them.
     */
    public static int colorIndex(VcsRepository repository, List<VcsRepository> project) {
        int i = project.indexOf(repository);
        return i < 0 ? -1 : i % RepositoryColors.COUNT;
    }

    /**
     * Marks every snapshot stale and forgets which directories are in which
     * repository; a repository with listeners is read again in the background.
     */
    public static void invalidateAll() {
        synchronized (roots) {
            roots.clear();
        }
        for (VcsRepository repository : repositories.values()) {
            repository.invalidate();
            if (repository.hasListeners())
                repository.refresh();
        }
    }
}
