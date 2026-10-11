/*
 * GitDirs.java
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.Log;

/**
 * A working tree's git directories: its own (HEAD, index), and the common
 * one its refs are in, which differ for a linked worktree.
 */
record GitDirs(Path gitDir, Path commonDir) {
    /** The directories of the working tree at root, or null if it has none. */
    static GitDirs of(Path root) {
        Path dotGit = root.resolve(".git");
        Path gitDir = null;
        if (Files.isDirectory(dotGit)) {
            gitDir = dotGit;
        } else if (Files.isRegularFile(dotGit)) {
            // A worktree's or submodule's ".git" file: "gitdir: <path>".
            String s = readFirstLine(dotGit);
            if (s != null && s.startsWith("gitdir:"))
                gitDir = root.resolve(s.substring("gitdir:".length()).strip()).normalize();
        }
        if (gitDir == null)
            return null;
        Path commonDir = gitDir;
        String common = readFirstLine(gitDir.resolve("commondir"));
        if (common != null && !common.isBlank())
            commonDir = gitDir.resolve(common.strip()).normalize();
        return new GitDirs(gitDir, commonDir);
    }

    private static String readFirstLine(Path file) {
        if (!Files.isRegularFile(file))
            return null;
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        }
        catch (IOException e) {
            Log.debug(e);
            return null;
        }
    }

    /** What is in progress, from the files git leaves while it waits. */
    GitSnapshot.Operation operation() {
        if (Files.isDirectory(gitDir.resolve("rebase-merge")))
            return GitSnapshot.Operation.REBASE;
        if (Files.isDirectory(gitDir.resolve("rebase-apply")))
            return Files.exists(gitDir.resolve("rebase-apply/applying"))
                    ? GitSnapshot.Operation.AM
                    : GitSnapshot.Operation.REBASE;
        if (Files.exists(gitDir.resolve("MERGE_HEAD")))
            return GitSnapshot.Operation.MERGE;
        if (Files.exists(gitDir.resolve("CHERRY_PICK_HEAD")))
            return GitSnapshot.Operation.CHERRY_PICK;
        if (Files.exists(gitDir.resolve("REVERT_HEAD")))
            return GitSnapshot.Operation.REVERT;
        if (Files.exists(gitDir.resolve("BISECT_LOG")))
            return GitSnapshot.Operation.BISECT;
        return GitSnapshot.Operation.NONE;
    }
}
