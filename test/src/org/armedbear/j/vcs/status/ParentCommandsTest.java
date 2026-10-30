/*
 * ParentCommandsTest.java
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

package org.armedbear.j.vcs.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.Snapshot.Relation;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ParentCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture git;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    // A clone of a repository with main and feature, on x made from
    // origin/main and pushed, with a local branch a.
    private void cloned() throws Exception {
        final GitFixture origin = GitFixture.init(Files.createDirectory(tmp.resolve("origin")));
        origin.commitAll("one");
        origin.git("branch", "feature");
        git = GitFixture.init(Files.createDirectory(tmp.resolve("work")));
        git.git("remote", "add", "origin", origin.root.toString());
        git.git("fetch", "-q", "origin");
        git.git("remote", "set-head", "origin", "main");
        git.git("switch", "-q", "-c", "x", "origin/main");
        git.git("push", "-q", "-u", "origin", "x");
        git.git("branch", "-q", "a");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private Relation parent() {
        repo().invalidate();
        return repo().snapshotNow().parent();
    }

    @Test
    public void theRecordIsGitHubsAndRemovingItFindsTheParentAgain() throws Exception {
        cloned();
        assertEquals("reflog", parent().source());
        assertTrue(repo().backend().setParent(repo(), "x", "a").succeeded());
        assertEquals("a", git.git("config", "branch.x.gh-merge-base").strip());
        assertEquals(new Relation("a", "gh-merge-base", 0, 0), parent());
        assertTrue(repo().backend().setParent(repo(), "x", null).succeeded());
        assertFalse(git.run("config", "branch.x.gh-merge-base").succeeded());
        assertEquals("reflog", parent().source());
        assertTrue(repo().backend().setParent(repo(), "x", null).succeeded(), "none to remove is no failure");
    }

    @Test
    public void theBranchesAreTheOthersARemotesByItsBareName() throws Exception {
        cloned();
        final ParentCommands.Branches b = ParentCommands.branches(repo());
        assertEquals("x", b.branch());
        final Map<String, String> names = b.names();
        assertEquals("a", names.get("a"));
        assertEquals("main", names.get("origin/main"));
        assertEquals("feature", names.get("origin/feature"));
        assertFalse(names.containsKey("x"), "not itself");
        assertFalse(names.containsKey("origin/x"), "nor its own copy");
        assertFalse(names.containsKey("origin/HEAD"));
    }

    @Test
    public void aNameIsRecordedListedOrBare() {
        final Map<String, String> names = Map.of("a", "a", "origin/feature", "feature");
        assertEquals("feature", ParentCommands.recorded("origin/feature", names));
        assertEquals("feature", ParentCommands.recorded("feature", names));
        assertEquals("a", ParentCommands.recorded("a", names));
        assertNull(ParentCommands.recorded("nothing", names));
    }

    @Test
    public void anotherRemotesBranchIsRecordedWholeAndReadBackAsIts() throws Exception {
        cloned();
        final GitFixture fork = GitFixture.init(Files.createDirectory(tmp.resolve("fork")));
        fork.commitAll("fork's");
        fork.git("branch", "develop");
        git.git("remote", "add", "upstream", fork.root.toString());
        git.git("fetch", "-q", "upstream");
        final Map<String, String> names = ParentCommands.branches(repo()).names();
        assertEquals("upstream/main", names.get("upstream/main"), "not main, origin's");
        assertEquals("main", names.get("origin/main"));
        repo().backend().setParent(repo(), "x", names.get("upstream/develop"));
        assertEquals("upstream/develop", parent().name());
        assertEquals("gh-merge-base", parent().source());
    }

    @Test
    public void aRemotesBranchRecordedBareIsReadAsTheRemotes() throws Exception {
        cloned();
        repo().backend().setParent(repo(), "x", "feature");
        assertEquals(new Relation("origin/feature", "gh-merge-base", 0, 0), parent());
    }
}
