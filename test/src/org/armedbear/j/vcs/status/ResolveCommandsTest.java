/*
 * ResolveCommandsTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Conflict;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.ResolveCommands.Take;
import org.armedbear.j.vcs.status.StatusActions.Files;
import org.armedbear.j.vcs.status.StatusActions.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ResolveCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture git;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    private static FileItem item(String path, Conflict conflict) {
        return new FileItem(new FileChange(path, null, Kind.CONFLICTED, Kind.CONFLICTED, "UU", conflict),
                'U',
                false,
                null);
    }

    // Unmerged files of no repository, for the popup in a status of one.
    private static List<Target> unmerged(FileItem... items) {
        return List.of(new Files(null, StatusData.UNMERGED, List.of(items)));
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private Target files(String path, Conflict conflict) {
        return new Files(repo(), StatusData.UNMERGED, List.of(item(path, conflict)));
    }

    // ---- The popup's choices by kind ----

    @Test
    public void bothModifiedOffersOursTheirsAndAgain() {
        final Transient p = ResolveCommands.popup(unmerged(item("a.txt", Conflict.BOTH_MODIFIED)), false);
        assertNotNull(p);
        assertNotNull(p.item("o"));
        assertNotNull(p.item("t"));
        assertNotNull(p.item("c"));
        assertNull(p.item("k"));
        assertNull(p.item("d"));
    }

    @Test
    public void bothAddedOffersOursTheirsAndAgain() {
        final Transient p = ResolveCommands.popup(unmerged(item("a.txt", Conflict.BOTH_ADDED)), false);
        assertNotNull(p.item("o"));
        assertNotNull(p.item("t"));
        assertNotNull(p.item("c"));
    }

    @Test
    public void deletedByThemOffersKeepAndDelete() {
        final Transient p = ResolveCommands.popup(unmerged(item("a.txt", Conflict.DELETED_BY_THEM)), false);
        assertNotNull(p.item("k"));
        assertNotNull(p.item("d"));
        assertNull(p.item("o"));
        assertNull(p.item("c"));
    }

    @Test
    public void bothDeletedOffersOnlyDelete() {
        final Transient p = ResolveCommands.popup(unmerged(item("a.txt", Conflict.BOTH_DELETED)), false);
        assertNotNull(p.item("d"));
        assertNull(p.item("k"));
        assertNull(p.item("o"));
    }

    @Test
    public void aMixOfKindsHasNoPopup() {
        assertNull(
            ResolveCommands.popup(
                unmerged(item("a.txt", Conflict.BOTH_MODIFIED), item("b.txt", Conflict.DELETED_BY_THEM)),
                false));
    }

    @Test
    public void withSeveralRepositoriesThePopupNamesEachFilesRepository() throws Exception {
        bothModified();
        final Transient p = ResolveCommands.popup(List.of(files("a.txt", Conflict.BOTH_MODIFIED)), true);
        assertEquals("Resolve a.txt in " + repo().name(), p.title());
    }

    // ---- resolve, on real conflicts ----

    // ours and theirs both changed a.txt.
    private void bothModified() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "base\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "theirs\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
    }

    // a.txt changed on main, deleted on other: a modify/delete conflict,
    // git leaves our version in the tree.
    private void deletedByThem() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "base\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.git("rm", "-q", "a.txt");
        git.commitAll("gone");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
    }

    // a.txt deleted on both sides. A merge resolves that on its own, so the
    // index is built by hand: stage 1 (the base) and nothing else, with the
    // file gone from the worktree. HEAD keeps its copy, so the deletion shows.
    private void bothDeleted() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "base\n");
        git.commitAll("first");
        final String base = git.git("rev-parse", "HEAD:a.txt").trim();
        git.git("rm", "-q", "--cached", "a.txt");
        git.updateIndex("1 " + base + " 1\ta.txt\n");
        java.nio.file.Files.delete(git.root.resolve("a.txt"));
    }

    // Whether git still has an unmerged entry for a.txt.
    private boolean unmerged() {
        return !git.git("ls-files", "-u").isEmpty();
    }

    @Test
    public void resolveOursTakesOurTextAndStagesIt() throws Exception {
        bothModified();
        assertNull(ResolveCommands.resolve(List.of(files("a.txt", Conflict.BOTH_MODIFIED)), Take.OURS));
        assertEquals("ours\n", git.read("a.txt"));
        assertFalse(unmerged(), "no unmerged path left");
        assertEquals("ours\n", git.index("a.txt"), "our version staged");
    }

    @Test
    public void resolveTheirsTakesTheirTextAndStagesIt() throws Exception {
        bothModified();
        assertNull(ResolveCommands.resolve(List.of(files("a.txt", Conflict.BOTH_MODIFIED)), Take.THEIRS));
        assertEquals("theirs\n", git.read("a.txt"));
        assertFalse(unmerged());
        assertEquals("theirs\n", git.index("a.txt"), "their version staged");
    }

    @Test
    public void resolveKeepOnDeletedByThemKeepsItStaged() throws Exception {
        deletedByThem();
        assertNull(ResolveCommands.resolve(List.of(files("a.txt", Conflict.DELETED_BY_THEM)), Take.KEEP));
        assertEquals("ours\n", git.read("a.txt"));
        assertFalse(unmerged());
        assertEquals("ours\n", git.index("a.txt"), "our version kept, staged");
    }

    @Test
    public void resolveDeleteOnDeletedByThemGoesAndStagesDeletion() throws Exception {
        deletedByThem();
        assertNull(ResolveCommands.resolve(List.of(files("a.txt", Conflict.DELETED_BY_THEM)), Take.DELETE));
        assertFalse(java.nio.file.Files.exists(git.root.resolve("a.txt")), "gone");
        assertFalse(unmerged());
        assertTrue(git.git("status", "--porcelain").contains("D  a.txt"), "deletion staged");
    }

    @Test
    public void resolveDeleteOnBothDeletedStagesDeletion() throws Exception {
        bothDeleted();
        assertNull(ResolveCommands.resolve(List.of(files("a.txt", Conflict.BOTH_DELETED)), Take.DELETE));
        assertFalse(unmerged());
        assertTrue(git.git("status", "--porcelain").contains("D  a.txt"), "deletion staged");
    }

    @Test
    public void resolveConflictPutsMarkersBackAndStaysUnmerged() throws Exception {
        bothModified();
        ResolveCommands.resolve(List.of(files("a.txt", Conflict.BOTH_MODIFIED)), Take.OURS);
        assertNull(ResolveCommands.resolve(List.of(files("a.txt", Conflict.BOTH_MODIFIED)), Take.CONFLICT));
        final String text = git.read("a.txt");
        assertTrue(text.contains("<<<<<<<"), text);
        assertTrue(text.contains("|||||||"), "with a base: " + text);
        assertTrue(unmerged(), "still unmerged");
    }
}
