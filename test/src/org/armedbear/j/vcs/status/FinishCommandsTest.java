/*
 * FinishCommandsTest.java
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.VirtualText;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.vcs.Finish;
import org.armedbear.j.vcs.InProgress;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Continuing, skipping and aborting each operation git stops at a conflict, on real repositories. */
public class FinishCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private VcsStatusBuffer status;

    @AfterEach
    public void tearDown() {
        final List<Buffer> buffers = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            buffers.add(b);
        for (Buffer b : buffers)
            if (b instanceof VcsStatusBuffer) {
                b.dispose();
                Editor.getBufferList().remove(b);
            }
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        GitFixture.reset();
    }

    // f.txt "1"; topic makes it "T", main "M": they conflict. On main.
    private void diverged() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "1\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "topic");
        git.write("f.txt", "T\n");
        git.commitAll("t");
        git.git("checkout", "-q", "main");
        git.write("f.txt", "M\n");
        git.commitAll("m");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    // What is in progress, read fresh.
    private InProgress doing() {
        repo().invalidateFiles();
        repo().snapshotNow();
        return repo().inProgress();
    }

    private VcsResult finish(Finish how) {
        return FinishCommands.perform(repo(), doing(), how);
    }

    private void resolve() throws Exception {
        git.write("f.txt", "R\n");
        git.git("add", "f.txt");
    }

    private String subject() {
        return git.git("log", "-1", "--format=%s").strip();
    }

    private void stopped(InProgress doing, String... command) {
        assertFalse(git.run(command).succeeded(), "stops at the conflict");
        assertEquals(doing, doing());
    }

    // ---- rebase

    private void rebase() throws Exception {
        diverged();
        git.git("checkout", "-q", "topic");
        stopped(InProgress.REBASE, "rebase", "main");
    }

    @Test
    public void aRebaseContinues() throws Exception {
        rebase();
        resolve();
        assertTrue(finish(Finish.CONTINUE).succeeded());
        assertNull(doing());
        assertEquals("R\n", git.read("f.txt"));
        assertEquals("t", subject(), "its message kept");
    }

    @Test
    public void aRebaseSkips() throws Exception {
        rebase();
        assertTrue(finish(Finish.SKIP).succeeded());
        assertNull(doing());
        assertEquals("M\n", git.read("f.txt"));
    }

    @Test
    public void aRebaseAborts() throws Exception {
        rebase();
        assertTrue(finish(Finish.ABORT).succeeded());
        assertNull(doing());
        assertEquals("T\n", git.read("f.txt"));
    }

    @Test
    public void aContinueStoppingAgainKeepsTheBase() throws Exception {
        diverged();
        git.git("checkout", "-q", "topic");
        git.write("f.txt", "T2\n");
        git.commitAll("t2");
        stopped(InProgress.REBASE, "rebase", "main");
        resolve();
        assertFalse(finish(Finish.CONTINUE).succeeded(), "the next step conflicts too");
        assertEquals(InProgress.REBASE, doing());
        assertTrue(git.read("f.txt").contains("|||||||"), git.read("f.txt"));
    }

    // ---- cherry-pick, revert, am

    @Test
    public void aCherryPickContinuesSkipsAndAborts() throws Exception {
        diverged();
        stopped(InProgress.CHERRY_PICK, "cherry-pick", "topic");
        resolve();
        assertTrue(finish(Finish.CONTINUE).succeeded());
        assertNull(doing());
        assertEquals("t", subject());

        git.git("reset", "-q", "--hard", "HEAD~1");
        stopped(InProgress.CHERRY_PICK, "cherry-pick", "topic");
        assertTrue(finish(Finish.SKIP).succeeded());
        assertNull(doing());
        assertEquals("m", subject());

        stopped(InProgress.CHERRY_PICK, "cherry-pick", "topic");
        assertTrue(finish(Finish.ABORT).succeeded());
        assertNull(doing());
        assertEquals("M\n", git.read("f.txt"));
    }

    @Test
    public void aRevertContinuesAndAborts() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "1\n");
        git.commitAll("one");
        git.write("f.txt", "2\n");
        git.commitAll("two");
        git.write("f.txt", "3\n");
        git.commitAll("three");
        stopped(InProgress.REVERT, "revert", "--no-edit", "HEAD~1");
        assertTrue(finish(Finish.ABORT).succeeded());
        assertEquals("3\n", git.read("f.txt"));
        stopped(InProgress.REVERT, "revert", "--no-edit", "HEAD~1");
        resolve();
        assertTrue(finish(Finish.CONTINUE).succeeded());
        assertNull(doing());
        assertTrue(subject().startsWith("Revert"), subject());
    }

    @Test
    public void anAmContinuesAndAborts() throws Exception {
        diverged();
        git.write("t.patch", git.git("format-patch", "-1", "topic", "--stdout"));
        stopped(InProgress.PATCH_SERIES, "am", "-3", "t.patch");
        assertTrue(finish(Finish.ABORT).succeeded());
        assertEquals("M\n", git.read("f.txt"));
        stopped(InProgress.PATCH_SERIES, "am", "-3", "t.patch");
        resolve();
        assertTrue(finish(Finish.CONTINUE).succeeded());
        assertNull(doing());
        assertEquals("t", subject());
    }

    // ---- merge

    @Test
    public void aMergeCommitsOrAbortsAndHasNoStepToSkip() throws Exception {
        diverged();
        stopped(InProgress.MERGE, "merge", "topic");
        assertNull(finish(Finish.SKIP), "nothing to skip");
        assertTrue(finish(Finish.ABORT).succeeded());
        assertEquals("M\n", git.read("f.txt"));
        stopped(InProgress.MERGE, "merge", "topic");
        resolve();
        assertTrue(finish(Finish.CONTINUE).succeeded());
        assertNull(doing());
        assertTrue(subject().startsWith("Merge branch 'topic'"), subject());
        assertFalse(git.git("log", "-1", "--format=%B").contains("#"), "git's comments stripped");
    }

    // ---- in the status

    private void open() throws Exception {
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> !status.getFirstLine().getText().startsWith("Reading"));
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + h.status());
    }

    // The links after the status's line starting with prefix.
    private String links(String prefix) {
        for (Line l = status.getFirstLine(); l != null; l = l.next()) {
            final Line line = l;
            if (line.getText().startsWith(prefix))
                return status.getVirtualText()
                        .get(FinishCommands.OWNER)
                        .stream()
                        .filter(v -> v.line() == line)
                        .map(v -> VirtualText.text(v.rows().get(0)))
                        .findFirst()
                        .orElse("");
        }
        return null;
    }

    @Test
    public void continueIsRefusedWhileFilesAreUnmerged() throws Exception {
        rebase();
        open();
        SwingUtilities.invokeAndWait(() -> FinishCommands.vcsContinue(h.editor(), null));
        until(() -> h.status().equals("Not while f.txt is unmerged"));
        assertEquals(InProgress.REBASE, doing());
    }

    @Test
    public void continueFromTheStatusFinishesTheRebase() throws Exception {
        rebase();
        resolve();
        open();
        SwingUtilities.invokeAndWait(() -> FinishCommands.vcsContinue(h.editor(), null));
        until(() -> h.status().equals("Continued the rebase"));
        assertNull(doing());
    }

    @Test
    public void theStatusHeaderLinksWhatIsInProgress() throws Exception {
        rebase();
        open();
        until(() -> "   continue · skip · abort".equals(links("Rebase in progress")));
    }

    @Test
    public void aMergesLinksCommitOrAbort() throws Exception {
        diverged();
        stopped(InProgress.MERGE, "merge", "topic");
        open();
        until(() -> "   commit · abort".equals(links("Merge in progress")));
    }

    @Test
    public void skipAsksFirst() throws Exception {
        rebase();
        open();
        final List<String> asked = new ArrayList<>();
        final StatusActions.Confirm saved = StatusActions.confirm;
        StatusActions.confirm = (editor, text, title) -> {
            asked.add(title + ": " + text);
            return false;
        };
        try {
            SwingUtilities.invokeAndWait(() -> FinishCommands.vcsSkip(h.editor(), null));
            until(() -> !asked.isEmpty());
            assertEquals(
                List.of(
                    "Skip: Skip the rebase's step, dropping its changes and your resolutions and any other"
                            + " uncommitted change to a tracked file?"),
                asked);
            assertEquals(InProgress.REBASE, doing(), "not without a yes");
        }
        finally {
            StatusActions.confirm = saved;
        }
    }

    // A stash of f.txt "S" popped over a commit of f.txt since: it conflicts.
    private void poppedOverAConflict() throws Exception {
        diverged();
        git.write("f.txt", "S\n");
        git.git("stash", "-q");
        final String id = git.git("rev-parse", "stash@{0}").strip();
        git.write("f.txt", "M2\n");
        git.commitAll("m2");
        assertFalse(
            StashCommands.applyStash(repo(), new StatusData.StashItem("stash@{0}", id, "", List.of()), true)
                    .succeeded());
    }

    @Test
    public void abortingAStashPopForgetsItKeepingTheStashAndTheFiles() throws Exception {
        poppedOverAConflict();
        final String before = git.read("f.txt");
        open();
        until(() -> "   continue \u00b7 forget".equals(links("Stash pop in progress")));
        final List<String> asked = new ArrayList<>();
        final StatusActions.Confirm saved = StatusActions.confirm;
        StatusActions.confirm = (editor, text, title) -> {
            asked.add(title + ": " + text);
            return true;
        };
        try {
            SwingUtilities.invokeAndWait(() -> FinishCommands.vcsAbort(h.editor(), null));
            until(() -> h.status().equals("Forgot the stash pop"));
            assertEquals(
                List.of(
                    "Forget: Forget the stash pop? Its stash is kept, and nothing in the working tree is touched:"
                            + " discard what it wrote in the status."),
                asked);
            assertNull(repo().stashConflict());
            assertFalse(git.run("stash", "list").out().isEmpty(), "the stash kept");
            assertEquals(before, git.read("f.txt"), "the file untouched");
        }
        finally {
            StatusActions.confirm = saved;
        }
    }

    @Test
    public void continuingAPopAsksBeforeDroppingItsStash() throws Exception {
        // Its changes may have been discarded since: the stash is their last.
        poppedOverAConflict();
        git.write("f.txt", "R\n");
        git.git("add", "f.txt");
        open();
        final List<String> asked = new ArrayList<>();
        final StatusActions.Confirm saved = StatusActions.confirm;
        StatusActions.confirm = (editor, text, title) -> {
            asked.add(title + ": " + text);
            return false;
        };
        try {
            SwingUtilities.invokeAndWait(() -> FinishCommands.vcsContinue(h.editor(), null));
            until(() -> !asked.isEmpty());
            assertEquals(
                List.of(
                    "Drop Stash: Continue the stash pop, dropping its stash? Its changes stay in the working tree."),
                asked);
            assertFalse(git.run("stash", "list").out().isEmpty(), "not without a yes");
            StatusActions.confirm = (editor, text, title) -> true;
            SwingUtilities.invokeAndWait(() -> FinishCommands.vcsContinue(h.editor(), null));
            until(() -> git.run("stash", "list").out().isEmpty());
            until(() -> h.status().startsWith("Continued the stash pop, dropping its stash "));
            assertTrue(h.status().contains("git stash apply "), h.status());
        }
        finally {
            StatusActions.confirm = saved;
        }
    }

    @Test
    public void theStatusLineSaysWhatIsInProgressInItsOwnWords() throws Exception {
        diverged();
        git.write("t.patch", git.git("format-patch", "-1", "topic", "--stdout"));
        stopped(InProgress.PATCH_SERIES, "am", "-3", "t.patch");
        open();
        until(() -> links("Patch series in progress") != null);
    }

    @Test
    public void fromAFileItActsInTheFilesOwnRepository() throws Exception {
        // A project of two repositories, a and b: a file of b's is b's.
        final Path ws = tmp.resolve("ws");
        java.nio.file.Files.createDirectories(ws.resolve(".j-project"));
        GitFixture.init(java.nio.file.Files.createDirectories(ws.resolve("a")));
        final GitFixture b = GitFixture.init(java.nio.file.Files.createDirectories(ws.resolve("b")));
        b.write("y.txt", "y\n");
        b.commitAll("y");
        final VcsRepository[] at = new VcsRepository[1];
        SwingUtilities.invokeAndWait(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            final Buffer y = Editor.getBuffer(org.armedbear.j.File.getInstance(ws.resolve("b/y.txt").toString()));
            if (!y.initialized())
                y.initialize();
            y.load();
            h.showBuffer(y);
            at[0] = BranchCommands.repository(h.editor());
        });
        assertEquals(Repositories.forDirectory(ws.resolve("b")), at[0]);
    }

    @Test
    public void thePopupFinishesWhatIsInProgress() throws Exception {
        diverged();
        final BranchCommands.Context c = new BranchCommands.Context(repo(), null, "main", List.of());
        final Transient rebase = MergeCommands.popup(c, InProgress.REBASE);
        assertEquals("vcsContinue", ((Command) rebase.item("c")).name());
        assertEquals("vcsSkip", ((Command) rebase.item("s")).name());
        assertEquals("vcsAbort", ((Command) rebase.item("a")).name());
        final Transient pop = MergeCommands.popup(c, InProgress.STASH_POP);
        assertNull(pop.item("s"), "a pop has no step to skip");
        assertEquals("forget", ((Command) pop.item("a")).description(), "a stash's abort forgets it");
        assertEquals("vcsContinue", ((Command) pop.item("c")).name());
    }
}
