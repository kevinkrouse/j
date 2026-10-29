/*
 * FetchCommandsTest.java
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
import java.util.EnumSet;
import java.util.List;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.vcs.FetchOption;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FetchCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture origin;
    private GitFixture git;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    // A clone of a repository with one commit, on x made from origin/main.
    private void cloned() throws Exception {
        origin = GitFixture.init(Files.createDirectory(tmp.resolve("origin")));
        origin.commitAll("one");
        git = GitFixture.init(Files.createDirectory(tmp.resolve("work")));
        git.git("remote", "add", "origin", origin.root.toString());
        git.git("fetch", "-q", "origin");
        git.git("switch", "-q", "-c", "x", "origin/main");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private VcsResult fetch(String remote, String branch, FetchOption... options) {
        final EnumSet<FetchOption> set = EnumSet.noneOf(FetchOption.class);
        set.addAll(List.of(options));
        return repo().backend().fetch(repo(), remote, branch, set);
    }

    private String at(GitFixture f, String rev) {
        return f.git("rev-parse", rev).strip();
    }

    @Test
    public void aFetchMovesTheRemotesBranches() throws Exception {
        cloned();
        origin.commitAll("two");
        final VcsResult r = fetch("origin", null);
        assertTrue(r.succeeded(), r.stderr());
        assertEquals(at(origin, "main"), at(git, "origin/main"));
        assertEquals(1, refs(r));
        assertEquals(0, refs(fetch("origin", null)));
        assertEquals("Fetched from origin: 1 ref updated", FetchCommands.fetched("origin", 1));
        assertEquals("Fetched from origin: nothing new", FetchCommands.fetched("origin", 0));
        assertEquals("Fetched from origin", FetchCommands.fetched("origin", -1));
    }

    private int refs(VcsResult r) {
        return repo().backend().fetchedRefs(r);
    }

    @Test
    public void pruneDropsTheBranchesTheRemoteNoLongerHas() throws Exception {
        cloned();
        origin.git("branch", "gone");
        fetch("origin", null);
        assertTrue(git.run("rev-parse", "--verify", "-q", "origin/gone").succeeded());
        origin.git("branch", "-D", "gone");
        fetch("origin", null);
        assertTrue(git.run("rev-parse", "--verify", "-q", "origin/gone").succeeded(), "kept without -p");
        fetch("origin", null, FetchOption.PRUNE);
        assertFalse(git.run("rev-parse", "--verify", "-q", "origin/gone").succeeded());
    }

    @Test
    public void aBranchIsFetchedAlone() throws Exception {
        cloned();
        final String main = at(git, "origin/main");
        origin.commitAll("two");
        origin.git("branch", "other");
        final VcsResult r = fetch("origin", "other");
        assertTrue(r.succeeded());
        assertEquals(at(origin, "other"), at(git, "origin/other"));
        assertEquals(main, at(git, "origin/main"), "main not fetched");
        assertEquals(1, refs(r), "not its FETCH_HEAD too");
        assertEquals(0, refs(fetch("origin", "other")), "nothing new, though FETCH_HEAD is written");
    }

    @Test
    public void allRemotesAndAFailingOneThatAsksNothing() throws Exception {
        cloned();
        origin.commitAll("two");
        assertTrue(fetch(null, null).succeeded());
        assertEquals(at(origin, "main"), at(git, "origin/main"));
        git.git("remote", "add", "nowhere", tmp.resolve("missing").toString());
        assertFalse(fetch("nowhere", null).succeeded());
    }

    @Test
    public void theBranchsRemotes() throws Exception {
        cloned();
        FetchCommands.Remotes rs = FetchCommands.remotes(repo());
        assertEquals(List.of("origin"), rs.all());
        assertEquals("origin", rs.upstream());
        assertNull(rs.push(), "simple pushes nowhere to an upstream of another name");
        git.git("push", "-q", "-u", "origin", "x");
        repo().invalidate();
        rs = FetchCommands.remotes(repo());
        assertEquals("origin", rs.push());
    }

    @Test
    public void aRemoteIsTheLongestItsBranchStartsWith() {
        assertEquals("a/b", FetchCommands.remoteOf("a/b/c", List.of("a", "a/b")));
        assertEquals("a", FetchCommands.remoteOf("a/c", List.of("a", "a/b")));
        assertNull(FetchCommands.remoteOf("main", List.of("a")));
    }

    @Test
    public void thePopupDimsWhatHasNoRemote() {
        final Transient none = FetchCommands.popup(new FetchCommands.Remotes(List.of(), null, null));
        for (String key : List.of("p", "u", "a", "e", "o"))
            assertNull(((Command) none.item(key)).command(), key);
        final Transient set = FetchCommands.popup(new FetchCommands.Remotes(List.of("origin"), "origin", "upstream"));
        assertEquals("vcsFetchPushRemote", ((Command) set.item("p")).command());
        assertEquals("origin", set.item("p").description());
        assertEquals("upstream", set.item("u").description());
        assertEquals("vcsFetchAll", ((Command) set.item("a")).command());
    }
}
