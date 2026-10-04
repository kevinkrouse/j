/*
 * LegacyDetectionTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.legacy;

import static org.armedbear.j.Constants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.File;
import org.armedbear.j.vcs.VcsBackends;
import org.armedbear.j.vcs.VersionControl;
import org.armedbear.j.vcs.cvs.CvsBackend;
import org.armedbear.j.vcs.darcs.DarcsBackend;
import org.armedbear.j.vcs.p4.P4Backend;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LegacyDetectionTest {
    @TempDir
    Path root;

    @BeforeAll
    public static void register() {
        assumeTrue(System.getenv("P4CONFIG") == null && System.getenv("P4PORT") == null);
        if (VcsBackends.get(VC_CVS) == null) {
            VcsBackends.register(new CvsBackend());
            VcsBackends.register(new DarcsBackend());
            VcsBackends.register(new P4Backend());
        }
    }

    private int guess(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        return VersionControl.guessVCS(File.getInstance(file.toString()));
    }

    @Test
    public void cvs() throws Exception {
        Files.createDirectories(root.resolve("src/CVS"));
        assertEquals(VC_CVS, guess(root.resolve("src/a.c")));
    }

    // At one directory, extension backends come before core's.
    @Test
    public void darcsBeforeGit() throws Exception {
        Files.createDirectory(root.resolve("_darcs"));
        Files.createDirectory(root.resolve(".git"));
        assertEquals(VC_DARCS, guess(root.resolve("a.txt")));
    }
}
