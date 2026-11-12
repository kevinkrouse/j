/*
 * SvnCommitTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.status;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.svn.SvnFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whole-file commits and discards in an svn working copy, through the status. */
public class SvnCommitTest {
    @TempDir
    Path tmp;
    private SvnFixture svn;
    private EditorHarness h;
    private VcsStatusBuffer status;

    @BeforeEach
    public void setUp() {
        CommitCommands.show = (editor, buffer) -> {
            h.showBuffer(buffer);
            return h.editor();
        };
    }

    @AfterEach
    public void tearDown() throws Exception {
        CommitCommands.show = (editor, buffer) -> editor.activateInOtherWindow(buffer);
        final List<Buffer> buffers = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            buffers.add(b);
        SwingUtilities.invokeAndWait(() -> {
            for (Buffer b : buffers)
                if (b instanceof VcsStatusBuffer || b instanceof CommitMessageBuffer) {
                    b.dispose();
                    Editor.getBufferList().remove(b);
                }
        });
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        SwingUtilities.invokeAndWait(RepositoryStatus::closeAll);
        SvnFixture.reset();
    }

    // a.txt, b.txt and c.txt committed, then each changed; the status open.
    private void open() throws Exception {
        svn = SvnFixture.init(tmp);
        for (String p : List.of("a.txt", "b.txt", "c.txt"))
            svn.write(p, "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n");
        svn.add("a.txt", "b.txt", "c.txt");
        svn.commit("first");
        for (String p : List.of("a.txt", "b.txt", "c.txt"))
            svn.write(p, "ONE\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\nTWELVE\n");
        showStatus();
    }

    // The status shown, once it lists path.
    private void showStatusOf(String path) throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
        });
        until(
            () -> status.model().files(repo(), StatusData.Role.WORKING).stream().anyMatch(f -> f.path().equals(path)));
    }

    private void showStatus() throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> text().stream().anyMatch(t -> t.startsWith("Changes (")));
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(svn.root);
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 400; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + text() + " / " + h.status());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private List<String> text() {
        final List<String> list = new ArrayList<>();
        if (status != null)
            for (Line l = status.getFirstLine(); l != null; l = l.next())
                list.add(l.getText());
        return list;
    }

    private CommitMessageBuffer message() {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CommitMessageBuffer m)
                return m;
        return null;
    }

    private FileItem file(String path) {
        for (FileItem f : status.model().files(repo(), StatusData.Role.WORKING))
            if (f.path().equals(path))
                return f;
        throw new AssertionError("no " + path);
    }

    private Target.Files whole(String... paths) {
        final List<FileItem> files = new ArrayList<>();
        for (String p : paths)
            files.add(file(p));
        return new Target.Files(repo(), "working", files);
    }

    // The message replaced with subject, and committed; true when its buffer closes.
    private boolean finish(String subject) throws Exception {
        until(() -> message() != null);
        final CommitMessageBuffer m = message();
        onEdt(() -> {
            final String text = m.getText();
            final int at = text.indexOf("# ");
            CommitCommands.replace(h.editor(), m, subject + "\n\n" + (at < 0 ? "" : text.substring(at)));
            CommitCommands.vcsCommitFinish(h.editor());
        });
        until(() -> !Editor.getBufferList().contains(m) || !m.isCommitting() && !h.status().startsWith("Checking"));
        return !Editor.getBufferList().contains(m);
    }

    // The paths changed by the last commit, as svn log names them.
    private String lastCommit() {
        svn.update();
        return svn.svn("log", "-v", "-l", "1", "-q");
    }

    @Test
    public void commitsTwoOfThreeFilesWhole() throws Exception {
        open();
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(whole("a.txt", "b.txt"))));
        assertTrue(finish("two of three"), h.status());
        until(() -> h.status().startsWith("Committed revision 2"));
        final String log = lastCommit();
        assertTrue(log.contains("/a.txt") && log.contains("/b.txt"), log);
        assertFalse(log.contains("/c.txt"), log);
        assertTrue(svn.svn("status", "-q").contains("c.txt"));
        assertEquals("two of three", svn.svn("log", "-l", "1", "--xml").replaceAll("(?s).*<msg>(.*)</msg>.*", "$1"));
    }

    @Test
    public void anEditBetweenReviewAndCommitIsRefused() throws Exception {
        open();
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(whole("a.txt"))));
        until(() -> message() != null);
        svn.write("a.txt", "edited after the review\n");
        assertFalse(finish("refused"), "the message stays");
        assertTrue(h.status().contains("changed since it was shown"), h.status());
        assertEquals(1, svn.revision());
    }

    @Test
    public void theBackendRefusesAVersionNotReviewed() throws Exception {
        open();
        final VcsRepository r = repo();
        final VcsBackend.CommitPreview preview = r.backend().previewPaths(r, List.of("a.txt"));
        assertNull(preview.failure());
        svn.write("a.txt", "edited\n");
        r.invalidate();
        final VcsResult result = r.backend().commitPaths(r, List.of("a.txt"), preview.versions(), "m");
        assertFalse(result.succeeded());
        assertTrue(result.stderr().contains("changed since it was shown"), result.stderr());
    }

    @Test
    public void anOutOfDateWorkingCopyIsToldToUpdate() throws Exception {
        open();
        final SvnFixture other = svn.second();
        other.write("a.txt", "theirs\n");
        other.commit("theirs");
        final VcsRepository r = repo();
        r.invalidate();
        final VcsResult result = r.backend().commitPaths(r, List.of("a.txt"), null, "ours");
        assertFalse(result.succeeded());
        assertTrue(result.stderr().startsWith("Update the working copy first: svn update"), result.stderr());
    }

    @Test
    public void anAddedDirectoryIsDiscarded() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("e/x.txt", "x\n");
        svn.add("e");
        final VcsRepository r = Repositories.forDirectory(svn.root);
        r.invalidate();
        r.snapshotNow();
        final VcsResult result = r.backend().discard(r, List.of("e", "e/x.txt"));
        assertTrue(result.succeeded(), result.stderr());
        assertTrue(svn.svn("status").contains("?       e"), svn.svn("status"));
    }

    @Test
    public void anAddedDirectoryGoneToTheTrashIsReverted() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("e/x.txt", "x\n");
        svn.add("e");
        java.nio.file.Files.delete(svn.root.resolve("e/x.txt"));
        java.nio.file.Files.delete(svn.root.resolve("e"));
        final VcsRepository r = Repositories.forDirectory(svn.root);
        final VcsResult result = r.backend().discard(r, List.of("e"));
        assertTrue(result.succeeded(), result.stderr());
        assertEquals("", svn.svn("status").strip());
    }

    @Test
    public void aMissingFileIsCommittedAsDeleted() throws Exception {
        open();
        java.nio.file.Files.delete(svn.root.resolve("c.txt"));
        final VcsRepository r = repo();
        r.invalidate();
        r.snapshotNow();
        final VcsResult result = r.backend().commitPaths(r, List.of("c.txt"), null, "gone");
        assertTrue(result.succeeded(), result.stderr());
        assertTrue(lastCommit().contains("D /c.txt"), lastCommit());
    }

    @Test
    public void aFailedCommitLeavesAMissingFileMissing() throws Exception {
        open();
        final SvnFixture other = svn.second();
        other.write("a.txt", "theirs\n");
        other.commit("theirs");
        java.nio.file.Files.delete(svn.root.resolve("c.txt"));
        final VcsRepository r = repo();
        r.invalidate();
        r.snapshotNow();
        final VcsResult result = r.backend().commitPaths(r, List.of("a.txt", "c.txt"), null, "refused");
        assertFalse(result.succeeded());
        assertTrue(svn.svn("status").contains("!       c.txt"), svn.svn("status"));
        assertFalse(java.nio.file.Files.exists(svn.root.resolve("c.txt")));
    }

    @Test
    public void anAddedFileGoneFromDiskIsRefused() throws Exception {
        open();
        svn.write("new.txt", "n\n");
        svn.add("new.txt");
        java.nio.file.Files.delete(svn.root.resolve("new.txt"));
        final VcsRepository r = repo();
        r.invalidate();
        r.snapshotNow();
        final VcsResult result = r.backend().commitPaths(r, List.of("a.txt", "new.txt"), null, "m");
        assertFalse(result.succeeded());
        assertTrue(result.stderr().contains("was added, then deleted"), result.stderr());
        assertEquals(1, svn.revision());
    }

    @Test
    public void discardingACopyOrADeletedFilesHunk() throws Exception {
        open();
        svn.svn("copy", "-q", "--", "a.txt", "copy.txt");
        svn.svn("revert", "-q", "--", "c.txt");
        svn.svn("rm", "-q", "--", "c.txt");
        onEdt(() -> status.model().refresh());
        until(() -> status.model().files(repo(), StatusData.Role.WORKING).size() == 4);
        status.model()
                .readDiffsThen(
                    java.util.Set.of(new org.armedbear.j.vcs.selection.FileKey(repo(), "working", "c.txt")),
                    () -> {});
        until(() -> file("c.txt").diff() != null);
        final String[] said = new String[2];
        onEdt(() -> {
            said[0] = StatusActions.discard(List.of(whole("copy.txt")));
            final FileItem c = file("c.txt");
            said[1] = StatusActions
                    .discard(List.of(new Target.Part(repo(), "working", c, c.diff().hunks().get(0), null)));
        });
        assertNull(said[0]);
        assertNull(said[1]);
        final String st = svn.svn("status");
        assertFalse(st.contains("copy.txt"), "the copy unversioned, its file trashed: " + st);
        assertFalse(java.nio.file.Files.exists(svn.root.resolve("copy.txt")));
        assertFalse(st.contains("c.txt"), "the deleted file reverted, not added anew: " + st);
        assertEquals("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n", svn.read("c.txt"));
    }

    @Test
    public void aPathEndingInASpaceIsCommittedItself() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("x", "x\n");
        svn.write("x ", "x space\n");
        svn.add("x", "x ");
        svn.commit("first");
        svn.write("x", "x2\n");
        svn.write("x ", "x space2\n");
        final VcsRepository r = Repositories.forDirectory(svn.root);
        r.invalidate();
        final VcsResult result = r.backend().commitPaths(r, List.of("x "), null, "the spaced one");
        assertTrue(result.succeeded(), result.stderr());
        final String st = svn.svn("status");
        assertTrue(st.contains("M       x\n") || st.trim().endsWith("M       x"), st);
        assertFalse(st.contains("x \n"), st);
    }

    @Test
    public void aHunkIsRefused() throws Exception {
        open();
        until(() -> status.model().getData(repo()) != null);
        status.model()
                .readDiffsThen(
                    java.util.Set.of(new org.armedbear.j.vcs.selection.FileKey(repo(), "working", "a.txt")),
                    () -> {});
        until(() -> file("a.txt").diff() != null);
        onEdt(() -> {
            final FileItem a = file("a.txt");
            final Target.Part hunk = new Target.Part(repo(), "working", a, a.diff().hunks().get(0), null);
            final var refused = assertThrows(
                PartialCommit.Refused.class,
                () -> PartialCommit.ofTargets(status.model(), List.of(hunk)).plan());
            assertEquals("svn commits whole files: select the file", refused.getMessage());
        });
    }

    @Test
    public void discardsAFileAndAHunk() throws Exception {
        open();
        status.model()
                .readDiffsThen(
                    java.util.Set.of(new org.armedbear.j.vcs.selection.FileKey(repo(), "working", "b.txt")),
                    () -> {});
        until(() -> file("b.txt").diff() != null && file("b.txt").diff().hunks().size() == 2);
        final String[] said = new String[2];
        onEdt(() -> {
            said[0] = StatusActions.discard(List.of(whole("a.txt")));
            final FileItem b = file("b.txt");
            said[1] = StatusActions
                    .discard(List.of(new Target.Part(repo(), "working", b, b.diff().hunks().get(0), null)));
        });
        assertNull(said[0]);
        assertNull(said[1]);
        assertEquals("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n", svn.read("a.txt"));
        assertEquals("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\nTWELVE\n", svn.read("b.txt"));
    }

    @Test
    public void commitCommitsEveryWorkingChange() throws Exception {
        open();
        svn.write("untracked.txt", "u\n");
        onEdt(() -> CommitCommands.vcsCommit(h.editor()));
        until(() -> message() != null);
        assertTrue(message().getText().contains("M  a.txt (whole file)"), message().getText());
        assertTrue(finish("all of it"), h.status());
        final String log = lastCommit();
        for (String p : List.of("a.txt", "b.txt", "c.txt"))
            assertTrue(log.contains("/" + p), log);
        assertTrue(svn.svn("status").contains("untracked.txt"), "untracked left");
    }

    @Test
    public void discardingAnAddedDirectoryThroughTheStatus() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("e/x.txt", "x\n");
        svn.add("e");
        showStatusOf("e");
        final String[] said = new String[1];
        onEdt(() -> said[0] = StatusActions.discard(List.of(whole("e", "e/x.txt"))));
        assertNull(said[0]);
        assertFalse(java.nio.file.Files.exists(svn.root.resolve("e")));
        assertEquals("", svn.svn("status").strip());
    }

    @Test
    public void commitRefusesAFileChangedSinceItWasShown() throws Exception {
        open();
        onEdt(() -> CommitCommands.vcsCommit(h.editor()));
        until(() -> message() != null && message().getPartial() != null);
        svn.write("a.txt", "edited after it was shown\n");
        assertFalse(finish("refused"), "the message stays");
        assertTrue(h.status().contains("changed since it was shown"), h.status());
        assertEquals(1, svn.revision());
    }

    @Test
    public void aFileInAnAddedDirectoryIsCommittedAlone() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("new/dir/a.txt", "a\n");
        svn.write("new/dir/b.txt", "b\n");
        svn.add("new");
        showStatus();
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(whole("new/dir/a.txt"))));
        assertTrue(finish("one file"), h.status());
        final String log = lastCommit();
        assertTrue(log.contains("/new/dir/a.txt"), log);
        assertFalse(log.contains("b.txt"), log);
        assertTrue(svn.svn("status").contains("new/dir/b.txt"));
    }
}
