/*
 * SvnBlameTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.BackendFixture;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.blame.BlameCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SvnBlameTest {
    @TempDir
    Path tmp;
    private SvnFixture f;
    private VcsRepository repository;

    @AfterEach
    void reset() {
        SvnFixture.reset();
    }

    // Each line's commit, as blame told it.
    private static final class Lines implements BlameCommit.Sink {
        final List<BlameCommit> byLine = new ArrayList<>();
        final List<Integer> sources = new ArrayList<>();
        boolean noHistory;

        @Override
        public void lines(int first, int count, BlameCommit commit, int source) {
            sources.add(source);
            while (byLine.size() < first + count)
                byLine.add(null);
            for (int i = first; i < first + count; i++)
                byLine.set(i, commit);
        }

        @Override
        public void noHistory() {
            noHistory = true;
        }

        List<String> revisions() {
            return byLine.stream().map(c -> c.isUncommitted() ? "-" : c.hash()).toList();
        }
    }

    // r1 adds a.txt of three lines; r2, by Other, changes its second.
    private void twoCommits() throws Exception {
        f = SvnFixture.init(tmp);
        f.write("a.txt", "one\ntwo\nthree\n");
        f.add("a.txt");
        f.commit("add a\n\nbody");
        f.write("a.txt", "one\nTWO\nthree\n");
        f.svn("commit", "-q", "--username", "Other", "-m", "change two");
        f.update();
        repository = BackendFixture.repository(f.root, new SvnBackend());
    }

    private Lines blame(String revision, String contents, Whitespace ignored) {
        final Lines lines = new Lines();
        final VcsResult r = repository.backend()
                .blame(
                    repository,
                    "a.txt",
                    revision,
                    contents == null ? null : contents.getBytes(StandardCharsets.UTF_8),
                    ignored,
                    lines);
        assertTrue(r.succeeded(), r.stderr());
        return lines;
    }

    @Test
    public void committedLines() throws Exception {
        twoCommits();
        final Lines lines = blame(null, "one\nTWO\nthree\n", Whitespace.NONE);
        assertEquals(List.of("1", "2", "1"), lines.revisions());
        final BlameCommit two = lines.byLine.get(1);
        assertEquals("Other", two.author());
        assertEquals("change two", two.summary());
        assertEquals("1", two.previous());
        assertEquals("add a", lines.byLine.get(0).summary());
        assertNull(lines.byLine.get(0).previous(), "r1 has none before it");
    }

    @Test
    public void anEditedLineIsUncommitted() throws Exception {
        twoCommits();
        final Lines lines = blame(null, "zero\none\nTWO edited\nthree\n", Whitespace.NONE);
        assertEquals(List.of("-", "1", "-", "1"), lines.revisions());
        final Lines ws = blame(null, "one\nTWO  \nthree\n", Whitespace.AMOUNT);
        assertEquals(List.of("1", "2", "1"), ws.revisions(), "a change to whitespace alone, ignored");
    }

    @Test
    public void aRevisionsBlame() throws Exception {
        twoCommits();
        assertEquals(List.of("1", "1", "1"), blame("1", null, Whitespace.NONE).revisions());
        assertEquals(List.of("1", "2", "1"), blame("2", null, Whitespace.NONE).revisions());
    }

    @Test
    public void aRevisionFromBeforeARename() throws Exception {
        twoCommits();
        f.svn("move", "-q", "--", "a.txt", "b.txt");
        f.commit("rename");
        final Lines lines = new Lines();
        final VcsResult r = repository.backend().blame(repository, "b.txt", "2", null, Whitespace.NONE, lines);
        assertTrue(r.succeeded(), r.stderr());
        assertEquals(List.of("1", "2", "1"), lines.revisions());
    }

    @Test
    public void aLinesPlaceInItsCommitIsntGuessed() throws Exception {
        twoCommits();
        final Lines lines = blame(null, "new\none\nTWO\nthree\n", Whitespace.NONE);
        assertTrue(lines.sources.stream().allMatch(s -> s == -1), lines.sources.toString());
    }

    @Test
    public void aBranchsHistoryFromBeforeItsCopy() throws Exception {
        f = SvnFixture.init(tmp);
        f.write("trunk/a.txt", "one\ntwo\n");
        f.add("trunk");
        f.commit("add a");
        f.svn("copy", "-q", "-m", "branch", "--username", "Tester", f.url + "/trunk", f.url + "/branch");
        final Path wc = tmp.toRealPath().resolve("branch-wc");
        final VcsResult co =
                SvnCli.run(SvnCli.write(tmp.toRealPath(), "checkout", "-q", "--", f.url + "/branch", wc.toString()));
        assertTrue(co.succeeded(), co.stderr());
        repository = BackendFixture.repository(wc, new SvnBackend());
        final Lines lines = new Lines();
        final VcsResult r = repository.backend().blame(repository, "a.txt", "1", null, Whitespace.NONE, lines);
        assertTrue(r.succeeded(), r.stderr());
        assertEquals(List.of("1", "1"), lines.revisions());
        assertEquals("add a", lines.byLine.get(0).summary());
        final var changes = repository.backend().readCommitChanges(repository, "1", null);
        assertEquals(
            List.of(new org.armedbear.j.vcs.log.CommitChange("a.txt", null, org.armedbear.j.vcs.FileChange.Kind.ADDED)),
            changes);
        assertTrue(repository.backend().readChangeDiff(repository, "1", null, changes.get(0), Whitespace.NONE).isNew());
    }

    @Test
    public void aCopyNotCommittedAndABinaryMimeType() throws Exception {
        twoCommits();
        f.svn("copy", "-q", "--", "a.txt", "copy.txt");
        final Lines copy = new Lines();
        assertTrue(
            repository.backend()
                    .blame(
                        repository,
                        "copy.txt",
                        null,
                        "one\nTWO\nthree\n".getBytes(StandardCharsets.UTF_8),
                        Whitespace.NONE,
                        copy)
                    .succeeded());
        assertEquals("change two", copy.byLine.get(1).summary());
        f.propset("svn:mime-type", "application/octet-stream", "a.txt");
        f.commit("binary");
        final Lines bin = blame(null, "one\nTWO\nthree\n", Whitespace.NONE);
        assertEquals(List.of("1", "2", "1"), bin.revisions());
    }

    @Test
    public void aFileWithNoHistory() throws Exception {
        twoCommits();
        f.write("new.txt", "n\n");
        f.add("new.txt");
        f.write("unv.txt", "u\n");
        for (String p : List.of("new.txt", "unv.txt")) {
            final Lines lines = new Lines();
            final VcsResult r = repository.backend()
                    .blame(repository, p, null, "n\n".getBytes(StandardCharsets.UTF_8), Whitespace.NONE, lines);
            assertTrue(r.succeeded(), r.stderr());
            assertTrue(lines.noHistory, p);
            assertTrue(lines.byLine.isEmpty());
        }
    }
}
