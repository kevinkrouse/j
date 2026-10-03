/*
 * VcsDetectionTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.Constants;
import org.armedbear.j.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class VcsDetectionTest implements Constants {
    @TempDir
    Path root;

    @BeforeEach
    public void noPerforceEnvironment() {
        assumeTrue(System.getenv("P4CONFIG") == null && System.getenv("P4PORT") == null);
    }

    private int guess(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        return VersionControl.guessVCS(File.getInstance(file.toString()));
    }

    @Test
    public void aGitFileMarksAWorktree() throws Exception {
        Files.writeString(root.resolve(".git"), "gitdir: elsewhere\n");
        assertEquals(VC_GIT, guess(root.resolve("src/a.txt")));
    }

    @Test
    public void theNearestTreeWins() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.createDirectories(root.resolve("sub/.svn"));
        assertEquals(VC_SVN, guess(root.resolve("sub/a.txt")));
        assertEquals(VC_GIT, guess(root.resolve("a.txt")));
    }

    @Test
    public void noTreeIsNone() throws Exception {
        assumeTrue(VersionControl.guessVCS(File.getInstance(root.toString())) < 0);
        assertEquals(-1, guess(root.resolve("a.txt")));
    }
}
