/*
 * PartialCommitTest.java
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.ChangelistStore;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PartialCommitTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private VcsStatusBuffer status;

    private static final String SIXTY;

    static {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++)
            sb.append("line ").append(i).append('\n');
        SIXTY = sb.toString();
    }

    private static final String CHANGED =
            SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n");

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
        GitFixture.reset();
    }

    // a.txt of sixty lines with lines 3 and 50 changed; b.txt's change
    // staged; c.txt new; and the status open.
    private void open() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", SIXTY);
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", CHANGED);
        git.write("b.txt", "b changed\n");
        git.git("add", "b.txt");
        git.write("c.txt", "new\n");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> text().contains("  M  a.txt"));
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + text());
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

    private StatusLine line(String text) {
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l.getText().equals(text))
                return (StatusLine) l;
        throw new AssertionError("no line " + text + " in " + text());
    }

    private CommitMessageBuffer message() {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CommitMessageBuffer m)
                return m;
        return null;
    }

    // Lists Default and Feature, line 50's hunk in Feature.
    private void fiftyInFeature() throws Exception {
        onEdt(() -> {
            assertTrue(RepositoryLists.of(repo()).update(c -> c.withList("Feature")));
            SignsUpdater.listsChanged(repo());
        });
        until(() -> text().contains("  Feature (0)"));
        // Its diff read, the file opened.
        onEdt(() -> status.toggle(line("    M  a.txt")));
        until(() -> status.files(repo(), StatusRenderer.UNSTAGED).get(0).diff() != null);
        onEdt(() -> {
            final StatusData.FileItem a = status.files(repo(), StatusRenderer.UNSTAGED).get(0);
            final Map<String, BitSet> only = new HashMap<>();
            for (Map.Entry<String, Hunk> e : status.picks().ids(a.diff()).entrySet())
                if (e.getValue().lines().stream().anyMatch(l -> l.text().equals("line fifty"))) {
                    final BitSet bits = new BitSet();
                    bits.set(0, e.getValue().lines().size());
                    only.put(e.getKey(), bits);
                }
            ChangelistCommands.moveFile(status.model(), repo(), "a.txt", only, "Feature", s -> said.add(s));
        });
        until(() -> !said.isEmpty() && (text().contains("  Feature (1)") || !said.get(0).startsWith("Moved")));
        assertTrue(said.get(0).startsWith("Moved"), said.toString());
        until(
            () -> text().contains("  Feature (1)")
                    && text().stream().anyMatch(t -> t.startsWith("  Default (") && !t.startsWith("  Default (0)")));
    }

    private final List<String> said = new ArrayList<>();

    // The message replaced with subject, and committed.
    private void finish(String subject) throws Exception {
        until(() -> message() != null && message().getPartial() != null);
        final CommitMessageBuffer m = message();
        onEdt(() -> {
            CommitCommands.replace(h.editor(), m, subject + "\n\n" + m.getText().substring(m.getText().indexOf("# ")));
            CommitCommands.vcsCommitFinish(h.editor());
        });
        until(() -> !Editor.getBufferList().contains(m) || !m.isCommitting() && m.getText() != null);
    }

    private String subject() {
        return git.git("log", "-1", "--format=%s").strip();
    }

    @Test
    public void aListsHunkIsCommittedAloneWhatIsStagedStayingStaged() throws Exception {
        open();
        fiftyInFeature();
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        until(() -> message() != null);
        assertTrue(message().getText().contains("# Commits changelist Feature alone"), message().getText());
        assertTrue(message().getText().contains("#   M  a.txt"));
        finish("Fifty");
        until(() -> "Fifty".equals(subject()));
        assertTrue(git.head("a.txt").contains("line fifty\n"), "the list's hunk");
        assertTrue(git.head("a.txt").contains("line 3\n"), "not Default's");
        assertEquals("b\n", git.head("b.txt"), "what was staged not committed");
        assertEquals("b changed\n", git.index("b.txt"), "and still staged");
        assertEquals(git.head("a.txt"), git.index("a.txt"), "the index agrees with the new HEAD");
        assertEquals(CHANGED, git.read("a.txt"), "the work tree as it was");
        // Feature, empty now, gone.
        until(() -> RepositoryLists.of(repo()).lists().list("Feature") == null);
        assertNull(ChangelistStore.of(repo()).read().list("Feature"));
    }

    @Test
    public void theDefaultListsWholeFilesAreCommittedItsDraftTheMessageAndItStays() throws Exception {
        open();
        onEdt(() -> {
            assertTrue(
                RepositoryLists.of(repo())
                        .update(c -> c.withList("Feature").withComment(Changelists.DEFAULT, "From the draft")));
            SignsUpdater.listsChanged(repo());
        });
        until(() -> text().contains("  Default (1)  active"));
        onEdt(() -> {
            h.editor().setDot(line("  Default (1)  active"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        until(() -> message() != null);
        assertTrue(message().getText().startsWith("From the draft"), message().getText());
        final CommitMessageBuffer m = message();
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertEquals("From the draft", subject());
        assertEquals(CHANGED, git.head("a.txt"));
        assertEquals("b\n", git.head("b.txt"));
        final Changelists.Changelist kept = RepositoryLists.of(repo()).lists().list(Changelists.DEFAULT);
        assertNotNull(kept, "the default stays");
        assertEquals("", kept.comment(), "its draft used");
    }

    @Test
    public void thePickedChangesAndAnUntrackedFileAreCommittedAlone() throws Exception {
        open();
        until(() -> text().contains("  ?  c.txt"));
        onEdt(() -> status.pick(List.of(line("  ?  c.txt"))));
        // a.txt's line 3 hunk picked, from its open diff.
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> text().stream().anyMatch(t -> t.startsWith("  @@ -1,")));
        onEdt(() -> {
            for (Line l = status.getFirstLine(); l != null; l = l.next())
                if (l.getText().startsWith("  @@ -1,")) {
                    status.pick(List.of((StatusLine) l));
                    break;
                }
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        assertTrue(message().getText().contains("# Commits the picked changes alone"), message().getText());
        finish("Picked");
        until(() -> "Picked".equals(subject()));
        assertEquals("new\n", git.head("c.txt"));
        assertTrue(git.head("a.txt").contains("line three\n"));
        assertTrue(git.head("a.txt").contains("line 50\n"), "line 50 not picked");
        assertEquals("b\n", git.head("b.txt"));
        assertEquals("b changed\n", git.index("b.txt"));
        until(() -> status.picks().isEmpty());
    }

    @Test
    public void aHookThatRejectsLeavesTheIndexAndTheMessage() throws Exception {
        open();
        fiftyInFeature();
        Files.writeString(git.root.resolve(".git/hooks/pre-commit"), "#!/bin/sh\necho the hook says no >&2\nexit 1\n");
        git.root.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        finish("Rejected");
        until(() -> String.valueOf(h.status()).contains("the hook says no"));
        assertEquals("first", subject());
        assertTrue(Editor.getBufferList().contains(message()), "the message kept");
        assertEquals(SIXTY, git.index("a.txt"), "the index as it was");
        assertEquals("b changed\n", git.index("b.txt"));
        assertNotNull(RepositoryLists.of(repo()).lists().list("Feature"), "the list kept");
        try (var files = Files.list(git.root.resolve(".git/j"))) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("commit-index")), "no index left");
        }
    }

    @Test
    public void aHookThatChangesTheWorkTreeLeavesTheIndexAsCommitted() throws Exception {
        open();
        fiftyInFeature();
        // The hook changes line 50 in the work tree as it commits: the
        // index takes what was committed, not the file now.
        Files.writeString(
            git.root.resolve(".git/hooks/pre-commit"),
            "#!/bin/sh\nsed -i 's/line fifty/line FIFTY/' a.txt\n");
        git.root.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        finish("Fifty");
        until(() -> "Fifty".equals(subject()));
        assertTrue(git.head("a.txt").contains("line fifty\n"));
        assertEquals(git.head("a.txt"), git.index("a.txt"), "the index as committed");
        assertEquals("b changed\n", git.index("b.txt"), "what was staged still staged");
    }

    @Test
    public void aFileWithSomethingStagedIsRefused() throws Exception {
        open();
        fiftyInFeature();
        // Line 3's hunk staged: a.txt is in Feature and has a staged change.
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n"));
        git.git("add", "a.txt");
        git.write("a.txt", CHANGED);
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("  M  a.txt") && text().contains("  Feature (1)"));
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
            assertNull(message(), "no message opened");
        });
        assertTrue(String.valueOf(h.status()).contains("a.txt has staged changes"), h.status());
    }

    @Test
    public void aMergeInProgressIsRefused() throws Exception {
        open();
        fiftyInFeature();
        // A merge with nothing to conflict, stopped before it commits.
        git.git("stash");
        git.git("checkout", "-q", "-b", "side");
        git.write("d.txt", "d\n");
        git.commitAll("side");
        git.git("checkout", "-q", "main");
        git.git("merge", "--no-commit", "--no-ff", "side");
        git.git("stash", "pop");
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> repo().inProgress() != null && text().contains("  Feature (1)"));
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
            assertNull(message(), "no message opened");
        });
        assertTrue(String.valueOf(h.status()).contains("merge is in progress"), h.status());
    }

    @Test
    public void ctrlPKeepsWhatItCommitsInTheMessage() throws Exception {
        open();
        fiftyInFeature();
        onEdt(() -> {
            org.armedbear.j.CommentRing.getInstance().appendNew("An older message");
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        until(() -> message() != null);
        onEdt(() -> CommitCommands.vcsCommitPreviousMessage(h.editor()));
        assertTrue(message().getText().startsWith("An older message"), message().getText());
        assertTrue(message().getText().contains("# Commits changelist Feature alone"), message().getText());
    }

    @Test
    public void aListGivenUpKeepsTheMessageAsItsDraft() throws Exception {
        open();
        fiftyInFeature();
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        until(() -> message() != null);
        final CommitMessageBuffer m = message();
        onEdt(() -> {
            CommitCommands.replace(
                h.editor(),
                m,
                "Half written\n#123 of its own\n\n" + m.getText().substring(m.getText().indexOf("# Lines")));
            // Closed any way at all, as killed.
            h.showBuffer(h.buffer());
            m.kill();
        });
        assertEquals("Half written\n#123 of its own", RepositoryLists.of(repo()).lists().list("Feature").comment());
    }

    @Test
    public void aListsLinesOfAHunkSplitAcrossListsAreCommittedAlone() throws Exception {
        open();
        // Lines 3 and 6, one hunk.
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 6\n", "line six\n"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
            assertTrue(RepositoryLists.of(repo()).update(c -> c.withList("Feature")));
            SignsUpdater.listsChanged(repo());
        });
        until(() -> text().contains("  Feature (0)"));
        onEdt(() -> status.toggle(line("    M  a.txt")));
        until(() -> {
            final StatusData.FileItem a = status.files(repo(), StatusRenderer.UNSTAGED).get(0);
            return a.diff() != null && a.diff().hunks().size() == 1;
        });
        onEdt(() -> {
            final StatusData.FileItem a = status.files(repo(), StatusRenderer.UNSTAGED).get(0);
            final Map.Entry<String, Hunk> e = status.picks().ids(a.diff()).entrySet().iterator().next();
            // Line 6's removal and addition alone.
            final BitSet bits = new BitSet();
            final List<org.armedbear.j.vcs.diff.DiffLine> lines = e.getValue().lines();
            for (int k = 0; k < lines.size(); k++)
                if (lines.get(k).text().equals("line 6") && lines.get(k).isRemoved()
                        || lines.get(k).text().equals("line six"))
                    bits.set(k);
            ChangelistCommands
                    .moveFile(status.model(), repo(), "a.txt", Map.of(e.getKey(), bits), "Feature", said::add);
        });
        until(() -> text().contains("  Feature (1)") && text().contains("  Default (1)  active"));
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        finish("Six");
        until(() -> "Six".equals(subject()));
        assertTrue(git.head("a.txt").contains("line six\n"), "Feature's lines");
        assertTrue(git.head("a.txt").contains("line 3\n"), "not Default's, in the same hunk");
        assertEquals(git.head("a.txt"), git.index("a.txt"));
    }

    @Test
    public void twoHunksOfAFileAroundAnotherListsAreCommittedAndTheIndexAgrees() throws Exception {
        open();
        git.write("a.txt", CHANGED.replace("line 30\n", "line thirty\nline thirty-one\n"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
            assertTrue(RepositoryLists.of(repo()).update(c -> c.withList("Feature")));
            SignsUpdater.listsChanged(repo());
        });
        until(() -> text().contains("  Feature (0)"));
        onEdt(() -> status.toggle(line("    M  a.txt")));
        until(() -> {
            final StatusData.FileItem a = status.files(repo(), StatusRenderer.UNSTAGED).get(0);
            return a.diff() != null && a.diff().hunks().size() == 3;
        });
        // Lines 3's and 50's hunks to Feature, line 30's left in Default.
        onEdt(() -> {
            final StatusData.FileItem a = status.files(repo(), StatusRenderer.UNSTAGED).get(0);
            final Map<String, BitSet> only = new HashMap<>();
            for (Map.Entry<String, Hunk> e : status.picks().ids(a.diff()).entrySet())
                if (e.getValue().lines().stream().noneMatch(l -> l.text().startsWith("line thirty"))) {
                    final BitSet bits = new BitSet();
                    bits.set(0, e.getValue().lines().size());
                    only.put(e.getKey(), bits);
                }
            ChangelistCommands.moveFile(status.model(), repo(), "a.txt", only, "Feature", said::add);
        });
        until(() -> text().contains("  Feature (1)") && text().contains("  Default (1)  active"));
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        finish("Ends");
        until(() -> "Ends".equals(subject()));
        final String head = git.head("a.txt");
        assertTrue(head.contains("line three\n") && head.contains("line fifty\n"), head);
        assertTrue(head.contains("line 30\n"), "Default's hunk not committed");
        assertEquals(head, git.index("a.txt"), "the index as committed, both hunks");
    }

    @Test
    public void whatIsCommittedIsWorkedOutAgainAtCtrlEnter() throws Exception {
        open();
        fiftyInFeature();
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        until(() -> message() != null);
        // Staged in a terminal as the message is written, the status not
        // read again: git's index, read as it commits, refuses.
        git.git("add", "a.txt");
        finish("Too late");
        until(() -> String.valueOf(h.status()).contains("a.txt has staged changes"));
        assertEquals("first", subject());
        assertEquals(CHANGED, git.index("a.txt"), "what was staged still staged");
    }

    @Test
    public void somethingPickedAsTheMessageIsWrittenIsShownBeforeItIsCommitted() throws Exception {
        open();
        until(() -> text().contains("  ?  c.txt"));
        onEdt(() -> {
            status.pick(List.of(line("  ?  c.txt")));
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        assertFalse(message().getText().contains("a.txt"), message().getText());
        onEdt(() -> status.pick(List.of(line("  M  a.txt"))));
        final CommitMessageBuffer m = message();
        onEdt(() -> {
            CommitCommands.replace(h.editor(), m, "Both\n\n" + m.getText().substring(m.getText().indexOf("# ")));
            CommitCommands.vcsCommitFinish(h.editor());
        });
        // Not committed, shown again with it.
        until(() -> !m.isCommitting());
        assertEquals("first", subject());
        assertTrue(m.getText().contains("#   M  a.txt"), m.getText());
        assertTrue(String.valueOf(h.status()).contains("changed since it was shown"), h.status());
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> "Both".equals(subject()));
        assertEquals(CHANGED, git.head("a.txt"));
        assertEquals("new\n", git.head("c.txt"));
    }

    private StatusData.FileItem readA() throws Exception {
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> status.files(repo(), StatusRenderer.UNSTAGED).get(0).diff() != null);
        return status.files(repo(), StatusRenderer.UNSTAGED).get(0);
    }

    private Target.Part part(StatusData.FileItem file, int hunk) {
        return Target.Part.of(repo(), StatusRenderer.UNSTAGED, file, file.diff().hunks().get(hunk), null);
    }

    @Test
    public void addingAndRemovingAPickedHunkInOneFileEachRequireConfirmation() throws Exception {
        open();
        final var file = readA();
        onEdt(() -> {
            status.picks().set(List.of(part(file, 0)), true);
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        assertTrue(message().getText().contains("1 hunk, 2 changed lines"));
        onEdt(() -> status.picks().set(List.of(part(file, 1)), true));
        finish("One file");
        assertEquals("first", subject());
        assertTrue(message().getText().contains("2 hunks, 4 changed lines"), message().getText());
        onEdt(() -> status.picks().set(List.of(part(file, 1)), false));
        finish("One file");
        assertEquals("first", subject());
        assertTrue(message().getText().contains("1 hunk, 2 changed lines"));
        finish("One file");
        assertEquals("One file", subject());
        assertEquals(SIXTY.replace("line 3\n", "line three\n"), git.head("a.txt"));
        assertEquals("b changed\n", git.index("b.txt"));
    }

    @Test
    public void differentPickedLinesWithTheSameHunkAndLineCountsRequireConfirmation() throws Exception {
        open();
        git.write("a.txt", CHANGED.replace("line 4\n", "line four\n"));
        final var file = readA();
        final Hunk hunk = file.diff().hunks().get(0);
        final BitSet three = new BitSet(), four = new BitSet();
        for (int i = 0; i < hunk.lines().size(); i++) {
            if (hunk.lines().get(i).text().equals("line three"))
                three.set(i);
            if (hunk.lines().get(i).text().equals("line four"))
                four.set(i);
        }
        assertFalse(three.isEmpty());
        assertFalse(four.isEmpty());
        final var first = Target.Part.of(repo(), StatusRenderer.UNSTAGED, file, hunk, three);
        final var second = Target.Part.of(repo(), StatusRenderer.UNSTAGED, file, hunk, four);
        onEdt(() -> {
            status.picks().set(List.of(first), true);
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        onEdt(() -> {
            status.picks().clear();
            status.picks().set(List.of(second), true);
        });
        finish("Four");
        assertEquals("first", subject());
        assertTrue(String.valueOf(h.status()).contains("changed since it was shown"), h.status());
        finish("Four");
        assertEquals(SIXTY.replace("line 4\n", "line four\n"), git.head("a.txt"));
        assertEquals("b changed\n", git.index("b.txt"));
    }

    @Test
    public void addingAHunkToTheSameChangelistFileRequiresConfirmation() throws Exception {
        open();
        fiftyInFeature();
        onEdt(() -> CommitCommands.startPartialList(h.editor(), status.model(), repo(), "Feature"));
        until(() -> message() != null);
        final var file = status.files(repo(), StatusRenderer.UNSTAGED).get(0);
        final Map<String, BitSet> first = new HashMap<>();
        status.picks().ids(file.diff()).forEach((id, hunk) -> {
            if (hunk.lines().stream().anyMatch(l -> l.text().equals("line three"))) {
                final BitSet bits = new BitSet();
                bits.set(0, hunk.lines().size());
                first.put(id, bits);
            }
        });
        onEdt(() -> ChangelistCommands.moveFile(status.model(), repo(), "a.txt", first, "Feature", said::add));
        until(() -> status.model().listed(repo()).members().get("Feature").get(0).only() == null);
        finish("Feature");
        assertEquals("first", subject());
        assertTrue(message().getText().contains("whole file"), message().getText());
        finish("Feature");
        assertEquals(CHANGED, git.head("a.txt"));
        assertEquals("b changed\n", git.index("b.txt"));
    }

    @Test
    public void wholeFileEditsWithoutAStatusRefreshRequireConfirmation() throws Exception {
        open();
        onEdt(() -> {
            status.pick(List.of(line("  M  a.txt")));
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        final String edited = CHANGED.replace("line three", "line THREE");
        git.write("a.txt", edited);
        finish("Edited");
        assertEquals("first", subject());
        assertTrue(String.valueOf(h.status()).contains("changed since it was shown"), h.status());
        assertTrue(message().getText().startsWith("Edited"), "the draft survives re-review");
        finish("Edited");
        assertEquals(edited, git.head("a.txt"));
        assertEquals("b changed\n", git.index("b.txt"));
    }

    @Test
    public void wholeFileModeAndEmptyUntrackedBinaryContentAreReviewed() throws Exception {
        open();
        git.write("c.txt", "");
        onEdt(() -> {
            status.pick(List.of(line("  M  a.txt"), line("  ?  c.txt")));
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        assertTrue(tmp.resolve("a.txt").toFile().setExecutable(true));
        finish("Modes and binary");
        assertEquals("first", subject());
        git.write("c.txt", "binary\0content");
        finish("Modes and binary");
        assertEquals("first", subject());
        finish("Modes and binary");
        assertEquals("binary\0content", git.head("c.txt"));
        assertTrue(git.git("ls-tree", "HEAD", "a.txt").startsWith("100755"));
        assertEquals("b changed\n", git.index("b.txt"));
    }

    @Test
    public void explicitHunksStayCapturedAndRefuseChangedSelectedBytes() throws Exception {
        open();
        final var file = readA();
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(part(file, 1))));
        until(() -> message() != null);
        git.write("a.txt", CHANGED.replace("line fifty", "line FIFTY"));
        finish("Stale");
        assertEquals("first", subject());
        assertNotNull(message());
        assertFalse(String.valueOf(h.status()).contains("changed since it was shown"), h.status());
        assertTrue(String.valueOf(h.status()).contains("changed"), h.status());
        assertEquals(SIXTY, git.index("a.txt"));
    }

    @Test
    public void anIdenticalEditAtAnotherBaseLocationCannotReplaceAnExplicitHunk() throws Exception {
        open();
        final String base = SIXTY.replace("line 3\n", "x\n").replace("line 50\n", "x\n");
        git.write("a.txt", base);
        git.git("commit", "--only", "-m", "Repeated base", "--", "a.txt");
        git.write("a.txt", base.replaceFirst("(?m)^x$", "y"));
        final var file = readA();
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(part(file, 0))));
        until(() -> message() != null);

        git.write("a.txt", base.replace("x\nline 51", "y\nline 51"));
        finish("Moved");
        assertEquals("Repeated base", subject());
        assertEquals(base, git.index("a.txt"));
        assertTrue(String.valueOf(h.status()).contains("different base location"), h.status());
    }

    @Test
    public void aNewUnselectedHunkChangesWhetherTheSelectedPatchStagesTheMode() throws Exception {
        open();
        final String selected = SIXTY.replace("line 3\n", "line three\n");
        git.write("a.txt", selected);
        assertTrue(tmp.resolve("a.txt").toFile().setExecutable(true));
        final var file = readA();
        assertEquals(1, file.diff().hunks().size());
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(part(file, 0))));
        until(() -> message() != null);

        git.write("a.txt", CHANGED);
        finish("Text only");
        assertEquals("first", subject());
        assertTrue(String.valueOf(h.status()).contains("changed since it was shown"), h.status());
        finish("Text only");
        assertEquals("Text only", subject());
        assertEquals(selected, git.head("a.txt"));
        assertTrue(git.git("ls-tree", "HEAD", "a.txt").startsWith("100644"));
    }

    @Test
    public void explicitHunkMovingAfterAnUnselectedEditDoesNotNeedAnotherConfirmation() throws Exception {
        open();
        final var file = readA();
        onEdt(() -> CommitCommands.commitTargets(h.editor(), status.model(), List.of(part(file, 1))));
        until(() -> message() != null);
        git.write("a.txt", CHANGED.replace("line three\n", "line three\nextra\nrows\n"));
        finish("Fifty moved");
        assertEquals("Fifty moved", subject());
        assertEquals(SIXTY.replace("line 50\n", "line fifty\n"), git.head("a.txt"));
        assertEquals("b changed\n", git.index("b.txt"));
    }

    @Test
    public void aFailedReviewPreservesTheMessageAndCanBeRetried() throws Exception {
        open();
        onEdt(() -> {
            status.pick(List.of(line("  M  a.txt")));
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        git.write(".gitattributes", "a.txt filter=broken\n");
        git.git("config", "filter.broken.clean", "false");
        git.git("config", "filter.broken.required", "true");
        finish("Retry");
        assertEquals("first", subject());
        assertNotNull(message());
        assertTrue(message().getText().startsWith("Retry"));
        assertEquals(SIXTY, git.index("a.txt"));
        Files.delete(tmp.resolve(".gitattributes"));
        finish("Retry");
        assertEquals("Retry", subject());
        assertEquals(CHANGED, git.head("a.txt"));
    }

    @Test
    public void aFileStagedThenEditedElsewhereDoesntStopIt() throws Exception {
        open();
        // b.txt staged, then changed again: out of date with its index entry.
        git.write("b.txt", "b changed again\n");
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        fiftyInFeature();
        onEdt(() -> {
            h.editor().setDot(line("  Feature (1)"), 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        finish("Fifty");
        until(() -> "Fifty".equals(subject()));
        assertEquals("b changed\n", git.index("b.txt"), "still staged");
        assertEquals("b\n", git.head("b.txt"));
    }

    private List<GitFixture> pickInTwoRepositories() throws Exception {
        final GitFixture a = GitFixture.init(Files.createDirectories(tmp.resolve("a")));
        a.write("a.txt", "a\n");
        a.commitAll("first");
        a.write("a.txt", "a changed\n");
        final GitFixture b = GitFixture.init(Files.createDirectories(tmp.resolve("b")));
        b.write("b.txt", "b\n");
        b.commitAll("first");
        b.write("b.txt", "b changed\n");
        git = a;
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository ra = Repositories.forDirectory(a.root);
        final VcsRepository rb = Repositories.forDirectory(b.root);
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(ra, rb));
            h.showBuffer(status);
        });
        until(() -> text().contains("  M  a.txt") && text().contains("  M  b.txt"));
        onEdt(() -> {
            status.pick(List.of(line("  M  a.txt"), line("  M  b.txt")));
            CommitCommands.vcsCommitPicked(h.editor());
        });
        until(() -> message() != null);
        assertTrue(message().getText().contains(ra.name() + ": M  a.txt"), message().getText());
        assertTrue(message().getText().contains(rb.name() + ": M  b.txt"), message().getText());
        return List.of(a, b);
    }

    @Test
    public void picksInTwoRepositoriesAreCommittedOneEach() throws Exception {
        final List<GitFixture> repositories = pickInTwoRepositories();
        final GitFixture a = repositories.get(0);
        final GitFixture b = repositories.get(1);
        finish("Each");
        until(
            () -> "Each".equals(a.git("log", "-1", "--format=%s").strip())
                    && "Each".equals(b.git("log", "-1", "--format=%s").strip()));
        assertEquals("a changed\n", a.head("a.txt"));
        assertEquals("b changed\n", b.head("b.txt"));
        until(() -> status.picks().isEmpty());
    }

    @Test
    public void theNextRepositoryRefusesUnreviewedContentEditedByAnEarlierHook() throws Exception {
        final List<GitFixture> repositories = pickInTwoRepositories();
        final GitFixture a = repositories.get(0);
        final GitFixture b = repositories.get(1);
        final Path hook = a.root.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nprintf 'late edit\\n' > ../b/b.txt\n");
        assertTrue(hook.toFile().setExecutable(true));
        finish("Both");
        assertEquals("a changed\n", a.head("a.txt"));
        assertEquals("b\n", b.head("b.txt"));
        assertEquals("b\n", b.index("b.txt"));
        assertTrue(String.valueOf(h.status()).contains("staged changes differ from the review"), h.status());
        assertNotNull(message());

        until(() -> !status.model().isWriting());
        finish("Both");
        assertEquals("b\n", b.head("b.txt"), "the changed preview requires confirmation");
        assertTrue(String.valueOf(h.status()).contains("changed since it was shown"), h.status());
        finish("Both");
        assertEquals("late edit\n", b.head("b.txt"), h.status());
    }

}
