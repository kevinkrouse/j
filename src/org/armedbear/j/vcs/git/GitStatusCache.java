/*
 * GitStatusCache.java
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

package org.armedbear.j.vcs.git;

import java.nio.file.Path;
import org.armedbear.j.File;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VersionControl;

/**
 * A file's git status, from its repository's snapshot. Callers that have
 * just changed the working tree -- saving a file, say -- invalidate it.
 */
public final class GitStatusCache {
    private GitStatusCache() {}

    /**
     * The two letter short-format code for a file ("M ", "??", "!!"), or null
     * if git says nothing about it -- unchanged, or not in a repository at
     * all. Git runs on this thread only for a repository's first snapshot; a
     * stale one answers while a fresh one is read in the background, and its
     * buffers are updated when it is.
     */
    public static String statusFor(File file) {
        if (file == null || file.isRemote())
            return null;
        VcsRepository repository = Repositories.forFile(file);
        if (repository == null || !(repository.backend() instanceof GitBackend))
            return null;
        VersionControl.track(repository);
        Snapshot snapshot = repository.snapshot();
        if (snapshot == null) {
            snapshot = repository.snapshotNow();
        } else if (repository.isStale()) {
            repository.refresh();
        }
        return code(snapshot, relativePath(repository.root(), file));
    }

    // The file's own entry, or "!!" if a directory it is in is ignored: git
    // lists an ignored directory, not the files in it.
    static String code(Snapshot snapshot, String path) {
        FileChange change = snapshot.change(path);
        if (change != null)
            return change.code();
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            FileChange dir = snapshot.change(path.substring(0, slash + 1));
            if (dir != null && dir.isIgnored())
                return dir.code();
        }
        return null;
    }

    static String relativePath(Path root, File file) {
        Path path = Path.of(file.canonicalPath());
        if (!path.startsWith(root))
            return path.toString();
        return root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }
}
