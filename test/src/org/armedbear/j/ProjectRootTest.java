/*
 * ProjectRootTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectRootTest {
    @TempDir
    Path tmp;

    private Path dir(String relative) throws IOException {
        return Files.createDirectories(tmp.resolve(relative));
    }

    private static File file(Path p) {
        return File.getInstance(p.toString());
    }

    // tmp stands in for the home directory, so the search never leaves it.
    private Path root(Path dir, String configured) {
        File root = ProjectRoot.find(file(dir), configured, file(tmp));
        return root == null ? null : Path.of(root.canonicalPath());
    }

    private Path real(Path p) throws IOException {
        return p.toRealPath();
    }

    @Test
    public void fallsBackToTheDirectory() throws IOException {
        Path d = dir("a/b");
        assertEquals(real(d), root(d, null));
    }

    @Test
    public void nearestVcsRoot() throws IOException {
        dir("outer/.git");
        dir("outer/inner/.git");
        Path d = dir("outer/inner/src/main");
        assertEquals(real(tmp.resolve("outer/inner")), root(d, null));
        assertEquals(real(tmp.resolve("outer")), root(dir("outer/other"), null));
    }

    @Test
    public void gitFileCountsAsARoot() throws IOException {
        dir("repo/.git");
        Files.writeString(dir("repo/sub").resolve(".git"), "gitdir: ../.git/modules/sub\n");
        assertEquals(real(tmp.resolve("repo/sub")), root(dir("repo/sub/x"), null));
    }

    @Test
    public void outermostMarkerBeatsVcs() throws IOException {
        dir("ws/.j-project");
        dir("ws/team/.j-project");
        dir("ws/team/repo/.git");
        Path d = dir("ws/team/repo/src");
        assertEquals(real(tmp.resolve("ws")), root(d, null));
    }

    @Test
    public void markerMustBeADirectory() throws IOException {
        dir("p/.git");
        Files.writeString(dir("p/q").resolve(".j-project"), "");
        assertEquals(real(tmp.resolve("p")), root(dir("p/q/r"), null));
    }

    @Test
    public void propertyWins() throws IOException {
        dir("ws/.j-project");
        Path elsewhere = dir("elsewhere");
        assertEquals(real(elsewhere), root(dir("ws/src"), elsewhere.toString()));
    }

    @Test
    public void propertyNamingNoDirectoryIsIgnored() throws IOException {
        dir("ws/.j-project");
        assertEquals(real(tmp.resolve("ws")), root(dir("ws/src"), tmp.resolve("missing").toString()));
        assertEquals(real(tmp.resolve("ws")), root(dir("ws/src"), "  "));
    }

    @Test
    public void relativePropertyIsIgnored() throws IOException {
        dir("ws/.j-project");
        dir("ws/src/sub");
        assertEquals(real(tmp.resolve("ws")), root(dir("ws/src"), "sub"));
    }

    @Test
    public void homeIsNeverAProject() throws IOException {
        dir(".j-project");
        dir(".git");
        Path d = dir("scratch/tool");
        assertEquals(real(d), root(d, null));
        assertNull(root(tmp, null));
    }

    @Test
    public void missingDirectoryUsesNearestExisting() throws IOException {
        Path d = dir("a");
        assertEquals(real(d), root(tmp.resolve("a/not/yet"), null));
    }

    @Test
    public void outsideHomeADirectoryIsNoProject() throws IOException {
        Path home = dir("home");
        Path d = dir("elsewhere/plain");
        assertNull(ProjectRoot.find(file(d), null, file(home)));
        dir("elsewhere/.git");
        assertEquals(
            real(tmp.resolve("elsewhere")),
            Path.of(ProjectRoot.find(file(d), null, file(home)).canonicalPath())
        );
    }
}
