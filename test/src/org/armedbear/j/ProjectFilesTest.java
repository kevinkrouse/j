/*
 * ProjectFilesTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectFilesTest {
    @TempDir
    Path tmp;

    private void touch(String... relatives) throws IOException {
        for (String relative : relatives) {
            Path p = tmp.resolve(relative);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "");
        }
    }

    private void write(String relative, String content) throws IOException {
        Path p = tmp.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private List<String> walk(int max, Pattern excludes, Path globalIgnore) {
        List<String> files =
            new ArrayList<>(new ProjectFiles.Walker(tmp, max, excludes, globalIgnore, () -> false, null).walk());
        Collections.sort(files);
        return files;
    }

    private List<String> walk() {
        return walk(1000, null, null);
    }

    @Test
    public void listsFilesRelativeToTheRoot() throws IOException {
        touch("a.txt", "src/b.java", "src/deep/c.md");
        assertEquals(List.of("a.txt", "src/b.java", "src/deep/c.md"), walk());
    }

    @Test
    public void skipsDefaultDirectories() throws IOException {
        touch("keep.txt", "node_modules/x/y.js", ".git/config", "build/out.class", "src/target/t");
        assertEquals(List.of("keep.txt"), walk());
    }

    @Test
    public void gitignoreRules() throws IOException {
        write(".gitignore", "# comment\n\n*.log\nlogs/\n/top.txt\ndocs/*.tmp\n**/gen\n!keep.log\n");
        touch(
            "a.log",
            "keep.log",
            "logs/x.txt",
            "sub/logs/y.txt",
            "top.txt",
            "sub/top.txt",
            "docs/a.tmp",
            "docs/b.md",
            "sub/docs/a.tmp",
            "x/y/gen/g.java",
            "src/main.c"
        );
        assertEquals(List.of(".gitignore", "docs/b.md", "src/main.c", "sub/docs/a.tmp", "sub/top.txt"), walk());
    }

    @Test
    public void directoryOnlyRuleSparesFiles() throws IOException {
        write(".gitignore", "data/\n");
        touch("data", "other/data/x");
        assertEquals(List.of(".gitignore", "data"), walk());
    }

    @Test
    public void nestedGitignoreAppliesToItsSubtree() throws IOException {
        write("sub/.gitignore", "*.tmp\n/local\n");
        touch("a.tmp", "sub/b.tmp", "sub/c.txt", "sub/local/x", "sub/deeper/local/y");
        assertEquals(List.of("a.tmp", "sub/.gitignore", "sub/c.txt", "sub/deeper/local/y"), walk());
    }

    @Test
    public void repositoryExcludeFile() throws IOException {
        write(".git/info/exclude", "secret\n");
        touch("secret", "public");
        assertEquals(List.of("public"), walk());
    }

    @Test
    public void globalIgnoreFile() throws IOException {
        Path global = Files.writeString(Files.createTempFile("ignore", ""), "*.swp\n");
        try {
            touch("a.swp", "b.txt");
            assertEquals(List.of("b.txt"), walk(1000, null, global));
        }
        finally {
            Files.delete(global);
        }
    }

    @Test
    public void skipsWorktreesKeepsSubmodules() throws IOException {
        touch(".git/HEAD", "main.c");
        write("wt/.git", "gitdir: /repo/.git/worktrees/wt\n");
        touch("wt/main.c");
        write("mod/.git", "gitdir: ../.git/modules/mod\n");
        touch("mod/lib.c");
        assertEquals(List.of("main.c", "mod/lib.c"), walk());
    }

    @Test
    public void excludePatternAppliesToNames() throws IOException {
        touch("a.java", "a.java~", "CVS/Entries");
        assertEquals(List.of("a.java"), walk(1000, Pattern.compile("^(CVS|.+~)$"), null));
    }

    @Test
    public void stopsAtTheCap() throws IOException {
        touch("a", "b", "c", "d");
        ProjectFiles.Walker w = new ProjectFiles.Walker(tmp, 2, null, null, () -> false, null);
        assertEquals(2, w.walk().size());
        assertTrue(w.truncated);
    }

    @Test
    public void globs() {
        assertTrue(Pattern.matches(ProjectFiles.Ignore.globToRegex("*.c"), "x.c"));
        assertFalse(Pattern.matches(ProjectFiles.Ignore.globToRegex("*.c"), "d/x.c"));
        assertTrue(Pattern.matches(ProjectFiles.Ignore.globToRegex("a/**/b"), "a/b"));
        assertTrue(Pattern.matches(ProjectFiles.Ignore.globToRegex("a/**/b"), "a/x/y/b"));
        assertTrue(Pattern.matches(ProjectFiles.Ignore.globToRegex("a/**"), "a/x/y"));
        assertTrue(Pattern.matches(ProjectFiles.Ignore.globToRegex("f?o[0-9]"), "foo1"));
        assertFalse(Pattern.matches(ProjectFiles.Ignore.globToRegex("f[!o]o"), "foo"));
        assertTrue(Pattern.matches(ProjectFiles.Ignore.globToRegex("a+b(c)"), "a+b(c)"));
    }

    @Test
    public void scansInTheBackgroundAndTracksSaves() throws Exception {
        touch("a.txt", "src/b.java");
        ProjectFiles pf = ProjectFiles.forRoot(File.getInstance(tmp.toString()));
        CountDownLatch done = new CountDownLatch(1);
        pf.addListener(p -> {
            if (!p.isScanning())
                done.countDown();
        });
        pf.rescan();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("a.txt", "src/b.java"), pf.files());

        touch("src/new.java");
        ProjectFiles.fileSaved(File.getInstance(tmp.resolve("src/new.java").toString()));
        ProjectFiles.awaitScanner();
        assertEquals(List.of("a.txt", "src/b.java", "src/new.java"), pf.files());
        // Already listed, or what a scan would skip: no change.
        write(".gitignore", "*.log\n");
        touch(".git/COMMIT_EDITMSG", "x.log", "build/gen.java");
        for (String s : new String[] { "a.txt", ".git/COMMIT_EDITMSG", "x.log", "build/gen.java" })
            ProjectFiles.fileSaved(File.getInstance(tmp.resolve(s).toString()));
        ProjectFiles.awaitScanner();
        assertEquals(List.of("a.txt", "src/b.java", "src/new.java"), pf.files());
    }

    @Test
    public void rescanKeepsTheOldListUntilDone() throws Exception {
        touch("a.txt");
        ProjectFiles pf = ProjectFiles.forRoot(File.getInstance(tmp.toString()));
        pf.rescan();
        ProjectFiles.awaitScanner();
        assertEquals(List.of("a.txt"), pf.files());
        List<List<String>> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        pf.addListener(p -> seen.add(p.files()));
        touch("b.txt");
        pf.rescan();
        ProjectFiles.awaitScanner();
        assertEquals(List.of(List.of("a.txt", "b.txt")), seen);
    }

    @Test
    public void invalidateMakesItStale() throws Exception {
        touch("a.txt");
        ProjectFiles pf = ProjectFiles.forRoot(File.getInstance(tmp.toString()));
        pf.refreshIfStale(3600);
        ProjectFiles.awaitScanner();
        touch("b.txt");
        pf.refreshIfStale(3600);
        ProjectFiles.awaitScanner();
        assertEquals(List.of("a.txt"), pf.files());
        Thread.sleep(2);
        ProjectFiles.invalidate();
        pf.refreshIfStale(3600);
        ProjectFiles.awaitScanner();
        assertEquals(List.of("a.txt", "b.txt"), pf.files());
    }

    @Test
    public void submoduleOfAWorktreeIsKept() throws IOException {
        write("lib/.git", "gitdir: /repo/.git/worktrees/wt/modules/lib\n");
        touch("lib/x.c");
        assertEquals(List.of("lib/x.c"), walk());
    }

    @Test
    public void linksToDirectoriesAreSkipped() throws IOException {
        touch("real/a.txt");
        Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("real"));
        assertEquals(List.of("real/a.txt"), walk());
    }

    @Test
    public void aFileInARootNamedLikeASkippedDirectoryIsAccepted() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("build"));
        Path f = Files.writeString(root.resolve("a.txt"), "");
        assertTrue(new ProjectFiles.Walker(root, 1000, null, null, () -> false, null).accepts(f));
        Path nested = Files.createDirectories(root.resolve("node_modules"));
        Path g = Files.writeString(nested.resolve("b.js"), "");
        assertFalse(new ProjectFiles.Walker(root, 1000, null, null, () -> false, null).accepts(g));
    }
}
