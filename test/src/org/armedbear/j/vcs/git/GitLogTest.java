/*
 * GitLogTest.java
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitLogTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private VcsRepository repository;

    @AfterEach
    public void tearDown() {
        GitFixture.reset();
    }

    private void init() throws Exception {
        git = GitFixture.init(tmp);
        repository = Repositories.forDirectory(git.root);
    }

    private List<String> subjects(List<LogCommit> commits) {
        final List<String> out = new ArrayList<>();
        for (LogCommit c : commits)
            out.add(c.subject());
        return out;
    }

    private String id(String rev) {
        return git.git("rev-parse", rev).strip();
    }

    @Test
    public void readsEachCommitWithItsParentsAndRefs() throws Exception {
        init();
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("tag", "v1");
        git.write("a.txt", "b\n");
        git.commitAll("second: with a colon, and é");
        final List<LogCommit> log = GitLog.read(repository, LogQuery.ALL, 0, 100);
        assertEquals(List.of("second: with a colon, and é", "first"), subjects(log));
        final LogCommit second = log.get(0);
        assertEquals(id("HEAD"), second.id());
        assertEquals(List.of(id("HEAD~")), second.parents());
        assertEquals(List.of(new LogRef("main", LogRef.Kind.BRANCH, true)), second.refs());
        assertEquals("Tester", second.author());
        assertEquals("tester@example.com", second.email());
        assertEquals(1767225600L, second.time());
        assertEquals(List.of(new LogRef("v1", LogRef.Kind.TAG, false)), log.get(1).refs());
        assertTrue(log.get(1).parents().isEmpty(), "a root");
    }

    @Test
    public void tellsBranchesWithASlashFromRemotes() {
        assertEquals(
            List.of(
                new LogRef("main", LogRef.Kind.BRANCH, true),
                new LogRef("v1", LogRef.Kind.TAG, false),
                new LogRef("origin/main", LogRef.Kind.REMOTE, false),
                new LogRef("feature/x", LogRef.Kind.BRANCH, false)),
            GitLog.refs("HEAD -> refs/heads/main, tag: refs/tags/v1, refs/remotes/origin/main, refs/heads/feature/x"));
        assertEquals(List.of(new LogRef("HEAD", LogRef.Kind.HEAD, true)), GitLog.refs("HEAD"));
        assertEquals(List.of(), GitLog.refs("refs/stash"));
    }

    @Test
    public void pagesThroughTheLog() throws Exception {
        init();
        for (int i = 0; i < 5; i++) {
            git.write("a.txt", i + "\n");
            git.commitAll("c" + i);
        }
        assertEquals(List.of("c4", "c3"), subjects(GitLog.read(repository, LogQuery.ALL, 0, 2)));
        assertEquals(List.of("c2", "c1"), subjects(GitLog.read(repository, LogQuery.ALL, 2, 2)));
        assertEquals(List.of("c0"), subjects(GitLog.read(repository, LogQuery.ALL, 4, 2)));
        assertEquals(List.of(), subjects(GitLog.read(repository, LogQuery.ALL, 5, 2)));
    }

    @Test
    public void showsEveryBranchEachCommitBeforeItsParents() throws Exception {
        init();
        git.write("a.txt", "a\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "side");
        git.write("b.txt", "b\n");
        git.commitAll("on side");
        git.git("checkout", "-q", "main");
        git.write("c.txt", "c\n");
        git.commitAll("on main");
        final List<String> all = subjects(GitLog.read(repository, LogQuery.ALL, 0, 100));
        assertEquals(3, all.size(), all.toString());
        assertEquals("base", all.get(2));
        final LogQuery main = new LogQuery(List.of("main"), null, null, null, null, null, false);
        assertEquals(List.of("on main", "base"), subjects(GitLog.read(repository, main, 0, 100)));
    }

    @Test
    public void filtersByAuthorMessageAndPath() throws Exception {
        init();
        git.write("a.txt", "a\n");
        git.commitAll("Fix the parser");
        git.write("b.txt", "needle\n");
        git.commitAll("other");
        assertEquals(
            List.of("Fix the parser"),
            subjects(GitLog.read(repository, new LogQuery(List.of(), null, null, null, "PARSER", null, false), 0, 9)),
            "message, either case");
        assertEquals(List.of("other"), subjects(GitLog.read(repository, LogQuery.file("b.txt"), 0, 9)));
        assertEquals(
            List.of("other"),
            subjects(GitLog.read(repository, new LogQuery(List.of(), null, null, null, null, "needle", false), 0, 9)));
        assertEquals(
            List.of(),
            subjects(GitLog.read(repository, new LogQuery(List.of(), "nobody", null, null, null, null, false), 0, 9)));
    }

    @Test
    public void anEmptyRepositoryOrAnOptionAsARevisionHasNoLog() throws Exception {
        init();
        assertEquals(List.of(), GitLog.read(repository, LogQuery.ALL, 0, 9));
        git.write("a.txt", "a\n");
        git.commitAll("first");
        assertEquals(
            List.of(),
            GitLog.read(repository, new LogQuery(List.of("--output=x"), null, null, null, null, null, false), 0, 9));
    }

    @Test
    public void readsACommitsChangesAndEachOnesDiff() throws Exception {
        init();
        git.write("keep.txt", "one\ntwo\nthree\nfour\nfive\n");
        git.write("gone.txt", "x\n");
        git.commitAll("first");
        final List<CommitChange> root = GitLog.changes(repository, id("HEAD"), null);
        assertEquals(
            List.of(new CommitChange("gone.txt", null, Kind.ADDED), new CommitChange("keep.txt", null, Kind.ADDED)),
            root);
        git.git("mv", "keep.txt", "kept.txt");
        git.write("kept.txt", "one\ntwo\nTHREE\nfour\nfive\n");
        git.git("rm", "-q", "gone.txt");
        git.write("new file.txt", "n\n");
        git.commitAll("second");
        final List<CommitChange> changes = GitLog.changes(repository, id("HEAD"), id("HEAD~"));
        assertEquals(
            List.of(
                new CommitChange("gone.txt", null, Kind.DELETED),
                new CommitChange("kept.txt", "keep.txt", Kind.RENAMED),
                new CommitChange("new file.txt", null, Kind.ADDED)),
            changes);
        assertEquals('R', changes.get(1).kind().letter());
        final FileDiff renamed = GitLog.diff(repository, id("HEAD"), id("HEAD~"), changes.get(1));
        assertTrue(renamed.isRename(), "the rename, not a delete and an add");
        assertEquals(1, renamed.hunks().size());
        final FileDiff rootDiff = GitLog.diff(repository, id("HEAD~"), null, root.get(1));
        assertTrue(rootDiff.isNew());
    }

    @Test
    public void readsTheWorkingTreeAgainstACommitAndAFileAsItWas() throws Exception {
        init();
        git.write("a.txt", "one\ntwo\n");
        git.commitAll("first");
        final String first = id("HEAD");
        assertNull(GitLog.toWorkingTree(repository, first, "a.txt"), "no change since");
        git.write("a.txt", "zero\none\ntwo\n");
        assertEquals(1, GitLog.toWorkingTree(repository, first, "a.txt").hunks().size());
        git.commitAll("second");
        assertArrayEquals("one\ntwo\n".getBytes(StandardCharsets.UTF_8), GitLog.fileAt(repository, first, "a.txt"));
        git.git("commit", "-q", "--allow-empty", "-m", "Subject\n\nThe body.");
        assertEquals("Subject\n\nThe body.", GitLog.message(repository, id("HEAD")));
        assertNull(GitLog.fileAt(repository, first, "missing.txt"));
    }

    @Test
    public void aRootCommitsFilesWhateverLogShowRootSays() throws Exception {
        init();
        git.git("config", "log.showRoot", "false");
        git.write("a.txt", "a\n");
        git.commitAll("first");
        final List<CommitChange> root = GitLog.changes(repository, id("HEAD"), null);
        assertEquals(List.of(new CommitChange("a.txt", null, Kind.ADDED)), root);
        assertEquals(1, GitLog.diff(repository, id("HEAD"), null, root.get(0)).hunks().size());
    }

    @Test
    public void aDiffWithNoHunksIsEmptyAndOneThatFailsNull() throws Exception {
        init();
        git.write("a.sh", "a\n");
        git.commitAll("first");
        git.git("update-index", "--chmod=+x", "a.sh");
        git.git("commit", "-q", "-m", "mode");
        final CommitChange a = new CommitChange("a.sh", null, Kind.MODIFIED);
        assertTrue(GitLog.diff(repository, id("HEAD"), id("HEAD~"), a).hunks().isEmpty());
        assertNull(GitLog.diff(repository, "0".repeat(40), id("HEAD~"), a));
    }

    @Test
    public void readsTheRefsAndTheTrackedPaths() throws Exception {
        init();
        git.write("a.txt", "a\n");
        git.write("d/b.txt", "b\n");
        git.commitAll("first");
        git.git("tag", "v1");
        git.git("branch", "feature/x");
        assertEquals(
            Set.of(
                new LogRef("feature/x", LogRef.Kind.BRANCH, false),
                new LogRef("main", LogRef.Kind.BRANCH, true),
                new LogRef("v1", LogRef.Kind.TAG, false)),
            Set.copyOf(GitLog.refTips(repository).stream().map(LogRefTip::ref).toList()));
        assertEquals(List.of("a.txt", "d/b.txt"), GitLog.paths(repository));
    }

    @Test
    public void readsTheRefTipsWithTheirCommitsAndUpstreams() throws Exception {
        init();
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("tag", "-a", "-m", "one", "v1");
        git.git("branch", "feature/x");
        final String first = id("HEAD");
        final List<LogRefTip> tips = GitLog.refTips(repository);
        final LogRefTip main = tips.stream().filter(t -> t.ref().name().equals("main")).findFirst().orElseThrow();
        assertTrue(main.ref().head(), "checked out");
        assertEquals(first, main.id());
        final LogRefTip v1 = tips.stream().filter(t -> t.ref().name().equals("v1")).findFirst().orElseThrow();
        assertEquals(first, v1.id(), "an annotated tag's commit, not the tag");
        assertEquals(LogRef.Kind.TAG, v1.ref().kind());
        // Detached: HEAD first, at its commit.
        git.git("checkout", "-q", "--detach");
        final List<LogRefTip> detached = GitLog.refTips(repository);
        assertEquals(new LogRef("HEAD", LogRef.Kind.HEAD, true), detached.get(0).ref());
        assertEquals(first, detached.get(0).id());
        // An upstream's track; a remote's HEAD left out.
        final List<LogRefTip> parsed = GitLog.parseTips(
            "*\trefs/heads/main\tc1\t\t200\t[ahead 2, behind 1]\n" + " \trefs/heads/old\tc2\t\t100\t[gone]\n"
                    + " \trefs/remotes/origin/HEAD\tc1\t\t200\t\n" + " \trefs/remotes/origin/main\tc3\t\t150\t\n");
        assertEquals(3, parsed.size());
        assertEquals("↑2 ↓1", parsed.get(0).track());
        assertEquals(200, parsed.get(0).time());
        assertEquals("gone", parsed.get(1).track());
        assertEquals("", parsed.get(2).track());
        assertEquals(new LogRef("origin/main", LogRef.Kind.REMOTE, false), parsed.get(2).ref());
        assertTrue(parsed.get(0).primary(), "no remote's HEAD: a local main");
        // origin's HEAD names the default branch, before another remote's.
        final List<LogRefTip> named = GitLog.parseTips(
            " \trefs/heads/main\tc1\t\t200\t\t\n" + " \trefs/heads/trunk\tc2\t\t100\t\t\n"
                    + " \trefs/heads/dev\tc3\t\t100\t\t\n"
                    + " \trefs/remotes/fork/HEAD\tc3\t\t100\t\trefs/remotes/fork/dev\n"
                    + " \trefs/remotes/origin/HEAD\tc2\t\t100\t\trefs/remotes/origin/trunk\n");
        assertEquals(List.of(false, true, false), named.stream().map(LogRefTip::primary).toList());
        final List<LogRefTip> master =
                GitLog.parseTips(" \trefs/heads/master\tc1\t\t200\t\t\n" + " \trefs/heads/x\tc2\t\t100\t\t\n");
        assertTrue(master.get(0).primary(), "else master");
        final List<LogRefTip> remoteOnly = GitLog.parseTips(
            " \trefs/heads/main\tc1\t\t200\t\t\n"
                    + " \trefs/remotes/origin/HEAD\tc2\t\t100\t\trefs/remotes/origin/develop\n");
        assertTrue(remoteOnly.get(0).primary(), "the remote's has no local branch: main all the same");
    }

    @Test
    public void aPathsLogNamesTheParentsItLists() throws Exception {
        init();
        git.write("a.txt", "1\n");
        git.commitAll("a1");
        final String a1 = id("HEAD");
        git.write("b.txt", "1\n");
        git.commitAll("b1");
        git.write("a.txt", "2\n");
        git.commitAll("a2");
        final List<LogCommit> log = GitLog.read(repository, LogQuery.ALL.withPath("a.txt", false), 0, 10);
        assertEquals(List.of("a2", "a1"), subjects(log));
        assertEquals(List.of(a1), log.get(0).graphParents(), "b1, not in it, left out");
        assertEquals(List.of(id("HEAD~")), log.get(0).parents(), "its own, for its diff");
        assertEquals(List.of(), log.get(1).parents());
        assertTrue(LogQuery.file("a.txt").skipsCommits(), "followed, git leaves its parents as they are");
        assertEquals(List.of(id("HEAD~")), GitLog.read(repository, LogQuery.file("a.txt"), 0, 10).get(0).parents());
    }
}
