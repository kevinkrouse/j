/*
 * GitBackend.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.git;

import static org.armedbear.j.Constants.*;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VersionControlEntry;

public final class GitBackend implements VcsBackend {
    @Override
    public int id() {
        return VC_GIT;
    }

    @Override
    public String name() {
        return "git";
    }

    // A worktree or submodule has a .git file, not a directory.
    @Override
    public boolean isRoot(File dir) {
        File git = File.getInstance(dir, ".git");
        return git != null && git.exists();
    }

    @Override
    public VersionControlEntry getEntry(Buffer buffer) {
        return GitEntry.getEntry(buffer);
    }

    @Override
    public boolean gotoDiffSource(Editor editor, DiffOutputBuffer buffer) {
        DiffMode.gotoUnifiedDiffSource(editor, buffer, GitBackend::filename, Git::findRoot);
        return true;
    }

    private static String filename(String line) {
        if (line.startsWith("+++ b/"))
            return line.substring("+++ b/".length());
        if (line.startsWith("renamed to "))
            return line.substring("renamed to ".length());
        return null;
    }
}
