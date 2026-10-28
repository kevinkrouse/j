/*
 * GitRelationsTest.java
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.Snapshot.Relation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parent, one test per row of GIT-TOOLING.md's "Parent branch". */
public class GitRelationsTest {
    @TempDir
    Path tmp;

    private GitFixture f;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    // A clone of a repository with one commit on main, origin/HEAD set.
    private void cloned() throws IOException {
        final GitFixture origin = GitFixture.init(Files.createDirectory(tmp.resolve("origin")));
        origin.commitAll("one");
        f = GitFixture.init(Files.createDirectory(tmp.resolve("work")));
        f.git("remote", "add", "origin", origin.root.toString());
        f.git("fetch", "-q", "origin");
        f.git("remote", "set-head", "origin", "main");
        f.git("reset", "-q", "--hard", "origin/main");
        f.git("branch", "-q", "-u", "origin/main");
    }

    private GitSnapshot snapshot() {
        return (GitSnapshot) Repositories.forDirectory(f.root).snapshotNow();
    }

    private Relation parent() {
        return snapshot().parent();
    }

    @Test
    public void ghMergeBaseComesFirst() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "x", "origin/main");
        f.git("branch", "-q", "other");
        f.git("config", "branch.x.gh-merge-base", "other");
        assertEquals(new Relation("other", "gh-merge-base", 0, 0), parent());
    }

    @Test
    public void anUpstreamPushedToElsewhereIsTheParent() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "x", "origin/main");
        f.commitAll("two");
        GitSnapshot s = snapshot();
        assertEquals(new Relation("origin/main", "upstream", 1, 0), s.parent());
        assertNull(s.push(), "simple pushes nowhere to an upstream of another name");
        f.git("config", "push.default", "current");
        s = snapshot();
        assertEquals(new Relation("origin/main", "upstream", 1, 0), s.parent());
        assertEquals("origin/x", s.push().name());
        assertTrue(s.push().gone(), "not pushed yet");
    }

    @Test
    public void anUpstreamPushedToIsNotTheParent() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "x", "main");
        f.commitAll("two");
        f.git("push", "-q", "-u", "origin", "x");
        final GitSnapshot s = snapshot();
        assertEquals(new Relation("origin/x", null, 0, 0), s.upstream());
        assertEquals(new Relation("origin/x", null, 0, 0), s.push());
        assertEquals(new Relation("main", "reflog", 1, 0), s.parent());
    }

    @Test
    public void gitTownsParent() throws IOException {
        cloned();
        f.git("branch", "-q", "a");
        f.git("switch", "-q", "-c", "b");
        f.git("config", "git-town-branch.b.parent", "a");
        assertEquals("a", parent().name());
        assertEquals("git-town", parent().source());
    }

    @Test
    public void macheteParent() throws IOException {
        cloned();
        f.git("branch", "-q", "a");
        f.git("switch", "-q", "-c", "b");
        f.write(".git/machete", "main\n    a  PR #1\n        b\n    c\n");
        assertEquals(new Relation("a", "machete", 0, 0), parent());
    }

    @Test
    public void vscodeMergeBase() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "x");
        f.git("config", "branch.x.vscode-merge-base", "origin/main");
        assertEquals(new Relation("origin/main", "vscode", 0, 0), parent());
    }

    @Test
    public void reflogCreatedFrom() throws IOException {
        cloned();
        f.git("branch", "-q", "a");
        f.git("switch", "-q", "-c", "b", "a");
        assertEquals(new Relation("a", "reflog", 0, 0), parent());
    }

    @Test
    public void reflogCreatedFromHeadIsTheCheckoutToIt() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "a");
        f.git("switch", "-q", "-c", "b");
        f.git("switch", "-q", "main");
        f.git("switch", "-q", "b");
        assertEquals(new Relation("a", "reflog", 0, 0), parent());
    }

    @Test
    public void defaultsToOriginHead() throws IOException {
        cloned();
        f.git("switch", "-q", "--detach");
        f.git("branch", "-q", "-D", "main");
        f.git("switch", "-q", "-c", "x");
        f.git("reflog", "expire", "--expire=all", "--all");
        assertEquals(new Relation("origin/main", "default", 0, 0), parent());
    }

    @Test
    public void defaultsToMainThenMaster() throws IOException {
        f = GitFixture.init(tmp);
        f.commitAll("one");
        f.git("branch", "-q", "-m", "master");
        f.git("switch", "-q", "-c", "x");
        f.git("reflog", "expire", "--expire=all", "--all");
        assertEquals(new Relation("master", "default", 0, 0), parent());
        f.git("branch", "-q", "main", "master");
        assertEquals(new Relation("main", "default", 0, 0), parent());
    }

    @Test
    public void aBranchIsNotItsOwnParent() throws IOException {
        f = GitFixture.init(tmp);
        f.commitAll("one");
        assertNull(parent());
    }

    @Test
    public void aRecordOfABranchThereIsNotIsPassedOver() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "x", "main");
        f.git("config", "branch.x.gh-merge-base", "deleted");
        assertEquals(new Relation("main", "reflog", 0, 0), parent());
    }

    @Test
    public void anUnbornBranchHasNone() throws IOException {
        f = GitFixture.init(tmp);
        final GitSnapshot s = snapshot();
        assertNull(s.parent());
        assertNull(s.push());
    }

    @Test
    public void aDetachedHeadHasNone() throws IOException {
        cloned();
        f.git("switch", "-q", "--detach");
        final GitSnapshot s = snapshot();
        assertNull(s.upstream());
        assertNull(s.parent());
    }

    @Test
    public void aGoneUpstreamIsNotTheParent() throws IOException {
        cloned();
        f.git("switch", "-q", "-c", "x", "main");
        f.git("push", "-q", "-u", "origin", "x");
        f.git("push", "-q", "origin", "--delete", "x");
        f.git("fetch", "-q", "-p");
        f.git("reflog", "expire", "--expire=all", "--all");
        final GitSnapshot s = snapshot();
        assertTrue(s.upstream().gone());
        assertTrue(s.push().gone());
        assertEquals(new Relation("origin/main", "default", 0, 0), s.parent());
    }

    @Test
    public void readsTrack() {
        assertArrayEquals(new int[] { 1, 2 }, GitRelations.track("[ahead 1, behind 2]"));
        assertArrayEquals(new int[] { 0, 3 }, GitRelations.track("[behind 3]"));
        assertArrayEquals(new int[] { 0, 0 }, GitRelations.track(""));
        assertArrayEquals(new int[] { -1, -1 }, GitRelations.track("[gone]"));
    }

    @Test
    public void readsAMacheteTree() throws IOException {
        final Path file = tmp.resolve("machete");
        Files.writeString(file, "main\n  a\n    b  PR #2\n  c\nother\n  d\n");
        assertEquals("main", GitRelations.machete(file, "a"));
        assertEquals("a", GitRelations.machete(file, "b"));
        assertEquals("main", GitRelations.machete(file, "c"));
        assertEquals("other", GitRelations.machete(file, "d"));
        assertNull(GitRelations.machete(file, "main"));
        assertNull(GitRelations.machete(file, "missing"));
    }
}
