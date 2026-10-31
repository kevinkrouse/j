/*
 * GitBlameTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.blame.BlameCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitBlameTest {
    @TempDir
    Path tmp;

    private GitFixture git;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    private void commitAs(String author, String message) {
        git.git("add", "-A");
        git.git("commit", "-q", "--author=" + author + " <" + author.toLowerCase() + "@example.com>", "-m", message);
    }

    private String hash(String rev) {
        return git.git("rev-parse", rev).strip();
    }

    // The whitespace blame ignores.
    private Whitespace ignored = Whitespace.NONE;

    // Each line's commit, blaming text as path in the working tree.
    private BlameCommit[] blame(String path, String text) {
        return blame(path, null, text);
    }

    // Each line's commit, blaming path at revision (null for the working
    // tree, where text is what is there).
    private BlameCommit[] blame(String path, String revision, String text) {
        final VcsRepository r = Repositories.forDirectory(git.root);
        final String content = text != null ? text : git.git("show", revision + ":" + path);
        final int n = (int) content.chars().filter(c -> c == '\n').count();
        final BlameCommit[] commits = new BlameCommit[n];
        final VcsResult result = r.backend()
                .blame(
                    r,
                    path,
                    revision,
                    text == null ? null : text.getBytes(StandardCharsets.UTF_8),
                    ignored,
                    (first, count, c, source) -> {
                        for (int i = first; i < Math.min((long) first + count, n); i++)
                            commits[i] = c;
                    });
        assertTrue(result.succeeded(), result.stderr());
        return commits;
    }

    @Test
    public void eachLineHasItsCommitSharedByItsRuns() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\nc\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a\nB\nc\n");
        commitAs("Bob", "second");
        final BlameCommit[] c = blame("f.txt", "a\nB\nc\n");
        assertEquals("Ann", c[0].author());
        assertEquals("ann@example.com", c[0].authorMail());
        assertEquals("first", c[0].summary());
        assertSame(c[0], c[2], "one commit, two runs");
        assertTrue(c[0].boundary(), "the root commit");
        assertEquals("Bob", c[1].author());
        assertEquals(hash("HEAD"), c[1].hash());
        assertEquals(hash("HEAD~"), c[1].previous());
        assertEquals("f.txt", c[1].previousPath());
        assertEquals(1767225600L, c[1].authorTime(), "2026-01-01");
    }

    @Test
    public void aWhitespaceChangeIsPassedOverWhenWhitespaceIsIgnored() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a b\nc\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a  b\nc\n");
        commitAs("Bob", "spaces");
        assertEquals("Bob", blame("f.txt", "a  b\nc\n")[0].author());
        // git ignores all of it or none: any kind is all.
        ignored = Whitespace.AMOUNT;
        assertEquals("Ann", blame("f.txt", "a  b\nc\n")[0].author());
        assertEquals("Ann", blame("f.txt", "HEAD", null)[0].author(), "a revision too");
    }

    @Test
    public void aLineEditedButNotCommittedIsSaidSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "first");
        final BlameCommit[] c = blame("f.txt", "a\nnew\nb\n");
        assertTrue(c[1].isUncommitted());
        assertFalse(c[0].isUncommitted());
        assertEquals("Ann", c[2].author(), "moved down a line");
    }

    @Test
    public void aRenameIsFollowedThroughPrevious() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a\nB\n");
        commitAs("Bob", "second");
        git.git("mv", "f.txt", "g.txt");
        commitAs("Bob", "rename");
        final BlameCommit[] c = blame("g.txt", "a\nB\n");
        assertEquals("second", c[1].summary());
        assertEquals("f.txt", c[1].path(), "named as it was then");
        assertEquals("f.txt", c[1].previousPath());
    }

    @Test
    public void anIgnoredRevisionIsPassedOver() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a \nb \n");
        commitAs("Bob", "reformat");
        git.write(GitBlame.IGNORE_REVS, hash("HEAD") + "\n");
        commitAs("Bob", "ignore the reformat");
        final BlameCommit[] c = blame("f.txt", "a \nb \n");
        assertEquals("first", c[0].summary());
        assertEquals("first", c[1].summary());
    }

    @Test
    public void aFileNotCommittedIsSaidToHaveNoHistory() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        commitAs("Ann", "first");
        git.write("new.txt", "x\ny\n");
        // None of it is in HEAD: the blame says so, rather than a failure.
        final VcsRepository r = Repositories.forDirectory(git.root);
        final boolean[] noHistory = new boolean[1];
        final VcsResult result = r.backend()
                .blame(r, "new.txt", null, "x\ny\n".getBytes(StandardCharsets.UTF_8), ignored, new BlameCommit.Sink() {
                    @Override
                    public void lines(int first, int count, BlameCommit commit, int source) {}

                    @Override
                    public void noHistory() {
                        noHistory[0] = true;
                    }
                });
        assertTrue(result.succeeded(), result.stderr());
        assertTrue(noHistory[0], "no history told");
    }

    @Test
    public void aRevisionIsBlamedAsItIs() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\nc\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a\nB\nc\n");
        commitAs("Bob", "second");
        // HEAD~ is the file as the first commit had it: every line Ann's.
        final BlameCommit[] c = blame("f.txt", "HEAD~", null);
        assertEquals(3, c.length);
        assertEquals("Ann", c[0].author());
        assertEquals("Ann", c[1].author());
        assertEquals("Ann", c[2].author());
        assertEquals(hash("HEAD~"), c[0].hash());
    }

    @Test
    public void theSourceLineIsWhereTheCommitHadIt() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\nc\n");
        commitAs("Ann", "first");
        // A line pushed down by an insertion above it: 'a' is line 1 now,
        // line 0 in the first commit's version.
        git.write("f.txt", "new\na\nb\nc\n");
        commitAs("Bob", "insert");
        final int[] sources = new int[4];
        final VcsRepository r = Repositories.forDirectory(git.root);
        final VcsResult result = r.backend()
                .blame(
                    r,
                    "f.txt",
                    null,
                    "new\na\nb\nc\n".getBytes(StandardCharsets.UTF_8),
                    Whitespace.NONE,
                    (first, count, c, source) -> {
                        for (int i = first; i < Math.min((long) first + count, sources.length); i++)
                            sources[i] = source + (i - first);
                    });
        assertTrue(result.succeeded(), result.stderr());
        assertEquals(0, sources[1], "'a' was line 0 in its commit");
        assertEquals(1, sources[2], "'b' was line 1");
        assertEquals(2, sources[3], "'c' was line 2");
        assertEquals(0, sources[0], "the new line is its commit's line 0");
    }

    @Test
    public void theCommitterTimeIsRead() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        // Authored a day before it was committed.
        final java.util.Map<String, String> env = new java.util.HashMap<>(GitFixture.ENV);
        env.put("GIT_AUTHOR_DATE", "@1767225600 +0000"); // 2026-01-01
        env.put("GIT_COMMITTER_DATE", "@1767312000 +0000"); // 2026-01-02
        GitCli.testEnvironment = env;
        try {
            git.commitAll("first");
        }
        finally {
            GitCli.testEnvironment = GitFixture.ENV;
        }
        final BlameCommit[] c = blame("f.txt", "a\n");
        assertEquals(1767225600L, c[0].authorTime(), "2026-01-01");
        assertEquals(1767312000L, c[0].committerTime(), "committed a day later");
    }

    @Test
    public void groupsSplitAcrossChunksAnywhereAreReadTheSame() {
        final String out = """
                           1111111111111111111111111111111111111111 1 1 2
                           author Ann
                           author-mail <ann@example.com>
                           author-time 100
                           summary first one
                           boundary
                           filename f.txt
                           2222222222222222222222222222222222222222 3 3 1
                           author Bob
                           summary second
                           previous 1111111111111111111111111111111111111111 f.txt
                           filename f.txt
                           """;
        final byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        for (int split = 1; split < bytes.length; split += 7) {
            final List<String> runs = new ArrayList<>();
            final GitBlame.Parser p = new GitBlame.Parser(
                    (first, count, c, source) -> runs.add(first + "+" + count + " " + c.author() + " " + c.previous()));
            p.feed(Arrays.copyOfRange(bytes, 0, split));
            p.feed(Arrays.copyOfRange(bytes, split, bytes.length));
            assertEquals(List.of("0+2 Ann null", "2+1 Bob 1111111111111111111111111111111111111111"), runs);
        }
        // Cut short: the last group told at the end.
        final List<String> runs = new ArrayList<>();
        final GitBlame.Parser p = new GitBlame.Parser((first, count, c, source) -> runs.add(first + "+" + count));
        p.feed("1111111111111111111111111111111111111111 1 4 2\nauthor Ann".getBytes(StandardCharsets.UTF_8));
        p.finish();
        assertEquals(List.of("3+2"), runs);
    }
}
