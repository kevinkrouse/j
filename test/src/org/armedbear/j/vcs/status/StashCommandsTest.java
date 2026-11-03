/*
 * StashCommandsTest.java
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
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Finish;
import org.armedbear.j.vcs.InProgress;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.StashOption;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusData.StashItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StashCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture git;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    // a.txt committed, then changed and staged, b.txt changed, c.txt new.
    private void repository() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", "a staged\n");
        git.git("add", "a.txt");
        git.write("b.txt", "b changed\n");
        git.write("c.txt", "new\n");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private VcsResult stash(String message, StashOption... options) {
        final EnumSet<StashOption> set = EnumSet.noneOf(StashOption.class);
        set.addAll(List.of(options));
        return repo().backend().stash(repo(), message, set);
    }

    private String status() {
        return git.git("status", "--porcelain");
    }

    private String hash(String rev) {
        return git.git("rev-parse", rev).strip();
    }

    @Test
    public void aStashTakesTheChangesWithItsMessage() throws Exception {
        repository();
        assertTrue(stash("saved").succeeded());
        assertEquals("?? c.txt\n", status(), "untracked files stay");
        assertEquals("On main: saved", git.git("stash", "list", "--format=%gs").strip());
    }

    @Test
    public void keepingTheIndexLeavesWhatIsStaged() throws Exception {
        repository();
        assertTrue(stash(null, StashOption.KEEP_INDEX).succeeded());
        assertEquals("M  a.txt\n?? c.txt\n", status());
    }

    @Test
    public void withUntrackedFilesTakesThemToo() throws Exception {
        repository();
        assertTrue(stash(null, StashOption.UNTRACKED).succeeded());
        assertEquals("", status());
    }

    @Test
    public void popAppliesAndDropsAndApplyKeeps() throws Exception {
        repository();
        stash(null);
        final String id = hash("stash@{0}");
        assertTrue(repo().backend().stashApply(repo(), "stash@{0}", id, false).succeeded());
        assertTrue(status().contains(" M b.txt"));
        assertEquals(id, hash("stash@{0}"), "apply keeps it");
        git.git("checkout", "--", "b.txt");
        git.git("reset", "-q", "--hard");
        assertTrue(repo().backend().stashApply(repo(), "stash@{0}", id, true).succeeded());
        assertTrue(status().contains(" M b.txt"));
        assertFalse(git.run("rev-parse", "-q", "--verify", "stash@{0}").succeeded(), "pop drops it");
    }

    @Test
    public void aStashThatMovedIsLeftAlone() throws Exception {
        repository();
        stash("older");
        final String older = hash("stash@{0}");
        git.write("b.txt", "b again\n");
        stash("newer");
        // stash@{0} is no longer the one asked for.
        final VcsResult r = repo().backend().stashDrop(repo(), "stash@{0}", older);
        assertFalse(r.succeeded());
        assertTrue(r.stderr().contains("no longer"), r.stderr());
        assertEquals(2, git.git("stash", "list").strip().split("\n").length);
        assertFalse(repo().backend().stashApply(repo(), "stash@{0}", older, true).succeeded());
        assertEquals(2, git.git("stash", "list").strip().split("\n").length);
        // Applied by its hash, the one shown applies.
        git.git("reset", "-q", "--hard");
        assertTrue(repo().backend().stashApply(repo(), "stash@{0}", older, false).succeeded());
        assertEquals("b changed\n", git.read("b.txt"));
        git.git("reset", "-q", "--hard");
        assertTrue(repo().backend().stashDrop(repo(), "stash@{1}", older).succeeded());
        assertEquals("On main: newer", git.git("stash", "list", "--format=%gs").strip());
    }

    @Test
    public void aConflictingPopKeepsTheStashAndSaysSo() throws Exception {
        repository();
        stash(null);
        final String id = hash("stash@{0}");
        git.write("b.txt", "b otherwise\n");
        git.commitAll("second");
        final VcsResult r = repo().backend().stashApply(repo(), "stash@{0}", id, true);
        assertFalse(r.succeeded());
        assertEquals(id, hash("stash@{0}"), "kept");
        assertEquals(
            "Popping stash@{0} conflicted: the stash is kept, and the conflicts are in Unmerged",
            StashCommands.failed("Popping stash@{0}", r));
    }

    // The conflicted pop of a stash, noted on the repository until its
    // conflicts are resolved.
    private void conflicted(boolean pop) throws Exception {
        repository();
        stash(null);
        final String id = hash("stash@{0}");
        git.write("b.txt", "b otherwise\n");
        git.commitAll("second");
        final VcsResult r = StashCommands.applyStash(repo(), new StashItem("stash@{0}", id, "", List.of()), pop);
        assertFalse(r.succeeded());
        assertEquals(new VcsRepository.StashConflict("stash@{0}", id, pop, hash("HEAD")), repo().stashConflict());
        repo().invalidate();
        final StatusData d = repo().backend().readStatus(repo(), (a, b) -> false, Whitespace.NONE);
        assertEquals((pop ? "Stash pop" : "Stash apply") + " in progress (1 conflict)", d.inProgress());
        // Resolving the conflict ends an apply's note; a pop's lasts, to be continued.
        git.git("checkout", "--theirs", "b.txt");
        git.git("add", "b.txt");
        repo().invalidate();
        repo().snapshotNow();
        if (pop) {
            assertEquals(new VcsRepository.StashConflict("stash@{0}", id, true, hash("HEAD")), repo().stashConflict());
            assertEquals(
                "Stash pop in progress",
                repo().backend().readStatus(repo(), (a, b) -> false, Whitespace.NONE).inProgress());
        } else {
            assertNull(repo().stashConflict());
            assertNull(repo().backend().readStatus(repo(), (a, b) -> false, Whitespace.NONE).inProgress());
        }
    }

    @Test
    public void aConflictingPopIsNotedUntilContinued() throws Exception {
        conflicted(true);
    }

    @Test
    public void aConflictingApplyIsNotedUntilItsConflictsAreGone() throws Exception {
        conflicted(false);
    }

    @Test
    public void continuingAPopDropsItsStash() throws Exception {
        conflicted(true);
        assertTrue(Files.exists(git.root.resolve(".git/j-stash-conflict")), "kept in git's directory");
        assertTrue(FinishCommands.perform(repo(), InProgress.STASH_POP, Finish.CONTINUE).succeeded());
        assertNull(repo().stashConflict());
        assertFalse(Files.exists(git.root.resolve(".git/j-stash-conflict")), "the note gone");
        assertTrue(git.run("stash", "list").out().isEmpty(), "the stash dropped");
    }

    @Test
    public void abortingAPopTakesItsChangesBackAndKeepsTheStash() throws Exception {
        repository();
        stash(null);
        final String id = hash("stash@{0}");
        git.write("b.txt", "b otherwise\n");
        git.commitAll("second");
        StashCommands.applyStash(repo(), new StashItem("stash@{0}", id, "", List.of()), true);
        assertTrue(FinishCommands.perform(repo(), InProgress.STASH_POP, Finish.ABORT).succeeded());
        assertNull(repo().stashConflict());
        assertEquals("b otherwise\n", git.read("b.txt"));
        assertTrue(git.run("ls-files", "-u").out().isEmpty(), "no conflict left");
        assertFalse(git.run("stash", "list").out().isEmpty(), "the stash kept");
    }

    @Test
    public void aPopsNoteGoesWithItsStashDroppedElsewhere() throws Exception {
        conflicted(true);
        git.git("stash", "drop", "-q");
        repo().invalidate();
        repo().snapshotNow();
        assertNull(repo().stashConflict());
    }

    @Test
    public void aPopsNoteEndsWhenItsResolutionIsCommitted() throws Exception {
        conflicted(true);
        git.git("commit", "-q", "-m", "resolved");
        repo().invalidate();
        repo().snapshotNow();
        assertNull(repo().stashConflict());
    }

    @Test
    public void continuingAPopFindsItsStashMovedByAnother() throws Exception {
        conflicted(true);
        final String id = hash("stash@{0}");
        // Another stash, made without touching the working tree, on top.
        final String other = git.git("stash", "create").strip();
        git.git("stash", "store", "-m", "another", other);
        assertEquals(id, hash("stash@{1}"));
        assertTrue(FinishCommands.perform(repo(), InProgress.STASH_POP, Finish.CONTINUE).succeeded());
        assertEquals(1, git.run("stash", "list").out().lines().count(), "the other kept");
        assertEquals(other, hash("stash@{0}"));
    }

    @Test
    public void aStashIsAbortedOnlyWhileItsConflictsAre() {
        assertEquals(List.of(Finish.CONTINUE, Finish.ABORT), FinishCommands.offered(InProgress.STASH_POP, true));
        assertEquals(List.of(Finish.CONTINUE), FinishCommands.offered(InProgress.STASH_POP, false));
        assertEquals(List.of(Finish.ABORT), FinishCommands.offered(InProgress.STASH_APPLY, true));
    }

    @Test
    public void aPopsNoteOutlivesJ() throws Exception {
        conflicted(true);
        final VcsRepository.StashConflict kept = repo().backend().readStashNote(repo());
        assertEquals(repo().stashConflict(), kept);
    }

    @Test
    public void aCleanPopNotesNothing() throws Exception {
        repository();
        stash(null);
        final String id = hash("stash@{0}");
        final VcsResult r = StashCommands.applyStash(repo(), new StashItem("stash@{0}", id, "", List.of()), true);
        assertTrue(r.succeeded());
        assertNull(repo().stashConflict());
    }

    @Test
    public void aCleanApplyOfAFileNamedConflictNotesNothing() throws Exception {
        git = GitFixture.init(tmp);
        git.write("CONFLICTS.md", "a\n");
        git.commitAll("first");
        git.write("CONFLICTS.md", "b\n");
        stash(null);
        final String id = hash("stash@{0}");
        final VcsResult r = StashCommands.applyStash(repo(), new StashItem("stash@{0}", id, "", List.of()), false);
        assertTrue(r.succeeded());
        assertTrue(r.out().contains("CONFLICTS.md"), r.out());
        assertNull(repo().stashConflict());
    }

    @Test
    public void thePopupNamesTheStashItUsesOrDimsThoseLines() {
        final Transient none = StashCommands.popup(null);
        for (String key : List.of("a", "p", "k"))
            assertNull(((Command) none.item(key)).command(), key);
        assertEquals("vcsStashSave", ((Command) none.item("z")).command());
        final Transient one = StashCommands.popup(new StashItem("stash@{1}", "abc", "On main: x", null));
        assertEquals("vcsStashPop", ((Command) one.item("p")).command());
        assertEquals("pop stash@{1}", one.item("p").description());
    }
}
