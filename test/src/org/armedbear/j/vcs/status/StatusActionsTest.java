/*
 * StatusActionsTest.java
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusActions.Op;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StatusActionsTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private VcsStatusBuffer status;
    private final List<String> asked = new ArrayList<>();
    private boolean answer = true;

    private static final String SIXTY;

    static {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++)
            sb.append("line ").append(i).append('\n');
        SIXTY = sb.toString();
    }

    private static final java.util.function.BiPredicate<Editor, String> SAVED_CONFIRM = StatusActions.confirm;

    @BeforeEach
    public void setUp() {
        StatusActions.confirm = (editor, text) -> {
            asked.add(text);
            return answer;
        };
        CommitCommands.show = (editor, buffer) -> {
            h.showBuffer(buffer);
            return h.editor();
        };
    }

    @AfterEach
    public void tearDown() {
        StatusActions.confirm = SAVED_CONFIRM;
        CommitCommands.show = (editor, buffer) -> editor.activateInOtherWindow(buffer);
        final List<org.armedbear.j.Buffer> buffers = new ArrayList<>();
        for (org.armedbear.j.Buffer b : Editor.getBufferList())
            buffers.add(b);
        for (org.armedbear.j.Buffer b : buffers)
            if (b instanceof VcsStatusBuffer status && status.getDiff() != null)
                status.closeDiff(null);
        for (org.armedbear.j.Buffer b : buffers)
            if (b instanceof VcsStatusBuffer || b instanceof CommitMessageBuffer) {
                b.dispose();
                Editor.getBufferList().remove(b);
            }
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        GitFixture.reset();
    }

    // a.txt of sixty lines with lines 3 and 50 changed; b.txt staged; c.txt new.
    private void repository() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", SIXTY);
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n"));
        git.write("b.txt", "b changed\n");
        git.git("add", "b.txt");
        git.write("c.txt", "new\n");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private void open() throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
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
        throw new AssertionError("timed out: " + text());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private List<String> text() {
        final List<String> list = new ArrayList<>();
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

    // The text, after line, of the first one starting with prefix.
    private StatusLine after(StatusLine line, String prefix) {
        for (Line l = line.next(); l != null; l = l.next())
            if (l.getText().startsWith(prefix))
                return (StatusLine) l;
        throw new AssertionError("nothing starting " + prefix + " after " + line.getText());
    }

    private void act(String at, Op op) throws Exception {
        onEdt(() -> {
            h.editor().setDot(line(at), 0);
            StatusActions.run(h.editor(), op);
        });
    }

    private void openFile(String fileLine, String waitFor) throws Exception {
        onEdt(() -> status.toggle(line(fileLine)));
        until(() -> text().contains(waitFor));
    }

    @Test
    public void eachIsAbleAsWhatTheCaretIsOnTakesIt() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            assertTrue(StatusActions.can(h.editor(), Op.STAGE));
            assertFalse(StatusActions.can(h.editor(), Op.UNSTAGE));
            assertTrue(StatusActions.can(h.editor(), Op.DISCARD));
            h.editor().setDot(line("  M  b.txt"), 0);
            assertFalse(StatusActions.can(h.editor(), Op.STAGE), "staged");
            assertTrue(StatusActions.can(h.editor(), Op.UNSTAGE));
            h.editor().setDot(line("  ?  c.txt"), 0);
            assertTrue(StatusActions.can(h.editor(), Op.STAGE));
            assertFalse(StatusActions.can(h.editor(), Op.UNSTAGE));
            h.editor().setDot(after(line("  M  b.txt"), "Recent commits"), 0);
            assertFalse(StatusActions.can(h.editor(), Op.STAGE));
            assertFalse(StatusActions.can(h.editor(), Op.DISCARD));
            h.editor().setDot(status.getFirstLine(), 0);
            assertFalse(StatusActions.can(h.editor(), Op.STAGE), "the header");
        });
    }

    @Test
    public void whileWhitespaceIsIgnoredAFileIsStagedWholeAndAHunkNot() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line  50\n"));
        open();
        openFile("  M  a.txt", "  -line 3");
        onEdt(() -> assertTrue(text().contains("  +line  50"), "compared, a change in amount shows"));
        try {
            onEdt(() -> DiffWhitespace.choose(Whitespace.AMOUNT));
            until(() -> !text().contains("  +line  50"));
            onEdt(() -> {
                h.editor().setDot(after(line("  M  a.txt"), "  @@ -1,6"), 0);
                assertFalse(StatusActions.can(h.editor(), Op.STAGE));
                StatusActions.run(h.editor(), Op.STAGE);
                assertEquals("Not a hunk while whitespace is ignored: a file is staged whole", h.status());
                StatusActions.run(h.editor(), Op.DISCARD);
                assertEquals("Not a hunk while whitespace is ignored: a file is discarded whole", h.status());
                h.editor().setDot(line("  M  a.txt"), 0);
                assertTrue(StatusActions.can(h.editor(), Op.STAGE));
            });
        }
        finally {
            onEdt(DiffWhitespace::forget);
        }
    }

    @Test
    public void sStagesAFileAndUUnstagesIt() throws Exception {
        repository();
        open();
        act("  M  a.txt", Op.STAGE);
        until(() -> !text().contains("Unstaged changes (1)") && text().contains("Staged changes (2)"));
        assertEquals(git.read("a.txt"), git.index("a.txt"));
        act("  M  a.txt", Op.UNSTAGE);
        until(() -> text().contains("Unstaged changes (1)"));
        assertEquals(SIXTY, git.index("a.txt"));
    }

    @Test
    public void afterStagingTheCaretIsOnTheNextFile() throws Exception {
        repository();
        git.write("d.txt", "d\n");
        git.git("add", "d.txt");
        git.git("commit", "-q", "-m", "d");
        git.write("d.txt", "d changed\n");
        open();
        act("  M  a.txt", Op.STAGE);
        until(() -> text().contains("Unstaged changes (1)"));
        onEdt(() -> assertEquals("  M  d.txt", h.editor().getDotLine().getText()));
    }

    @Test
    public void sOnAHunkStagesThatHunkOnly() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 3");
        onEdt(() -> {
            h.editor().setDot(after(line("  M  a.txt"), "  @@ -1,6"), 0);
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> text().contains("Staged changes (2)"));
        assertEquals(SIXTY.replace("line 3\n", "line three\n"), git.index("a.txt"));
    }

    @Test
    public void aSelectionStagesItsLines() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\ntwo\nthree\n");
        git.commitAll("first");
        git.write("a.txt", "one\nTWO\nthree\nfour\n");
        open();
        openFile("  M  a.txt", "  +four");
        onEdt(() -> {
            // Only the added "four".
            h.editor().setMark(new Position(line("  +four"), 0));
            h.editor().setDot(line("  +four"), 4);
            final List<StatusActions.Target> t = StatusActions.targets(status, h.editor());
            assertEquals(1, t.size());
            assertEquals("a line of a.txt", StatusActions.names(t));
        });
        onEdt(() -> StatusActions.run(h.editor(), Op.STAGE));
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("one\ntwo\nthree\nfour\n", git.index("a.txt"));
    }

    @Test
    public void xDiscardsAnUnstagedHunkAfterAsking() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        answer = false;
        onEdt(() -> {
            h.editor().setDot(after(line("  M  a.txt"), "  @@ -47"), 0);
            StatusActions.run(h.editor(), Op.DISCARD);
        });
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).startsWith("Discard a hunk of a.txt?"), asked.get(0));
        assertTrue(git.read("a.txt").contains("line fifty"), "not without a yes");
        answer = true;
        onEdt(() -> StatusActions.run(h.editor(), Op.DISCARD));
        until(() -> !text().contains("  -line 50"));
        assertEquals(SIXTY.replace("line 3\n", "line three\n"), git.read("a.txt"));
    }

    @Test
    public void xRemovesAnUntrackedFile() throws Exception {
        repository();
        open();
        act("  ?  c.txt", Op.DISCARD);
        until(() -> !text().contains("  ?  c.txt"));
        assertFalse(Files.exists(git.root.resolve("c.txt")));
        assertTrue(asked.get(0).contains("c.txt"), asked.toString());
    }

    @Test
    public void xOnAStagedFileTakesItOutOfTheIndexAndTheWorkingTree() throws Exception {
        repository();
        open();
        act("  M  b.txt", Op.DISCARD);
        until(() -> !text().stream().anyMatch(s -> s.startsWith("Staged changes")));
        assertEquals("b\n", git.read("b.txt"));
        assertEquals("b\n", git.index("b.txt"));
        assertTrue(asked.get(0).contains("staged and in the working tree"), asked.toString());
    }

    @Test
    public void stagingWhatIsStagedSaysSo() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  M  b.txt"), 0);
            assertEquals(
                "Already staged",
                StatusActions.refuse(Op.STAGE, StatusActions.targets(status, h.editor()).get(0)));
            h.editor().setDot(line("  ?  c.txt"), 0);
            assertEquals(
                "Not staged",
                StatusActions.refuse(Op.UNSTAGE, StatusActions.targets(status, h.editor()).get(0)));
        });
    }

    @Test
    public void xOnAnIntentToAddFileRemovesItWholeNotItsText() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.write("n.txt", "kept\n");
        git.git("add", "-N", "n.txt");
        open();
        act("  A  n.txt", Op.DISCARD);
        until(() -> !text().contains("  A  n.txt"));
        assertTrue(asked.get(0).contains("n.txt") && !asked.get(0).startsWith("Discard"), asked.toString());
        assertFalse(Files.exists(git.root.resolve("n.txt")), "gone whole, as the question says");
        assertEquals(null, git.index("n.txt"));
    }

    @Test
    public void xOnAStagedNewFileAsksToRemoveIt() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.write("n.txt", "new\n");
        git.git("add", "n.txt");
        open();
        act("  A  n.txt", Op.DISCARD);
        until(() -> !text().stream().anyMatch(t -> t.startsWith("Staged changes")));
        assertFalse(asked.get(0).startsWith("Discard"), asked.toString());
        assertEquals(null, git.index("n.txt"));
    }

    @Test
    public void xOnAStagedChangeKeepsUnstagedOnesElsewhereInTheFile() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n"));
        git.git("add", "a.txt");
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n"));
        open();
        onEdt(() -> {
            // The second "  M  a.txt" is the staged one.
            h.editor().setDot(after(line("Staged changes (2)"), "  M  a.txt"), 0);
            StatusActions.run(h.editor(), Op.DISCARD);
        });
        until(() -> text().contains("Staged changes (1)"));
        assertEquals(SIXTY, git.index("a.txt"));
        assertEquals(SIXTY.replace("line 50\n", "line fifty\n"), git.read("a.txt"), "the unstaged change stays");
    }

    @Test
    public void aStagedChangeUnderAnUnstagedOneIsNotDiscarded() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\n");
        git.commitAll("first");
        git.write("a.txt", "two\n");
        git.git("add", "a.txt");
        git.write("a.txt", "three\n");
        final StatusActions.Files t =
                new StatusActions.Files(Repositories.forDirectory(git.root), "staged", new ArrayList<>());
        open();
        until(() -> text().contains("Staged changes (1)"));
        onEdt(() -> t.files().addAll(status.files(repo(), "staged")));
        final String why = StatusActions.discard(List.of(t));
        assertTrue(why != null && why.contains("unstaged changes"), why);
        assertEquals("three\n", git.read("a.txt"), "nothing changed");
        assertEquals("two\n", git.index("a.txt"));
    }

    @Test
    public void aSecondActionWaitsForTheFirstsRefresh() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            StatusActions.run(h.editor(), Op.STAGE);
            assertTrue(status.isWriting());
            StatusActions.run(h.editor(), Op.STAGE); // Refused.
        });
        until(() -> !status.isWriting());
        assertEquals(git.read("a.txt"), git.index("a.txt"));
    }

    @Test
    public void unstagingAFileChangedSinceBeforeTheFirstCommit() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.git("add", "a.txt");
        git.write("a.txt", "a changed\n");
        open();
        act("  A  a.txt", Op.UNSTAGE);
        until(() -> text().contains("  ?  a.txt"));
        assertEquals("a changed\n", git.read("a.txt"));
    }

    @Test
    public void pathsWithSpacesDashesAndGlobsStage() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.write("-x [a]*.txt", "x\n");
        open();
        act("  ?  -x [a]*.txt", Op.STAGE);
        until(() -> text().contains("  A  -x [a]*.txt"));
    }

    @Test
    public void rewordWithAllOnStillRewords() throws Exception {
        repository();
        open();
        Transients.args(h.editor(), CommitCommands.ID).setOn("--all", true);
        onEdt(() -> CommitCommands.vcsCommitReword(h.editor()));
        until(() -> message() != null);
        assertTrue(message().getText().contains("# Only the message changes."), message().getText());
        onEdt(() -> CommitCommands.replace(h.editor(), message(), "reworded"));
        final CommitMessageBuffer m = message();
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertEquals("reworded", git.git("log", "-1", "--format=%s").strip());
        assertEquals("b\n", git.head("b.txt"));
    }

    @Test
    public void escapeWhileCommittingKeepsTheMessage() throws Exception {
        repository();
        Files.writeString(git.root.resolve(".git/hooks/pre-commit"), "#!/bin/sh\nsleep 1\nexit 1\n");
        git.root.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);
        open();
        startCommit();
        type("Slow");
        final CommitMessageBuffer m = message();
        onEdt(() -> {
            CommitCommands.vcsCommitFinish(h.editor());
            CommitCommands.vcsCommitCancel(h.editor());
        });
        assertTrue(Editor.getBufferList().contains(m), "not while committing");
        until(() -> !m.isCommitting());
        assertTrue(Editor.getBufferList().contains(m), "and kept when the hook fails");
    }

    @Test
    public void aMessageWithItsOwnHashLinesKeepsThemOnAmend() throws Exception {
        repository();
        git.git("commit", "-q", "--amend", "--cleanup=verbatim", "-m", "Fix\n\n#123 is the issue");
        open();
        onEdt(() -> CommitCommands.vcsCommitAmend(h.editor()));
        until(() -> message() != null);
        assertEquals("Fix\n\n#123 is the issue", message().getMessage());
    }

    @Test
    public void sOnASectionHeadingStagesItsFiles() throws Exception {
        repository();
        open();
        act("Untracked files (1)", Op.STAGE);
        until(() -> text().contains("  A  c.txt"));
    }

    @Test
    public void stageAllAndUnstageAll() throws Exception {
        repository();
        open();
        onEdt(() -> StatusActions.all(h.editor(), true));
        until(() -> text().contains("Staged changes (2)"));
        assertTrue(text().contains("  ?  c.txt"), "untracked files stay out");
        onEdt(() -> StatusActions.all(h.editor(), false));
        until(() -> !text().stream().anyMatch(s -> s.startsWith("Staged changes")));
    }

    @Test
    public void unstagingBeforeTheFirstCommit() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.git("add", "a.txt");
        open();
        act("  A  a.txt", Op.UNSTAGE);
        until(() -> text().contains("  ?  a.txt"));
    }

    // Commits.

    private CommitMessageBuffer message() {
        return h.editor().getBuffer() instanceof CommitMessageBuffer b ? b : null;
    }

    // c c, and the message it opens once its template is read.
    private void startCommit() throws Exception {
        onEdt(() -> CommitCommands.vcsCommit(h.editor()));
        until(() -> message() != null);
    }

    private void type(String text) throws Exception {
        onEdt(() -> message().insertString(new Position(message().getFirstLine(), 0), text));
    }

    @Test
    public void cOpensAMessageAndCtrlEnterCommitsWhatIsStaged() throws Exception {
        repository();
        open();
        startCommit();
        assertNotNull(message());
        assertTrue(message().getText().contains("#   M  b.txt"), message().getText());
        type("Change b\n\nBecause.");
        final CommitMessageBuffer m = message();
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertEquals("Change b\n\nBecause.", git.git("log", "-1", "--format=%B").strip());
        assertEquals("b changed\n", git.head("b.txt"));
        assertEquals(SIXTY, git.head("a.txt"), "only what was staged");
    }

    // Runs body with vim as the edit mode everywhere, as a user sets it.
    private void inVim(ThrowingRunnable body) throws Exception {
        h.vim();
        final org.armedbear.j.Preferences prefs = Editor.preferences();
        final String was = prefs.getStringProperty(org.armedbear.j.Property.EDIT_MODE);
        prefs.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
        try {
            body.run();
        }
        finally {
            if (was == null)
                prefs.removeProperty(org.armedbear.j.Property.EDIT_MODE.key());
            else
                prefs.setProperty(org.armedbear.j.Property.EDIT_MODE, was);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void inVimEscapeInNormalModeGivesTheMessageUp() throws Exception {
        repository();
        open();
        inVim(() -> {
            startCommit();
            final CommitMessageBuffer m = message();
            onEdt(() -> h.keys("hello<Esc>"));
            assertTrue(Editor.getBufferList().contains(m), "the first Escape leaves insert mode");
            onEdt(() -> h.keys("<Esc>"));
            assertEquals(1, asked.size(), "the second asks");
            assertFalse(Editor.getBufferList().contains(m));
        });
    }

    @Test
    public void inVimVThenSStagesTheLinesSelected() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\ntwo\n");
        git.commitAll("first");
        git.write("a.txt", "one\ntwo\nthree\nfour\n");
        open();
        openFile("  M  a.txt", "  +four");
        inVim(() -> {
            onEdt(() -> {
                h.editor().setDot(line("  +three"), 0);
                h.keys("Vs");
            });
            until(() -> text().contains("Staged changes (1)"));
        });
        assertEquals("one\ntwo\nthree\n", git.index("a.txt"));
    }

    @Test
    public void stagingHalfOfAnEditedLineStagesTheEditAndAnAddedLineAlone() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\nb\nc\n");
        git.commitAll("first");
        git.write("a.txt", "a\nB\nnew\nc\n"); // b edited, a line added after it.
        open();
        openFile("  M  a.txt", "  +new");
        inVim(() -> {
            onEdt(() -> {
                h.editor().setDot(line("  +B"), 0);
                h.keys("Vs");
            });
            until(() -> text().stream().anyMatch(t -> t.startsWith("Staged changes")));
            assertEquals("a\nB\nc\n", git.index("a.txt"), "the edit, not B beside b");
            git.git("reset", "-q");
            onEdt(() -> {
                repo().invalidate();
                status.refresh();
            });
            until(() -> !text().stream().anyMatch(t -> t.startsWith("Staged changes")) && text().contains("  +new"));
            onEdt(() -> {
                h.editor().setDot(line("  +new"), 0);
                h.keys("Vs");
            });
            until(() -> text().stream().anyMatch(t -> t.startsWith("Staged changes")));
            assertEquals("a\nb\nnew\nc\n", git.index("a.txt"), "only the added line");
        });
    }

    @Test
    public void inVimZZAndWqCommitAndZQAndQBangGiveUpWithoutAsking() throws Exception {
        repository();
        open();
        inVim(() -> {
            startCommit();
            CommitMessageBuffer m = message();
            onEdt(() -> h.keys("Gone<Esc>ZQ"));
            assertFalse(Editor.getBufferList().contains(m), "ZQ gives it up");
            assertTrue(asked.isEmpty(), "without asking");
            assertEquals("Gone", org.armedbear.j.CommentRing.getInstance().peek());

            startCommit();
            m = message();
            onEdt(() -> {
                h.keys("Kept<Esc>");
                h.exCommand("q!");
            });
            assertFalse(Editor.getBufferList().contains(m), ":q! gives it up");

            startCommit();
            final CommitMessageBuffer first = message();
            onEdt(() -> h.keys("Zed<Esc>ZZ"));
            until(() -> !Editor.getBufferList().contains(first));
            assertEquals("Zed", git.git("log", "-1", "--format=%s").strip());
        });
    }

    @Test
    public void inVimWqCommits() throws Exception {
        repository();
        open();
        inVim(() -> {
            startCommit();
            final CommitMessageBuffer m = message();
            onEdt(() -> {
                h.keys("Written<Esc>");
                h.exCommand("wq");
            });
            until(() -> !Editor.getBufferList().contains(m));
            assertEquals("Written", git.git("log", "-1", "--format=%s").strip());
        });
    }

    @Test
    public void verboseShowsTheDiffUnderTheScissorsAndLeavesItOut() throws Exception {
        repository();
        open();
        Transients.args(h.editor(), CommitCommands.ID).setOn("--verbose", true);
        startCommit();
        until(() -> message() != null);
        final String text = message().getText();
        assertTrue(text.contains(CommitCommands.SCISSORS + "\n"), text);
        assertTrue(text.contains("+b changed"), "the staged diff: " + text);
        assertFalse(text.contains("line three"), "not what is unstaged");
        type("Verbose");
        assertEquals("Verbose", message().getMessage());
        final CommitMessageBuffer m = message();
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertEquals("Verbose", git.git("log", "-1", "--format=%B").strip());
    }

    @Test
    public void gitsCommitVerboseMakesItVerbose() throws Exception {
        repository();
        git.git("config", "commit.verbose", "true");
        open();
        startCommit();
        until(() -> message() != null);
        assertTrue(message().getText().contains(CommitCommands.SCISSORS), message().getText());
    }

    @Test
    public void aReadStartedDuringAWriteDoesNotEndIt() throws Exception {
        repository();
        open();
        onEdt(() -> status.startWriting());
        onEdt(() -> status.refresh()); // As a return to j's window would.
        Thread.sleep(500);
        onEdt(() -> assertTrue(status.isWriting(), "its data may be from before the write"));
        status.wrote();
        onEdt(() -> status.refresh());
        until(() -> !status.isWriting());
    }

    @Test
    public void aBackendThatThrowsLeavesTheBufferTakingKeys() throws Exception {
        final org.armedbear.j.vcs.VcsBackend throwing = new org.armedbear.j.vcs.VcsBackend() {
            @Override
            public int id() {
                return -1;
            }

            @Override
            public String name() {
                return "broken";
            }

            @Override
            public java.util.Set<org.armedbear.j.vcs.Capability> capabilities() {
                return java.util.Set.of(org.armedbear.j.vcs.Capability.STAGING);
            }

            @Override
            public StatusData readStatus(
                    VcsRepository r,
                    java.util.function.BiPredicate<String, String> wants,
                    Whitespace ignored) {
                return new StatusData(List.of(new StatusData.Field("Head", "main")), null, List.of(), null);
            }

            @Override
            public org.armedbear.j.vcs.VcsResult stageAll(VcsRepository r) {
                throw new IllegalStateException("broken");
            }
        };
        final var c = VcsRepository.class.getDeclaredConstructor(Path.class, org.armedbear.j.vcs.VcsBackend.class);
        c.setAccessible(true);
        final VcsRepository r = c.newInstance(tmp, throwing);
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
        });
        until(() -> !status.getFirstLine().getText().startsWith("Reading"));
        onEdt(() -> StatusActions.all(h.editor(), true));
        until(() -> !status.isWriting());
        h.clearLoggedErrors(); // The exception, logged as it should be.
    }

    @Test
    public void unstageAllIsRefusedDuringAMerge() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "theirs\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
        open();
        onEdt(() -> StatusActions.all(h.editor(), false));
        Thread.sleep(300);
        assertTrue(Files.exists(git.root.resolve(".git/MERGE_HEAD")), "the merge goes on");
    }

    @Test
    public void ctrlPGoesBackAndCtrlNComesForwardToTheMessageBeingWritten() throws Exception {
        repository();
        open();
        final org.armedbear.j.CommentRing ring = org.armedbear.j.CommentRing.getInstance();
        ring.appendNew("older");
        ring.appendNew("newer");
        startCommit();
        onEdt(() -> CommitCommands.vcsCommitNextMessage(h.editor()));
        assertEquals("", message().getMessage(), "nothing later than what is being written");
        onEdt(() -> CommitCommands.vcsCommitPreviousMessage(h.editor()));
        assertEquals("newer", message().getMessage());
        onEdt(() -> CommitCommands.vcsCommitPreviousMessage(h.editor()));
        assertEquals("older", message().getMessage());
        onEdt(() -> CommitCommands.vcsCommitNextMessage(h.editor()));
        assertEquals("newer", message().getMessage());
        onEdt(() -> CommitCommands.vcsCommitNextMessage(h.editor()));
        assertEquals("", message().getMessage(), "back to the start");
    }

    @Test
    public void inVimTheMessageStartsInInsertMode() throws Exception {
        repository();
        open();
        h.vim();
        final org.armedbear.j.Preferences prefs = Editor.preferences();
        final String was = prefs.getStringProperty(org.armedbear.j.Property.EDIT_MODE);
        prefs.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
        try {
            startCommit();
            assertEquals(org.armedbear.j.vim.VimMode.INSERT, h.vimState().getMode());
        }
        finally {
            if (was == null)
                prefs.removeProperty(org.armedbear.j.Property.EDIT_MODE.key());
            else
                prefs.setProperty(org.armedbear.j.Property.EDIT_MODE, was);
        }
    }

    @Test
    public void nothingStagedOpensNoMessage() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.write("a.txt", "b\n");
        open();
        onEdt(() -> CommitCommands.vcsCommit(h.editor()));
        assertTrue(h.editor().getBuffer() == status);
        Transients.args(h.editor(), CommitCommands.ID).setOn("--all", true);
        startCommit();
        assertNotNull(message(), "-a commits every change");
    }

    @Test
    public void amendStartsFromTheLastMessageAndRewordLeavesWhatIsStaged() throws Exception {
        repository();
        open();
        onEdt(() -> CommitCommands.vcsCommitReword(h.editor()));
        until(() -> message() != null);
        assertTrue(message().getText().startsWith("first\n"), message().getText());
        onEdt(() -> CommitCommands.replace(h.editor(), message(), "renamed"));
        final CommitMessageBuffer m = message();
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertEquals("renamed", git.git("log", "-1", "--format=%s").strip());
        assertEquals("b\n", git.head("b.txt"), "the staged change is not in it");
        assertEquals("b changed\n", git.index("b.txt"), "and is still staged");
        assertEquals("1", git.git("rev-list", "--count", "HEAD").strip());
    }

    @Test
    public void extendAddsWhatIsStagedKeepingTheMessage() throws Exception {
        repository();
        open();
        onEdt(() -> CommitCommands.vcsCommitExtend(h.editor()));
        until(() -> git.head("b.txt").equals("b changed\n"));
        assertEquals("first", git.git("log", "-1", "--format=%s").strip());
        assertEquals("1", git.git("rev-list", "--count", "HEAD").strip());
    }

    @Test
    public void signoffAndAFailingHookAndNoVerify() throws Exception {
        repository();
        Files.writeString(git.root.resolve(".git/hooks/pre-commit"), "#!/bin/sh\necho no >&2\nexit 1\n");
        git.root.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);
        open();
        Transients.args(h.editor(), CommitCommands.ID).setOn("--signoff", true);
        startCommit();
        type("Hooked");
        final CommitMessageBuffer m = message();
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !m.isCommitting() && !status.isWriting());
        assertTrue(Editor.getBufferList().contains(m), "a failed commit keeps its message");
        assertEquals("1", git.git("rev-list", "--count", "HEAD").strip());
        m.getOptions().add(org.armedbear.j.vcs.CommitOption.NO_VERIFY);
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertTrue(git.git("log", "-1", "--format=%B").contains("Signed-off-by: Tester <tester@example.com>"));
    }

    @Test
    public void escapeAsksBeforeGivingUpAMessage() throws Exception {
        repository();
        open();
        startCommit();
        type("Not yet");
        final CommitMessageBuffer m = message();
        answer = false;
        onEdt(() -> CommitCommands.vcsCommitCancel(h.editor()));
        assertTrue(Editor.getBufferList().contains(m));
        answer = true;
        onEdt(() -> CommitCommands.vcsCommitCancel(h.editor()));
        assertFalse(Editor.getBufferList().contains(m));
        assertEquals("Not yet", org.armedbear.j.CommentRing.getInstance().peek());
    }

    @Test
    public void aLongSummaryIsMarkedPastFifty() throws Exception {
        repository();
        open();
        startCommit();
        type("x".repeat(60));
        final org.armedbear.j.LineSegmentList segments = message().getFormatter().formatLine(message().getFirstLine());
        assertEquals(2, segments.size());
        assertEquals(50, segments.getSegment(0).getText().length());
    }

    // The diff beside, showing the file at status line at; then the
    // harness's window on the diff, as when it has the focus.
    private StatusDiffBuffer paneOn(String at, String waitFor) throws Exception {
        onEdt(() -> {
            if (status.getDiff() == null)
                status.openDiff(null);
            h.editor().setDot(line(at), 0);
            status.followCaret();
        });
        final StatusDiffBuffer diff = status.getDiff();
        until(() -> paneLine(diff, waitFor) != null);
        onEdt(() -> h.showBuffer(diff));
        return diff;
    }

    private static StatusLine paneLine(StatusDiffBuffer diff, String prefix) {
        for (Line l = diff.getFirstLine(); l != null; l = l.next())
            if (l.getText().startsWith(prefix))
                return (StatusLine) l;
        return null;
    }

    @Test
    public void commitFromTheDiffBesideOpensThePopupOnTheStatus() throws Exception {
        repository();
        open();
        final EditorHarness s = EditorHarness.create();
        try {
            paneOn("  M  a.txt", "  @@ -1,6");
            onEdt(() -> s.showBuffer(status));
            onEdt(() -> {
                CommitCommands.vcsCommitPopup(h.editor());
                final TransientPopup popup = TransientPopup.getOpen();
                assertNotNull(popup);
                assertSame(s.editor(), popup.getEditor());
                popup.close(false);
            });
        }
        finally {
            onEdt(() -> {
                s.showBuffer(s.buffer());
                s.close();
            });
        }
    }

    @Test
    public void discardingAStagedFileWhileWhitespaceIsIgnoredTakesAllOfIt() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line  50\n"));
        git.git("add", "a.txt");
        open();
        try {
            onEdt(() -> DiffWhitespace.choose(Whitespace.ALL));
            until(() -> text().contains("Staged changes (2)"));
            final StatusLine staged = line("Staged changes (2)");
            onEdt(() -> {
                h.editor().setDot(after(staged, "  M  a.txt"), 0);
                StatusActions.run(h.editor(), Op.DISCARD);
            });
            until(() -> text().contains("Staged changes (1)"));
            assertEquals(SIXTY, git.index("a.txt"), "its change to whitespace too");
            assertEquals(SIXTY, git.read("a.txt"));
        }
        finally {
            onEdt(DiffWhitespace::forget);
        }
    }

    @Test
    public void sInTheDiffBesideStagesTheHunkAtTheCaret() throws Exception {
        repository();
        open();
        final StatusDiffBuffer diff = paneOn("  M  a.txt", "  @@ -1,6");
        onEdt(() -> {
            // On a line of the hunk, not its heading: the hunk all the same.
            h.editor().setDot(paneLine(diff, "  +line three"), 0);
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> text().contains("Staged changes (2)"));
        assertEquals(SIXTY.replace("line 3\n", "line three\n"), git.index("a.txt"), "that hunk, not line 50's");
    }

    @Test
    public void aSelectionInTheDiffBesideStagesItsLines() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\ntwo\nthree\n");
        git.commitAll("first");
        git.write("a.txt", "one\nTWO\nthree\nfour\n");
        open();
        final StatusDiffBuffer diff = paneOn("  M  a.txt", "  +four");
        onEdt(() -> {
            final StatusLine four = paneLine(diff, "  +four");
            h.editor().setMark(new Position(four, 0));
            h.editor().setDot(four, 4);
            assertEquals("a line of a.txt", StatusActions.names(StatusActions.targets(status, h.editor())));
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("one\ntwo\nthree\nfour\n", git.index("a.txt"));
    }

    @Test
    public void theDiffBesideUnstagesAndDiscardsAsTheStatusDoes() throws Exception {
        repository();
        open();
        StatusDiffBuffer diff = paneOn("  M  b.txt", "  +b changed");
        onEdt(() -> {
            h.editor().setDot(paneLine(diff, "  +b changed"), 0);
            final List<StatusActions.Target> t = StatusActions.targets(status, h.editor());
            assertEquals("Already staged", StatusActions.refuse(Op.STAGE, t.get(0)));
            StatusActions.run(h.editor(), Op.STAGE);
            assertFalse(status.isWriting(), "refused");
        });
        onEdt(() -> StatusActions.run(h.editor(), Op.UNSTAGE));
        until(() -> text().contains("Unstaged changes (2)"));
        assertEquals("b\n", git.index("b.txt"));
        onEdt(() -> h.showBuffer(status));
        final StatusDiffBuffer again = paneOn("  M  a.txt", "  +line fifty");
        onEdt(() -> {
            h.editor().setDot(paneLine(again, "  +line fifty"), 0);
            StatusActions.run(h.editor(), Op.DISCARD);
        });
        assertEquals(1, asked.size(), "asked first");
        until(() -> !status.isWriting());
        assertFalse(git.read("a.txt").contains("line fifty"));
        assertTrue(git.read("a.txt").contains("line three"), "only that hunk");
    }

    @Test
    public void afterStagingFromTheDiffBesideItsCaretIsInTheNextHunkAndItWaitsToBeReadAgain() throws Exception {
        repository();
        open();
        final StatusDiffBuffer diff = paneOn("  M  a.txt", "  @@ -1,6");
        onEdt(() -> {
            h.editor().setDot(paneLine(diff, "  +line three"), 0);
            StatusActions.run(h.editor(), Op.STAGE);
            assertTrue(diff.isStale(), "shows what was there before the write");
            // A second, at once: refused until the diff is read again.
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> !status.isWriting() && !diff.isStale() && paneLine(diff, "  +line three") == null);
        assertEquals(SIXTY.replace("line 3\n", "line three\n"), git.index("a.txt"), "the first hunk alone");
        onEdt(() -> {
            final StatusLine dot = (StatusLine) h.editor().getDotLine();
            assertTrue(dot.kind() != StatusLine.Kind.FILE, "not on the file, where s takes it all: " + dot.getText());
            assertEquals(paneLine(diff, "  @@ -47").key(), StatusLine.head(dot).key(), "in the hunk after");
            StatusActions.run(h.editor(), Op.STAGE);
        });
        // Nothing of a.txt left unstaged: the diff, whose file the status no longer has there, is cleared.
        until(() -> !status.isWriting() && !diff.isStale() && paneLine(diff, "  M  a.txt") == null);
        assertEquals(git.read("a.txt"), git.index("a.txt"));
        assertTrue(paneLine(diff, "No file") != null, String.valueOf(diff.getFirstLine().getText()));
    }

    @Test
    public void vimsHunkKeysStageAndUnstageInTheDiffBeside() throws Exception {
        repository();
        open();
        h.vim();
        final StatusDiffBuffer diff = paneOn("  M  a.txt", "  @@ -1,6");
        onEdt(() -> h.editor().setDot(paneLine(diff, "  +line three"), 0));
        h.keys("\\hs");
        until(() -> text().contains("Staged changes (2)") && !status.isWriting());
        assertEquals(SIXTY.replace("line 3\n", "line three\n"), git.index("a.txt"), "the hunk at the caret");
        // The staged a.txt, under Staged changes, in the diff beside.
        final StatusDiffBuffer staged = diff;
        onEdt(() -> {
            h.showBuffer(status);
            h.editor().setDot(after(line("Staged changes (2)"), "  M  a.txt"), 0);
            status.followCaret();
        });
        until(
            () -> paneLine(staged, "  +line three") != null
                    && paneLine(staged, "  +line fifty") == null
                    && !staged.isStale());
        onEdt(() -> {
            h.showBuffer(staged);
            h.editor().setDot(paneLine(staged, "  +line three"), 0);
        });
        h.keys("\\hu");
        until(() -> !text().contains("Staged changes (2)") && !status.isWriting());
        assertEquals(SIXTY, git.index("a.txt"), "unstaged again");
    }
}
