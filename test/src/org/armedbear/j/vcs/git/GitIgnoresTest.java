/*
 * GitIgnoresTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.armedbear.j.vcs.Ignores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitIgnoresTest {
    @TempDir
    Path tmp;

    private void write(String relative, String content) throws IOException {
        Path p = tmp.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private boolean ignored(String relative) {
        return new GitIgnores(tmp, null).ignored(tmp.resolve(relative), false);
    }

    @Test
    public void globs() {
        assertTrue(Pattern.matches(Ignores.globToRegex("*.c"), "x.c"));
        assertFalse(Pattern.matches(Ignores.globToRegex("*.c"), "d/x.c"));
        assertTrue(Pattern.matches(Ignores.globToRegex("a/**/b"), "a/b"));
        assertTrue(Pattern.matches(Ignores.globToRegex("a/**/b"), "a/x/y/b"));
        assertTrue(Pattern.matches(Ignores.globToRegex("a/**"), "a/x/y"));
        assertTrue(Pattern.matches(Ignores.globToRegex("f?o[0-9]"), "foo1"));
        assertFalse(Pattern.matches(Ignores.globToRegex("f[!o]o"), "foo"));
        assertTrue(Pattern.matches(Ignores.globToRegex("a+b(c)"), "a+b(c)"));
    }

    @Test
    public void theLastRuleThatMatchesDecidesAndBangUnignores() throws IOException {
        write(".gitignore", "*.log\n!keep.log\n*.tmp\n!*.tmp\n*.tmp\n");
        assertTrue(ignored("a.log"));
        assertFalse(ignored("keep.log"));
        assertTrue(ignored("b.tmp"), "the last of three");
    }

    @Test
    public void aDeeperFileOutranksAShallowerOne() throws IOException {
        write(".gitignore", "*.gen\n");
        write("sub/.gitignore", "!*.gen\n");
        assertTrue(ignored("a.gen"));
        assertFalse(ignored("sub/b.gen"));
        assertFalse(ignored("sub/deeper/c.gen"));
    }

    @Test
    public void aGitignoreOutranksExcludeWhichOutranksTheGlobalFile() throws IOException {
        Path global = Files.writeString(Files.createTempFile("ignore", ""), "*.swp\n*.bak\n");
        try {
            write(".git/info/exclude", "!*.bak\nsecret\n");
            write(".gitignore", "!secret\n");
            final GitIgnores ignores = new GitIgnores(tmp, global);
            assertTrue(ignores.ignored(tmp.resolve("a.swp"), false));
            assertFalse(ignores.ignored(tmp.resolve("a.bak"), false), "exclude over global");
            assertFalse(ignores.ignored(tmp.resolve("secret"), false), ".gitignore over exclude");
        }
        finally {
            Files.delete(global);
        }
    }

    @Test
    public void escapedBangAndHashAreNames() throws IOException {
        write(".gitignore", "\\!bang\n\\#hash\n");
        assertTrue(ignored("!bang"));
        assertTrue(ignored("#hash"));
    }

    @Test
    public void whatIsInAnIgnoredDirectoryIsIgnoredAndCantBeTakenBack() throws IOException {
        write(".gitignore", "out/\n!out/keep.txt\n");
        Files.createDirectories(tmp.resolve("out/deep"));
        assertTrue(ignored("out/a.txt"));
        assertTrue(ignored("out/keep.txt"), "git can't re-include it");
        assertTrue(ignored("out/deep/b.txt"));
        assertFalse(ignored("src/a.txt"));
    }

    @Test
    public void aDirectoryGitIgnoresGetsNoRulesOfItsOwn() throws IOException {
        // Dotfiles kept in a repository at the root, everything else ignored.
        GitFixture.metadata(tmp);
        write(".gitignore", "*\n!.bashrc\n");
        Files.createDirectories(tmp.resolve("Downloads"));
        assertTrue(Ignores.holding(tmp).ignored(tmp.resolve("notes.txt"), false));
        assertFalse(
            Ignores.holding(tmp.resolve("Downloads")).ignored(tmp.resolve("Downloads/a.pdf"), false),
            "git wouldn't list Downloads: nothing in it is hidden as ignored");
    }
}
