/*
 * SvnLogTest.java
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
import java.time.Instant;
import java.util.List;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.BackendFixture;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SvnLogTest {
    @TempDir
    Path tmp;
    private SvnFixture f;
    private VcsRepository repository;

    @AfterEach
    void reset() {
        SvnFixture.reset();
    }

    // r1 adds a.txt; r2 edits it, by Other; r3 renames it b.txt; r4 adds
    // c.txt; r5 edits c.txt, dated a year later than the rest.
    private void fiveCommits() throws Exception {
        f = SvnFixture.init(tmp);
        f.write("a.txt", "a\n");
        f.add("a.txt");
        f.commitAt("add a\n\nwith a body", Instant.parse("2025-01-01T00:00:00Z"));
        f.write("a.txt", "a\nmore\n");
        f.svn("commit", "-q", "--username", "Other", "-m", "edit a");
        f.update();
        f.svn("propset", "--revprop", "-r", "2", "svn:date", "2025-01-02T00:00:00.000000Z");
        f.svn("move", "-q", "--", "a.txt", "b.txt");
        f.commitAt("rename a to b", Instant.parse("2025-01-03T00:00:00Z"));
        f.write("c.txt", "c\n");
        f.add("c.txt");
        f.commitAt("add c", Instant.parse("2025-01-04T00:00:00Z"));
        f.write("c.txt", "c\nC\n");
        f.commitAt("edit c", Instant.parse("2026-01-01T00:00:00Z"));
        repository = BackendFixture.repository(f.root, new SvnBackend());
    }

    private List<LogCommit> log(LogQuery query, int skip, int max) {
        final List<LogCommit> commits = repository.backend().readLog(repository, query, skip, max);
        assertNotNull(commits);
        return commits;
    }

    private static List<String> ids(List<LogCommit> commits) {
        return commits.stream().map(LogCommit::id).toList();
    }

    @Test
    public void fiveCommitsInOneLane() throws Exception {
        fiveCommits();
        final List<LogCommit> all = log(LogQuery.ALL, 0, 100);
        assertEquals(List.of("5", "4", "3", "2", "1"), ids(all));
        for (int i = 0; i < 4; i++) {
            assertEquals(List.of(all.get(i + 1).id()), all.get(i).parents());
            assertEquals(all.get(i).parents(), all.get(i).graphParents());
            assertTrue(all.get(i).refs().isEmpty());
        }
        assertEquals(List.of(), all.get(4).parents(), "r1 has none");
        assertEquals("add a", all.get(4).subject());
        assertEquals("Other", all.get(3).author());
        assertEquals(Instant.parse("2025-01-02T00:00:00Z").getEpochSecond(), all.get(3).time());
        assertEquals("add a\n\nwith a body", repository.backend().readMessage(repository, "1"));
    }

    @Test
    public void aPathsLog() throws Exception {
        fiveCommits();
        final LogQuery c = new LogQuery(List.of(), null, "c.txt", null, null, null, false);
        assertEquals(List.of("5", "4"), ids(log(c, 0, 100)));
        assertEquals(List.of("4"), log(c, 0, 100).get(0).parents());
    }

    @Test
    public void aRenameAndItsFiles() throws Exception {
        fiveCommits();
        final List<CommitChange> renamed = repository.backend().readCommitChanges(repository, "3", "2");
        assertEquals(List.of(new CommitChange("b.txt", "a.txt", Kind.RENAMED)), renamed);
        final List<CommitChange> edited = repository.backend().readCommitChanges(repository, "2", "1");
        assertEquals(List.of(new CommitChange("a.txt", null, Kind.MODIFIED)), edited);
        final FileDiff diff = repository.backend().readChangeDiff(repository, "2", "1", edited.get(0), Whitespace.NONE);
        assertEquals("a.txt", diff.path());
        assertEquals(1, diff.hunks().size());
        assertEquals(
            "a\n",
            new String(repository.backend().readFileAt(repository, "1", "a.txt"), StandardCharsets.UTF_8));
        assertEquals(
            "c\nC\n",
            new String(repository.backend().readFileAt(repository, "5", "c.txt"), StandardCharsets.UTF_8));
        final String commit =
                new String(repository.backend().readCommit(repository, "5", Whitespace.NONE), StandardCharsets.UTF_8);
        assertTrue(commit.contains("edit c") && commit.contains("+C"), commit);
    }

    @Test
    public void aDateBetweenCommitsAndAnAuthor() throws Exception {
        fiveCommits();
        final LogQuery since = new LogQuery(List.of(), null, null, "2025-06-01", null, null, false);
        assertEquals(List.of("5"), ids(log(since, 0, 100)), "not the one before the date");
        final LogQuery other = new LogQuery(List.of(), "other", null, null, null, null, false);
        assertEquals(List.of("2"), ids(log(other, 0, 100)));
        final LogQuery text = new LogQuery(List.of(), null, null, null, "ADD", null, false);
        assertEquals(List.of("4", "1"), ids(log(text, 0, 100)));
    }

    @Test
    public void pagesKeepTheirLastCommitsParentAndFill() throws Exception {
        fiveCommits();
        final List<LogCommit> first = log(LogQuery.ALL, 0, 2);
        assertEquals(List.of("5", "4"), ids(first));
        assertEquals(List.of("3"), first.get(1).parents(), "a page's last commit has its parent");
        assertEquals(List.of("3", "2"), ids(log(LogQuery.ALL, 2, 2)));
        assertEquals(List.of("1"), ids(log(LogQuery.ALL, 4, 2)));
        // svn's --search "a" matches every entry's path or message; the page fills with those by "Tester".
        final LogQuery tester = new LogQuery(List.of(), "tester", null, null, null, null, false);
        assertEquals(List.of("5", "4"), ids(log(tester, 0, 2)));
        assertEquals(List.of("3", "1"), ids(log(tester, 2, 2)));
    }

    @Test
    public void aFilterReachesPastWhatOneReadTakes() throws Exception {
        f = SvnFixture.init(tmp);
        f.write("a.txt", "0\n");
        f.add("a.txt");
        f.svn("commit", "-q", "--username", "Alice", "-m", "alice's first");
        for (int i = 1; i <= 110; i++) {
            f.write("a.txt", i + "\n");
            f.svn("commit", "-q", "--username", "Bob", "-m", "bob " + i);
        }
        f.write("a.txt", "last\n");
        f.svn("commit", "-q", "--username", "Alice", "-m", "alice's last");
        repository = BackendFixture.repository(f.root, new SvnBackend());
        final LogQuery alice = new LogQuery(List.of(), "alice", null, null, null, null, false);
        final List<LogCommit> first = log(alice, 0, 1);
        assertEquals(List.of("112"), ids(first));
        assertEquals(List.of("111"), first.get(0).parents(), "its real parent, not the next listed");
        assertEquals(List.of("1"), first.get(0).graphParents());
        assertEquals(List.of("1"), ids(log(alice, 1, 1)), "past the first hundred");
        final LogQuery recent = new LogQuery(List.of(), null, null, "3 days ago", null, null, false);
        assertEquals(112, log(recent, 0, 200).size(), "a time svn takes");
    }

    @Test
    public void aRevisionGivenStartsTheLog() throws Exception {
        fiveCommits();
        assertEquals(List.of("3", "2"), ids(log(LogQuery.ALL.withRevisions(List.of("3")), 0, 2)));
    }

    @Test
    public void aRevisionWithNoAuthor() throws Exception {
        fiveCommits();
        f.svn("propdel", "-q", "--revprop", "-r", "2", "svn:author");
        assertEquals("", log(LogQuery.ALL, 0, 100).get(3).author());
    }

    @Test
    public void aWorkingCopyWhoseURLIsEscaped() throws Exception {
        f = SvnFixture.init(tmp);
        f.write("my dir%x/z.txt", "z\n");
        f.add("my dir%x");
        f.commit("add the dir");
        final Path wc = tmp.toRealPath().resolve("sub");
        final var r = SvnCli
                .run(SvnCli.write(tmp.toRealPath(), "checkout", "-q", "--", f.url + "/my%20dir%25x", wc.toString()));
        assertTrue(r.succeeded(), r.stderr());
        final VcsRepository sub = BackendFixture.repository(wc, new SvnBackend());
        assertEquals(
            List.of(new CommitChange("z.txt", null, Kind.ADDED)),
            sub.backend().readCommitChanges(sub, "1", null));
        assertEquals("z\n", new String(sub.backend().readFileAt(sub, "1", "z.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void aPathDeletedAtHeadAndAReplacedFile() throws Exception {
        fiveCommits();
        final SvnFixture other = f.second();
        other.svn("rm", "-q", "--", "c.txt");
        other.commit("delete c");
        final LogQuery c = new LogQuery(List.of(), null, "c.txt", null, null, null, false);
        assertEquals(List.of("5", "4"), ids(log(c, 0, 100)), "as the working copy has it");
        f.update();
        f.svn("rm", "-q", "--", "b.txt");
        f.write("b.txt", "replaced\n");
        f.add("b.txt");
        final int r = f.commit("replace b");
        final List<CommitChange> changes = repository.backend().readCommitChanges(repository, String.valueOf(r), null);
        final FileDiff d = repository.backend()
                .readChangeDiff(repository, String.valueOf(r), null, changes.get(0), Whitespace.NONE);
        assertFalse(d.isDeleted(), "the replacement's text, not only the deletion");
        assertTrue(d.hunks().get(0).lines().stream().anyMatch(l -> l.text().equals("replaced")));
    }

    @Test
    public void aDirectorysChangesAreListed() throws Exception {
        fiveCommits();
        f.write("d/x.txt", "x\n");
        f.add("d");
        f.commit("add d");
        f.svn("move", "-q", "--", "d", "e");
        final int r = f.commit("move d");
        assertEquals(
            List.of(new CommitChange("e/", "d/", Kind.RENAMED)),
            repository.backend().readCommitChanges(repository, String.valueOf(r), null));
    }

    @Test
    public void sinceTakesDatesAndAgo() {
        final Instant now = Instant.parse("2026-03-01T00:00:00Z");
        assertEquals(Instant.parse("2026-02-15T00:00:00Z"), SvnLog.since("2 weeks ago", now));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), SvnLog.since("2026-01-02T03:04:05Z", now));
        assertThrows(IllegalArgumentException.class, () -> SvnLog.since("last tuesday", now));
    }
}
