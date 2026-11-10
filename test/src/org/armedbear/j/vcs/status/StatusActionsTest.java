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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
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
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.signs.HunkCommands;
import org.armedbear.j.vcs.status.StatusActions.Op;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;
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

    private static final StatusActions.Confirm SAVED_CONFIRM = StatusActions.confirm;

    @BeforeEach
    public void setUp() {
        StatusActions.confirm = (editor, text, title) -> {
            asked.add(text);
            return answer;
        };
        CommitCommands.show = (editor, buffer) -> {
            h.showBuffer(buffer);
            return h.editor();
        };
    }

    @AfterEach
    public void tearDown() throws Exception {
        StatusActions.confirm = SAVED_CONFIRM;
        CommitCommands.show = (editor, buffer) -> editor.activateInOtherWindow(buffer);
        final List<org.armedbear.j.Buffer> buffers = new ArrayList<>();
        for (org.armedbear.j.Buffer b : Editor.getBufferList())
            buffers.add(b);
        // On the event thread, as the status's model is told.
        SwingUtilities.invokeAndWait(() -> {
            for (org.armedbear.j.Buffer b : buffers)
                if (b instanceof VcsStatusBuffer status && status.getDiff() != null)
                    status.closeDiff(null);
            for (org.armedbear.j.Buffer b : buffers)
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
    public void aPickWhoseHunkWhitespaceChangesIsDroppedSayingWhy() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 4\n", "line  4\n"));
        open();
        openFile("  M  a.txt", "  -line 3");
        pickAfter("  M  a.txt", "  @@ -1,");
        try {
            onEdt(() -> DiffWhitespace.choose(Whitespace.AMOUNT));
            until(() -> "A pick was dropped: whitespace shown or ignored changed its hunk".equals(h.status()));
            onEdt(() -> assertTrue(status.picks().isEmpty()));
        }
        finally {
            onEdt(DiffWhitespace::forget);
        }
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
            final List<Target> t = StatusActions.targets(status, h.editor());
            assertEquals(1, t.size());
            assertEquals("a line of a.txt", StatusActions.names(t));
        });
        onEdt(() -> StatusActions.run(h.editor(), Op.STAGE));
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("one\ntwo\nthree\nfour\n", git.index("a.txt"));
    }

    // ---- picking

    private void pick(String at) throws Exception {
        onEdt(() -> {
            h.editor().setDot(line(at), 0);
            VcsStatusMode.vcsStatusPick(h.editor());
        });
    }

    private void pickAfter(String file, String prefix) throws Exception {
        onEdt(() -> {
            h.editor().setDot(after(line(file), prefix), 0);
            VcsStatusMode.vcsStatusPick(h.editor());
        });
    }

    @Test
    public void sStagesWhatIsPickedNotWhatIsAtTheCaret() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pick("  ?  c.txt");
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(
            () -> assertEquals(
                "c.txt, a hunk of a.txt",
                StatusActions.names(StatusActions.targets(status, h.editor()))));
        // The caret elsewhere: the picks are what s takes.
        act("  M  b.txt", Op.STAGE);
        until(() -> git.index("c.txt") != null);
        // c.txt is staged first, then a.txt's hunk.
        until(() -> SIXTY.replace("line 50\n", "line fifty\n").equals(git.index("a.txt")));
        until(() -> status.picks().isEmpty());
    }

    @Test
    public void aSelectionInAHunkPicksItsLines() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\ntwo\nthree\n");
        git.commitAll("first");
        git.write("a.txt", "one\nTWO\nthree\nfour\n");
        open();
        openFile("  M  a.txt", "  +four");
        onEdt(() -> {
            h.editor().setMark(new Position(line("  +four"), 0));
            h.editor().setDot(line("  +four"), 4);
            VcsStatusMode.vcsStatusPick(h.editor());
            assertEquals(org.armedbear.j.util.PickState.ALL, status.picks().state(line("  +four")));
            assertEquals(org.armedbear.j.util.PickState.NONE, status.picks().state(line("  +TWO")));
            assertEquals(org.armedbear.j.util.PickState.SOME, status.picks().state(line("  M  a.txt")));
            // A picked line's background brighter than one's not picked.
            assertNotEquals(
                status.getFormatter().getLineBackground(line("  +four")),
                status.getFormatter().getLineBackground(line("  +TWO")));
            h.editor().setDot(status.getFirstLine(), 0);
        });
        onEdt(() -> StatusActions.run(h.editor(), Op.STAGE));
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("one\ntwo\nthree\nfour\n", git.index("a.txt"));
    }

    @Test
    public void aPickedRemovalStagesWithItsAddition() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\ntwo\nthree\n");
        git.commitAll("first");
        git.write("a.txt", "one\nTWO\nthree\n");
        open();
        openFile("  M  a.txt", "  +TWO");
        onEdt(() -> {
            h.editor().setMark(new Position(line("  -two"), 0));
            h.editor().setDot(line("  -two"), 4);
            VcsStatusMode.vcsStatusPick(h.editor());
            h.editor().setDot(status.getFirstLine(), 0);
        });
        onEdt(() -> StatusActions.run(h.editor(), Op.STAGE));
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("one\nTWO\nthree\n", git.index("a.txt"), "the modified line staged whole");
    }

    @Test
    public void aFileSelectedWithItsHunksIsPickedOnce() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        final Runnable selectFileAndHunk = () -> {
            h.editor().setMark(new Position(line("  M  a.txt"), 0));
            h.editor().setDot(after(line("  M  a.txt"), "  @@ -47"), 3);
            VcsStatusMode.vcsStatusPick(h.editor());
        };
        onEdt(() -> {
            selectFileAndHunk.run();
            assertEquals("a.txt", StatusActions.names(StatusActions.targets(status, h.editor())));
        });
        // Unpicked the same way, none of its hunks stays picked.
        onEdt(() -> {
            selectFileAndHunk.run();
            assertTrue(status.picks().isEmpty());
        });
    }

    @Test
    public void aSelectionOfALineBreakAloneLeavesThePicksTaken() throws Exception {
        repository();
        open();
        pick("  ?  c.txt");
        onEdt(() -> {
            final org.armedbear.j.Line b = line("  M  b.txt");
            h.editor().setMark(new Position(b, b.length()));
            h.editor().setDot(b.next(), 0);
            assertEquals("c.txt", StatusActions.names(StatusActions.targets(status, h.editor())));
        });
    }

    @Test
    public void aPickTakenAnotherWayIsDroppedWithNothingSaid() throws Exception {
        repository();
        open();
        pick("  ?  c.txt");
        onEdt(() -> {
            h.editor().setMark(new Position(line("  ?  c.txt"), 0));
            h.editor().setDot(line("  ?  c.txt"), 3);
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> status.picks().isEmpty());
        assertFalse(String.valueOf(h.status()).contains("dropped"), h.status());
    }

    // Repositories a and b under tmp, a.txt in a and b.txt in b each
    // changed, line 50 of a's, in a status of both: the second repository's.
    private GitFixture twoRepositories() throws Exception {
        git = GitFixture.init(Files.createDirectories(tmp.resolve("a")));
        git.write("a.txt", SIXTY);
        git.commitAll("first");
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n"));
        final GitFixture b = GitFixture.init(Files.createDirectories(tmp.resolve("b")));
        b.write("b.txt", "b\n");
        b.commitAll("first");
        b.write("b.txt", "b changed\n");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository ra = Repositories.forDirectory(git.root);
        final VcsRepository rb = Repositories.forDirectory(b.root);
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(ra, rb));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> text().contains("  M  a.txt") && text().contains("  M  b.txt"));
        return b;
    }

    @Test
    public void picksInTwoRepositoriesAreStagedInEach() throws Exception {
        final GitFixture b = twoRepositories();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -47");
        pick("  M  b.txt");
        onEdt(() -> h.editor().setDot(status.getFirstLine(), 0));
        onEdt(() -> StatusActions.run(h.editor(), Op.STAGE));
        until(() -> "b changed\n".equals(b.index("b.txt")));
        until(() -> SIXTY.replace("line 50\n", "line fifty\n").equals(git.index("a.txt")));
        until(() -> status.picks().isEmpty());
    }

    @Test
    public void picksInTwoRepositoriesAreStashedInEach() throws Exception {
        final GitFixture b = twoRepositories();
        git.write("u.txt", "untracked\n");
        onEdt(() -> {
            Repositories.forDirectory(git.root).invalidate();
            status.refresh();
        });
        until(() -> text().contains("  ?  u.txt"));
        pick("  M  a.txt");
        pick("  ?  u.txt");
        pick("  M  b.txt");
        onEdt(() -> {
            h.editor().setDot(status.getFirstLine(), 0);
            assertEquals("Stash the 3 picked files", StashCommands.popupOf(h.editor()).groups().get(1).label());
            StashCommands.vcsStashSave(h.editor());
        });
        until(() -> !git.run("stash", "list").out().isEmpty() && !b.run("stash", "list").out().isEmpty());
        assertEquals(SIXTY, git.read("a.txt"));
        assertFalse(Files.exists(git.root.resolve("u.txt")), "the untracked file picked stashed too");
        assertEquals("b\n", b.read("b.txt"));
        until(() -> status.picks().isEmpty());
    }

    @Test
    public void aStashTakesTheSelectedFilesBeforeThePicks() throws Exception {
        repository();
        open();
        pick("  M  a.txt");
        pick("  M  b.txt");
        onEdt(() -> {
            final org.armedbear.j.Line a = line("  M  a.txt");
            h.editor().setMark(new Position(a, 0));
            h.editor().setDot(a, a.length());
            assertEquals("Stash the selected files", StashCommands.popupOf(h.editor()).groups().get(1).label());
            StashCommands.vcsStashSave(h.editor());
        });
        until(() -> !git.run("stash", "list").out().isEmpty());
        assertEquals(SIXTY, git.read("a.txt"));
        assertEquals("b changed\n", git.read("b.txt"), "the picked, not selected, left");
        onEdt(() -> assertFalse(status.picks().isEmpty(), "the picks kept"));
    }

    @Test
    public void aHunkPickedWholeHasOneBoxAndALineDownThroughItsChanges() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(() -> {
            final StatusPickColumn col = (StatusPickColumn) status.getGutterColumns()
                    .stream()
                    .filter(c -> c instanceof StatusPickColumn)
                    .findFirst()
                    .orElseThrow();
            final org.armedbear.j.Line hunk = after(line("  M  a.txt"), "  @@ -47");
            final org.armedbear.j.Line removed = after(line("  M  a.txt"), "  -line 50");
            final org.armedbear.j.Line added = after(line("  M  a.txt"), "  +line fifty");
            assertEquals(org.armedbear.j.util.PickState.ALL, col.state(hunk));
            assertNull(col.state(removed), "its lines' boxes gone");
            assertEquals(org.armedbear.j.util.PickColumn.Trail.FROM, col.trail(hunk));
            assertEquals(org.armedbear.j.util.PickColumn.Trail.THROUGH, col.trail(removed));
            assertEquals(org.armedbear.j.util.PickColumn.Trail.TO, col.trail(added));
            assertNull(col.trail(added.next()), "not past its last change");
        });
    }

    // Runs body with changelist names asked for and chosen answered with name.
    private void answering(String name, ThrowingRunnable body) throws Exception {
        final BranchCommands.Ask ask = ChangelistCommands.ask;
        final BranchCommands.Choose choose = BranchCommands.choose;
        ChangelistCommands.ask = (e, prompt, title, offered) -> name;
        BranchCommands.choose = (e, q, history, names, unavailable, chosen) -> {
            offeredUnavailable = unavailable;
            chosen.accept(name);
        };
        try {
            body.run();
        }
        finally {
            ChangelistCommands.ask = ask;
            BranchCommands.choose = choose;
        }
    }

    // The index of the first line text is, after from's; -1 for none.
    private int indexAfter(int from, String text) {
        final List<String> lines = text();
        for (int i = from + 1; i < lines.size(); i++)
            if (lines.get(i).equals(text) || lines.get(i).startsWith(text) && text.contains("@@"))
                return i;
        return -1;
    }

    @Test
    public void aFileMovedToAChangelistIsUnderItsHeadingWhichStagesIt() throws Exception {
        repository();
        open();
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("  M  a.txt"), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  Feature (0)"));
            assertTrue(text().contains("Changelists (1)"), "in Unstaged's place");
            assertTrue(text().contains("  Default (1)  active"));
            onEdt(() -> {
                h.editor().setDot(line("    M  a.txt"), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            until(() -> text().contains("  Feature (1)"));
            assertTrue(text().contains("  Default (0)  active"));
            assertTrue(indexAfter(text().indexOf("  Feature (1)"), "    M  a.txt") > 0, "under its heading");
            // Its diff read first: kept by its changes, not whole.
            final org.armedbear.j.vcs.changelists.Changelists.Assignment kept =
                    org.armedbear.j.vcs.changelists.ChangelistStore.of(repo()).read().assignment("a.txt");
            assertNull(kept.whole());
            assertEquals(2, kept.changes().size());
            // s on the heading stages its files.
            onEdt(() -> {
                h.editor().setDot(line("  Feature (1)"), 0);
                StatusActions.run(h.editor(), Op.STAGE);
            });
            until(() -> String.valueOf(git.index("a.txt")).contains("line fifty"));
        });
    }

    @Test
    public void aHunkMovedSplitsItsFileAcrossTheLists() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("  M  a.txt"), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  Feature (0)"));
            onEdt(() -> {
                h.editor().setDot(after(line("    M  a.txt"), "    @@ -47"), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            until(() -> text().contains("  Feature (1)") && text().contains("  Default (1)  active"));
            // Under each, the file, with its hunk there alone.
            final int feature = text().indexOf("  Feature (1)");
            final int defaults = text().indexOf("  Default (1)  active");
            assertTrue(indexAfter(defaults, "    @@ -1,") > 0);
            assertTrue(indexAfter(defaults, "    @@ -47") > feature, "not under Default");
            assertTrue(indexAfter(feature, "    @@ -47") > feature);
            // Feature's heading stages line 50's change, not line 3's.
            onEdt(() -> {
                h.editor().setDot(line("  Feature (1)"), 0);
                StatusActions.run(h.editor(), Op.STAGE);
            });
            until(() -> String.valueOf(git.index("a.txt")).contains("line fifty"));
            assertTrue(git.index("a.txt").contains("line 3\n"), "Default's change not staged");
        });
    }

    @Test
    public void aHunkSplitAcrossListsShowsWholeTheOthersLinesDimmed() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 6\n", "line six\n"));
        open();
        openFile("  M  a.txt", "  -line 6");
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("  M  a.txt"), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  Feature (0)"));
            // Line 6's change alone, selected, moved.
            onEdt(() -> {
                final StatusLine six = after(line("    M  a.txt"), "    +line six");
                h.editor().setMark(new Position(six, 0));
                h.editor().setDot(six, six.length());
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            until(() -> text().contains("  Feature (1)") && text().contains("  Default (1)  active"));
            onEdt(() -> {
                final StatusLine feature = line("  Feature (1)");
                final StatusLine three = after(feature, "    +line three");
                final StatusLine six = after(feature, "    +line six");
                assertTrue(three.isForeign(), "Default's line dimmed under Feature");
                assertFalse(six.isForeign());
                assertFalse(StatusPicks.pickable(three));
                // The hunk under Feature stages its lines there alone.
                h.editor().setDot(after(feature, "    @@ -1,"), 0);
                StatusActions.run(h.editor(), Op.STAGE);
            });
            until(() -> String.valueOf(git.index("a.txt")).contains("line six"));
            assertTrue(git.index("a.txt").contains("line 3\n"), "Default's line not staged");
        });
    }

    // a.txt's line 50's hunk moved to Feature, a.txt split across Default and Feature.
    private void splitAcrossLists() throws Exception {
        openFile("  M  a.txt", "  -line 50");
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            ChangelistCommands.vcsChangelistNew(h.editor());
        });
        until(() -> text().contains("  Feature (0)"));
        onEdt(() -> {
            h.editor().setDot(after(line("    M  a.txt"), "    @@ -47"), 0);
            ChangelistCommands.vcsChangelistMove(h.editor());
        });
        until(() -> text().contains("  Feature (1)") && text().contains("  Default (1)  active"));
    }

    @Test
    public void aSplitFilesLineUnderAListKeepsItsKeyAndItsListIsItsParent() throws Exception {
        repository();
        open();
        answering("Feature", () -> {
            splitAcrossLists();
            onEdt(() -> {
                final StatusLine under = after(line("  Feature (1)"), "    M  a.txt");
                final String key = under.key();
                assertEquals(line("  Feature (1)"), status.parent(under), "its list's heading");
                // Default closed: the file under Feature keyed as it was.
                status.toggle(line("  Default (1)  active"));
                assertEquals(key, after(line("  Feature (1)"), "    M  a.txt").key());
                assertEquals(
                    StatusRenderer.fileKey(repo(), StatusRenderer.UNSTAGED, "a.txt"),
                    StatusRenderer.first(key));
                // A path that starts as the mark might look is its own.
                assertEquals("r|unstaged|~notes.txt", StatusRenderer.first("r|unstaged|~notes.txt"));
            });
        });
    }

    @Test
    public void aFileUnderAClosedListIsRevealed() throws Exception {
        repository();
        open();
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("  M  a.txt"), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  Feature (0)"));
            onEdt(() -> {
                h.editor().setDot(line("    M  a.txt"), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            until(() -> text().contains("  Feature (1)"));
            onEdt(() -> {
                status.toggle(line("  Feature (1)"));
                assertFalse(text().contains("    M  a.txt"));
                final String fk = StatusRenderer.fileKey(repo(), StatusRenderer.UNSTAGED, "a.txt");
                status.reveal(null, StatusRenderer.sectionKey(repo(), StatusRenderer.UNSTAGED), fk);
                assertEquals("    M  a.txt", h.editor().getDotLine().getText());
            });
        });
    }

    @Test
    public void aRemovedLineAloneMovesItsChange() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\nb\nc\nd\n");
        git.commitAll("first");
        git.write("a.txt", "A\nd\n");
        open();
        openFile("  M  a.txt", "  -c");
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("  M  a.txt"), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  Feature (0)"));
            // c, removed with no line added for it, selected alone.
            onEdt(() -> {
                final StatusLine c = after(line("    M  a.txt"), "    -c");
                h.editor().setMark(new Position(c, 0));
                h.editor().setDot(c, c.length());
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            until(() -> text().contains("  Feature (1)") && text().contains("  Default (0)  active"));
        });
    }

    @Test
    public void changelistsTakeTheWorkTreesChangesWithWhitespaceIgnored() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 40\n", "line  40\n"));
        open();
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("  M  a.txt"), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  Feature (0)"));
            try {
                onEdt(() -> DiffWhitespace.choose(Whitespace.AMOUNT));
                onEdt(() -> status.toggle(line("    M  a.txt")));
                // The whitespace-only change shown, as the lists take it.
                until(() -> text().contains("    -line 40"));
            }
            finally {
                onEdt(() -> DiffWhitespace.choose(Whitespace.NONE));
            }
        });
    }

    // The lists the last changelist prompt dimmed, and why.
    private java.util.Map<String, String> offeredUnavailable;

    @Test
    public void eachChangelistPromptDimsWhatItCantTake() throws Exception {
        repository();
        open();
        // Only the default: nothing to delete, rename or make active.
        onEdt(() -> {
            h.editor().setDot(status.getFirstLine(), 0);
            ChangelistCommands.vcsChangelistDelete(h.editor());
            assertEquals("Only Default, which stays: n makes a list", h.status());
            ChangelistCommands.vcsChangelistActive(h.editor());
            assertEquals("Only Default, which is active: n makes a list", h.status());
        });
        makeList("Feature");
        answering("Feature", () -> {
            // Feature offered, and its delete declined.
            answer = false;
            onEdt(() -> {
                h.editor().setDot(status.getFirstLine(), 0);
                ChangelistCommands.vcsChangelistDelete(h.editor());
            });
            answer = true;
            assertEquals(java.util.Map.of(Changelists.DEFAULT, "always kept"), offeredUnavailable);
            onEdt(() -> {
                h.editor().setDot(status.getFirstLine(), 0);
                ChangelistCommands.vcsChangelistActive(h.editor());
            });
            assertEquals(java.util.Map.of(Changelists.DEFAULT, "active"), offeredUnavailable);
        });
        // Feature active now, a.txt, in no list, in it.
        until(() -> text().contains("  Feature (1)  active"));
        answering("Feature", () -> {
            // A file in Feature: Feature dimmed, and chosen anyway, refused.
            onEdt(() -> {
                h.editor().setDot(line("    M  a.txt"), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            // Its diff read first, the move by its changes.
            until(() -> offeredUnavailable.containsKey("Feature"));
            assertEquals(java.util.Map.of("Feature", "already here"), offeredUnavailable);
            until(() -> "Feature: already here".equals(h.status()));
            onEdt(() -> {
                h.editor().setDot(line("  Default (0)"), 0);
                ChangelistCommands.vcsChangelistRename(h.editor());
                assertEquals("Default: can't be renamed", h.status());
            });
        });
    }

    @Test
    public void aMoveUnsavedInAFileOutlastsAListMade() throws Exception {
        repository();
        open();
        makeList("Feature");
        final Buffer file = showFile();
        untilTracked(file);
        // An edit unsaved, then line fifty's change moved.
        onEdt(() -> {
            h.editor().setDot(file.getLine(10), 0);
            h.editor().insertString("x");
        });
        until(() -> file.getSignsUpdater().tracker().listOf(file.getLine(10)) != null);
        answering("Feature", () -> onEdt(() -> {
            h.editor().setDot(file.getLine(49), 0);
            ChangelistCommands.vcsChangelistMove(h.editor());
            assertEquals("Moved to Feature, kept as the file is saved", h.status());
        }));
        // Another list made: the move is still the file's.
        makeList("Other");
        Thread.sleep(300);
        onEdt(() -> {
            assertEquals("Feature", file.getSignsUpdater().tracker().listOf(file.getLine(49)));
            // Feature deleted: the change the default's, unsaved still.
        });
        answering("Feature", () -> onEdt(() -> {
            h.editor().setDot(line("  Feature (0)"), 0);
            ChangelistCommands.vcsChangelistDelete(h.editor());
        }));
        onEdt(() -> {
            assertEquals(Changelists.DEFAULT, file.getSignsUpdater().tracker().listOf(file.getLine(49)));
            file.kill();
        });
    }

    @Test
    public void unchangedLinesSelectedInAFileMoveNothingAndMakeNoList() throws Exception {
        repository();
        open();
        makeList("Feature");
        final Buffer file = showFile();
        untilTracked(file);
        offeredUnavailable = null;
        answering("Scratch", () -> onEdt(() -> {
            h.editor().setMark(new Position(file.getLine(10), 0));
            h.editor().setDot(file.getLine(12), 0);
            ChangelistCommands.vcsChangelistMove(h.editor());
            assertEquals("No change here to move", h.status());
        }));
        onEdt(() -> {
            assertNull(offeredUnavailable, "no prompt");
            assertFalse(text().contains("  Scratch (0)"));
            file.kill();
        });
    }

    @Test
    public void aMoveNotKeptSaysSo() throws Exception {
        repository();
        open();
        makeList("Feature");
        final Buffer file = showFile();
        untilTracked(file);
        final java.nio.file.Path j = git.root.resolve(".git/j");
        try {
            // The store's directory not writable, nor its file read again.
            assertTrue(j.toFile().setWritable(false));
            answering("Feature", () -> onEdt(() -> {
                h.editor().setDot(file.getLine(49), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
                assertEquals("Couldn't keep the changelists: see the log", h.status());
                h.clearLoggedErrors();
            }));
        }
        finally {
            j.toFile().setWritable(true);
            onEdt(() -> file.kill());
        }
    }

    @Test
    public void aBinaryFileMovesWhole() throws Exception {
        repository();
        git.write("bin.dat", "a\u0000b\n");
        git.commitAll("binary");
        git.write("bin.dat", "a\u0000c\n");
        open();
        openFile("  M  bin.dat", "  Binary file");
        makeList("Feature");
        makeList("Other");
        for (String to : List.of("Feature", "Other"))
            answering(to, () -> {
                onEdt(() -> {
                    h.editor().setDot(line("    M  bin.dat"), 0);
                    ChangelistCommands.vcsChangelistMove(h.editor());
                });
                until(() -> indexAfter(text().indexOf("  " + to + " (1)"), "    M  bin.dat") > 0);
            });
    }

    @Test
    public void changelistsOffRefuseTheirCommands() throws Exception {
        repository();
        open();
        try {
            Editor.preferences().setProperty(org.armedbear.j.Property.VCS_CHANGELISTS, "false");
            onEdt(() -> {
                h.editor().setDot(status.getFirstLine(), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
                assertEquals("Changelists are off: vcsChangelists is false", h.status());
            });
        }
        finally {
            Editor.preferences().removeProperty(org.armedbear.j.Property.VCS_CHANGELISTS.key());
        }
        onEdt(
            () -> assertEquals(
                "vcsChangelistPopup",
                status.getMode().getVisualKeyMap().lookup('C', 0, 0).getCommand(),
                "C in vim's visual mode too"));
    }

    @Test
    public void anotherListsChangesAreOutlinedAndTheStatusBarNamesTheList() throws Exception {
        repository();
        open();
        makeList("Feature");
        final Buffer file = showFile();
        untilTracked(file);
        answering("Feature", () -> onEdt(() -> {
            h.editor().setDot(file.getLine(49), 0);
            ChangelistCommands.vcsChangelistMove(h.editor());
        }));
        until(() -> file.getSigns().get(file.getLine(49)).other());
        onEdt(() -> {
            assertEquals("Feature", file.getSigns().get(file.getLine(49)).otherList(), "which list, outlined apart");
            assertFalse(file.getSigns().get(file.getLine(2)).other(), "the active list's, solid");
            h.editor().setDot(file.getLine(49), 0);
            assertTrue(file.getStatusText(h.editor()).contains("M · Feature"), file.getStatusText(h.editor()));
            h.editor().setDot(file.getLine(10), 0);
            assertFalse(file.getStatusText(h.editor()).contains("·"), "no change there");
        });
        // Typed in: still Feature's; elsewhere: the active list's.
        onEdt(() -> {
            h.editor().setDot(file.getLine(49), 0);
            h.editor().insertString("!");
            h.editor().setDot(file.getLine(20), 0);
            h.editor().insertString("x");
        });
        until(() -> file.getSigns().get(file.getLine(20)) != null);
        onEdt(() -> {
            assertTrue(file.getSigns().get(file.getLine(49)).other());
            assertFalse(file.getSigns().get(file.getLine(20)).other());
            file.save();
        });
        until(() -> !file.isModified() && file.getSigns().get(file.getLine(49)).other());
        // Feature made active: its change solid, the others outlined.
        answering("Feature", () -> onEdt(() -> {
            h.editor().setDot(status.getFirstLine(), 0);
            ChangelistCommands.vcsChangelistActive(h.editor());
        }));
        until(() -> !file.getSigns().get(file.getLine(49)).other() && file.getSigns().get(file.getLine(2)).other());
        onEdt(() -> file.kill());
    }

    @Test
    public void theHunkPreviewMovesWhereThereAreLists() throws Exception {
        repository();
        open();
        makeList("Feature");
        final Buffer file = showFile();
        untilTracked(file);
        onEdt(() -> {
            h.editor().setDot(file.getLine(49), 0);
            org.armedbear.j.vcs.signs.HunkCommands.previewHunk(h.editor());
        });
        until(() -> org.armedbear.j.vcs.signs.HunkPreview.showing(h.editor()));
        onEdt(() -> {
            assertEquals("vcsChangelistMenu", h.editor().getCaretPopup().getKeyMap().lookup('m', 0, 0).getCommand());
            // Its menu's lists: the change's own dimmed, the rest offered.
            final org.armedbear.j.vcs.signs.SignsUpdater u = file.getSignsUpdater();
            final java.util.Set<org.armedbear.j.Line> lines =
                    org.armedbear.j.vcs.signs.ChangelistMenu.linesAt(h.editor(), u.tracker());
            assertEquals(
                java.util.Set.of(Changelists.DEFAULT, "Feature"),
                org.armedbear.j.vcs.signs.ChangelistMenu.choices(u, lines).keySet());
            assertEquals(
                "already here",
                org.armedbear.j.vcs.signs.ChangelistMenu.choices(u, lines).get(Changelists.DEFAULT));
            assertNull(org.armedbear.j.vcs.signs.ChangelistMenu.choices(u, lines).get("Feature"));
            assertEquals(
                "Moved to Feature",
                org.armedbear.j.vcs.signs.ChangelistMenu.move(h.editor(), u, lines, "Feature"));
            // The status bar names it, a link.
            assertEquals("Feature", file.getStatusLink(h.editor()));
            h.editor().closeCaretPopup();
            file.kill();
        });
    }

    @Test
    public void aMoveWaitingOnDiffsWhoseCaretMovedOnDoesNothing() throws Exception {
        repository();
        open();
        makeList("Feature");
        answering("Feature", () -> {
            offeredUnavailable = null;
            onEdt(() -> {
                h.editor().setDot(line("    M  a.txt"), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
                assertEquals("Reading the diffs to move…", h.status());
                // Moved on before the read.
                h.editor().setDot(line("  M  b.txt"), 0);
            });
            until(() -> "Moved on while the diffs were read: move again".equals(h.status()));
            assertNull(offeredUnavailable, "no prompt");
        });
    }

    // Until file's changes are placed in their lists, line fifty's among them.
    private void untilTracked(Buffer file) throws Exception {
        until(
            () -> file.getSignsUpdater() != null
                    && file.getSignsUpdater().tracker() != null
                    && file.getSignsUpdater().tracker().listOf(file.getLine(49)) != null);
    }

    // A changelist made, named name, from the status.
    private void makeList(String name) throws Exception {
        answering(name, () -> {
            onEdt(() -> {
                h.editor().setDot(status.getFirstLine(), 0);
                ChangelistCommands.vcsChangelistNew(h.editor());
            });
            until(() -> text().contains("  " + name + " (0)"));
        });
    }

    @Test
    public void aChangelistIsRenamedAndDeletedItsChangesToTheDefault() throws Exception {
        repository();
        open();
        makeList("Feature");
        answering("Feature", () -> {
            onEdt(() -> {
                h.editor().setDot(line("    M  a.txt"), 0);
                ChangelistCommands.vcsChangelistMove(h.editor());
            });
            until(() -> text().contains("  Feature (1)"));
            onEdt(() -> {
                h.editor().setDot(line("  Feature (1)"), 0);
                ChangelistCommands.vcsChangelistActive(h.editor());
            });
            until(() -> text().contains("  Feature (1)  active"));
        });
        // Renamed at its heading: its file and the active list with it.
        answering("Fix", () -> onEdt(() -> {
            h.editor().setDot(line("  Feature (1)  active"), 0);
            ChangelistCommands.vcsChangelistRename(h.editor());
        }));
        until(() -> text().contains("  Fix (1)  active"));
        onEdt(() -> {
            h.editor().setDot(line("  Default (0)"), 0);
            ChangelistCommands.vcsChangelistRename(h.editor());
            assertEquals("Default: can't be renamed", h.status());
        });
        // Deleted, after asking: its file the default's, which is active then.
        onEdt(() -> {
            h.editor().setDot(line("  Fix (1)  active"), 0);
            ChangelistCommands.vcsChangelistDelete(h.editor());
        });
        until(() -> !text().contains("Changelists (1)"));
        assertTrue(text().contains("Unstaged changes (1)"), "the default alone: Unstaged again");
        assertTrue(asked.stream().anyMatch(q -> q.startsWith("Delete changelist Fix?")), asked.toString());
    }

    @Test
    public void theChangelistPopupIsOnC() throws Exception {
        repository();
        open();
        onEdt(() -> {
            assertEquals("vcsChangelistPopup", status.getKeyMapForMode().lookup('C', 0, 0).getCommand());
            assertEquals("vcsChangelistPopup", status.getModalKeyMapForMode().lookup('C', 0, 0).getCommand());
            final List<String> commands = new ArrayList<>();
            for (org.armedbear.j.transients.Transient.Group g : ChangelistCommands.popup().groups())
                for (org.armedbear.j.transients.Transient.Item i : g.items())
                    if (i instanceof org.armedbear.j.transients.Transient.Command c)
                        commands.add(c.command());
            assertEquals(
                List.of(
                    "vcsChangelistMove",
                    "vcsChangelistActive",
                    "vcsChangelistNew",
                    "vcsChangelistRename",
                    "vcsChangelistDelete",
                    "vcsCommitList"),
                commands);
        });
    }

    @Test
    public void theChangeAtTheCaretInAFileIsMovedToAList() throws Exception {
        repository();
        open();
        makeList("Feature");
        final Buffer file = showFile();
        until(
            () -> file.getSignsUpdater() != null
                    && file.getSignsUpdater().tracker() != null
                    && Changelists.DEFAULT.equals(file.getSignsUpdater().tracker().listOf(file.getLine(49))));
        answering("Feature", () -> onEdt(() -> {
            h.editor().setDot(file.getLine(49), 0);
            ChangelistCommands.vcsChangelistMove(h.editor());
        }));
        onEdt(() -> {
            assertEquals("Feature", file.getSignsUpdater().tracker().listOf(file.getLine(49)));
            assertEquals(Changelists.DEFAULT, file.getSignsUpdater().tracker().listOf(file.getLine(2)));
        });
        // Unmodified: kept at once, the status showing it.
        until(() -> text().contains("  Feature (1)") && text().contains("  Default (1)  active"));
        onEdt(() -> file.kill());
    }

    @Test
    public void linesAreMovedOutOfAHunkSplitAcrossLists() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 6\n", "line six\n"));
        open();
        openFile("  M  a.txt", "  -line 6");
        makeList("Feature");
        makeList("Other");
        answering("Feature", () -> onEdt(() -> {
            final StatusLine six = after(line("    M  a.txt"), "    +line six");
            h.editor().setMark(new Position(six, 0));
            h.editor().setDot(six, six.length());
            ChangelistCommands.vcsChangelistMove(h.editor());
        }));
        until(() -> text().contains("  Feature (1)"));
        // Line 3's change, under Default, to Other: each list one change.
        answering("Other", () -> onEdt(() -> {
            final StatusLine three = after(line("  Default (1)  active"), "    +line three");
            h.editor().setMark(new Position(three, 0));
            h.editor().setDot(three, three.length());
            ChangelistCommands.vcsChangelistMove(h.editor());
        }));
        until(() -> text().contains("  Default (0)  active") && text().contains("  Other (1)"));
        onEdt(() -> {
            assertTrue(after(line("  Feature (1)"), "    +line three").isForeign());
            assertFalse(after(line("  Other (1)"), "    +line three").isForeign());
            assertTrue(after(line("  Other (1)"), "    +line six").isForeign());
        });
    }

    @Test
    public void aStashRefusesHunksPicked() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(() -> {
            h.editor().setDot(status.getFirstLine(), 0);
            assertEquals(
                "Stash: hunks or lines are picked, a stash takes whole files",
                StashCommands.popupOf(h.editor()).groups().get(1).label());
            StashCommands.vcsStashSave(h.editor());
        });
        assertEquals("A stash takes whole files: hunks or lines are picked", h.status());
        assertTrue(git.run("stash", "list").out().isEmpty());
    }

    @Test
    public void aHunkIsPickedFromItsFilesBufferAndItsSignShowsIt() throws Exception {
        repository();
        open();
        final Buffer[] file = new Buffer[1];
        onEdt(() -> {
            file[0] = Editor.getBuffer(org.armedbear.j.File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file[0].initialized())
                file[0].initialize();
            file[0].load();
            h.showBuffer(file[0]);
        });
        until(() -> file[0].getSigns() != null && file[0].getSigns().get(file[0].getLine(49)) != null);
        onEdt(() -> {
            h.editor().setDot(file[0].getLine(49), 0);
            HunkCommands.vcsPickHunk(h.editor());
        });
        until(() -> "Picked a change of a.txt".equals(h.status()));
        onEdt(() -> assertEquals("a hunk of a.txt", StatusActions.names(status.picks().targets())));
        until(() -> file[0].getSigns().get(file[0].getLine(49)).picked());
        onEdt(() -> assertFalse(file[0].getSigns().get(file[0].getLine(2)).picked(), "line 3's hunk not picked"));
        // A column of boxes while something of the file is picked: checked,
        // and the hunk not picked empty.
        onEdt(() -> {
            final EditorPickColumn col = editorPicks(file[0]);
            assertEquals(org.armedbear.j.util.PickState.ALL, state(col, file[0].getLine(49)));
            assertEquals(org.armedbear.j.util.PickState.NONE, state(col, file[0].getLine(2)));
            assertNull(state(col, file[0].getLine(20)), "no change there, no box");
            // A collapsed fold's line has a box for the changes it hides:
            // both hunks, one picked.
            for (int n = 1; n < 55; n++)
                file[0].getLine(n).hide();
            assertEquals(org.armedbear.j.util.PickState.SOME, state(col, file[0].getLine(0)));
            col.clicked(h.editor(), file[0].getLine(0), 0, 0);
            assertEquals(org.armedbear.j.util.PickState.ALL, state(col, file[0].getLine(0)), "a click picks them all");
            col.clicked(h.editor(), file[0].getLine(0), 0, 0);
            for (int n = 1; n < 55; n++)
                file[0].getLine(n).show();
        });
        until(() -> editorPicks(file[0]) == null);
        onEdt(() -> assertTrue(status.picks().isEmpty(), "unpicked all, the column gone"));
        // Picked again, below.
        onEdt(() -> HunkCommands.vcsPickHunk(h.editor()));
        until(() -> file[0].getSigns().get(file[0].getLine(49)).picked());
        // Again, unpicked.
        onEdt(() -> HunkCommands.vcsPickHunk(h.editor()));
        until(() -> !file[0].getSigns().get(file[0].getLine(49)).picked());
        onEdt(() -> file[0].kill());
    }

    @Test
    public void aSelectionInAFilesBufferPicksTheLinesItCovers() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 50\n", "line fifty\n").replace("line 51\n", "line fifty-one\n"));
        open();
        final Buffer[] file = new Buffer[1];
        onEdt(() -> {
            file[0] = Editor.getBuffer(org.armedbear.j.File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file[0].initialized())
                file[0].initialize();
            file[0].load();
            h.showBuffer(file[0]);
        });
        until(() -> file[0].getSigns() != null && file[0].getSigns().get(file[0].getLine(50)) != null);
        onEdt(() -> {
            // Line 51 alone, of the hunk changing 50 and 51.
            h.editor().setMark(new Position(file[0].getLine(50), 0));
            h.editor().setDot(file[0].getLine(50), 4);
            HunkCommands.vcsPickHunk(h.editor());
        });
        until(() -> "Picked lines of a.txt".equals(h.status()));
        onEdt(() -> {
            assertNull(h.editor().getMark(), "the selection gone");
            assertTrue(file[0].getSigns().get(file[0].getLine(50)).picked());
            assertFalse(file[0].getSigns().get(file[0].getLine(49)).picked(), "line 50 not picked");
        });
        until(() -> editorPicks(file[0]) != null);
        onEdt(() -> {
            // A box at each sign of the hunk picked in part.
            final EditorPickColumn col = editorPicks(file[0]);
            assertEquals(org.armedbear.j.util.PickState.NONE, state(col, file[0].getLine(49)));
            assertEquals(org.armedbear.j.util.PickState.ALL, state(col, file[0].getLine(50)));
            assertNull(state(col, file[0].getLine(48)));
            // The hunk picked: its removals' rows are its changed lines', not the line above.
            h.editor().setMark(null);
            h.editor().setDot(file[0].getLine(49), 0);
            HunkCommands.vcsPickHunk(h.editor());
        });
        until(() -> file[0].getSigns().get(file[0].getLine(49)).picked());
        onEdt(() -> {
            final java.util.function.IntPredicate picked = status.model().pickedRows(repo(), "a.txt");
            assertTrue(picked.test(49) && picked.test(50));
            assertFalse(picked.test(48), "the line above not picked");
            final EditorPickColumn col = editorPicks(file[0]);
            assertEquals(org.armedbear.j.util.PickState.ALL, state(col, file[0].getLine(49)));
            assertNull(state(col, file[0].getLine(50)), "the hunk's box alone");
            // The status gone, its picks stay; unpicked, the column and marks go.
            status.dispose();
            assertNotNull(editorPicks(file[0]));
            status.model().unpickAll();
            assertNull(editorPicks(file[0]));
            assertFalse(file[0].getSigns().get(file[0].getLine(49)).picked());
        });
        onEdt(() -> file[0].kill());
    }

    // a.txt's buffer, loaded and shown.
    private Buffer showFile() throws Exception {
        final Buffer[] file = new Buffer[1];
        onEdt(() -> {
            file[0] = Editor.getBuffer(org.armedbear.j.File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file[0].initialized())
                file[0].initialize();
            file[0].load();
            h.showBuffer(file[0]);
        });
        return file[0];
    }

    @Test
    public void aFilePickedWholeHasItsBoxesAndThePreviewSaysSo() throws Exception {
        repository();
        open();
        pick("  M  a.txt");
        final Buffer file = showFile();
        // Its diff read for its buffer, though closed in the status.
        until(() -> editorPicks(file) != null && state(editorPicks(file), file.getLine(49)) != null);
        onEdt(() -> {
            assertEquals(org.armedbear.j.util.PickState.ALL, state(editorPicks(file), file.getLine(49)));
            assertEquals(org.armedbear.j.util.PickState.ALL, file.getSignsUpdater().pickStateAt(49));
            // Edited: the column stays, so the text doesn't move, its boxes gone.
            h.editor().setDot(file.getLine(0), 0);
            h.editor().insertString("x");
        });
        until(() -> !file.getSigns().get(file.getLine(49)).picked());
        onEdt(() -> {
            assertNotNull(editorPicks(file));
            assertNull(state(editorPicks(file), file.getLine(49)));
            assertEquals(org.armedbear.j.util.PickState.NONE, file.getSignsUpdater().pickStateAt(49));
            file.kill();
        });
    }

    @Test
    public void aFilesPicksShowAgainAfterASaveThatChangesNothing() throws Exception {
        repository();
        open();
        pick("  M  a.txt");
        final Buffer file = showFile();
        until(() -> file.getSigns() != null && file.getSigns().get(file.getLine(49)).picked());
        // Edited and back, and saved: the read after finds nothing new.
        onEdt(() -> {
            h.editor().setDot(file.getLine(0), 0);
            h.editor().insertString("x");
            org.armedbear.j.EditCommands.backspace(h.editor());
            file.save();
            assertTrue(status.savedSinceRead(file), "saved");
        });
        until(() -> !status.savedSinceRead(file));
        until(() -> file.getSigns().get(file.getLine(49)).picked());
        // A file's time ahead of the clock is no save.
        onEdt(() -> {
            file.setLastModified(System.currentTimeMillis() + 86_400_000L);
            assertFalse(status.savedSinceRead(file));
            file.kill();
        });
    }

    @Test
    public void aRemovalPickedAloneShadesNoAddedLine() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\nb\nc\nd\n");
        git.commitAll("first");
        git.write("a.txt", "A\nd\n");
        open();
        openFile("  M  a.txt", "  -c");
        // c alone, selected, with no line added for it: its sign is A's,
        // but A not picked.
        onEdt(() -> {
            final org.armedbear.j.Line c = after(line("  M  a.txt"), "  -c");
            h.editor().setMark(new Position(c, 0));
            h.editor().setDot(c, 2);
            VcsStatusMode.vcsStatusPick(h.editor());
        });
        until(() -> status.model().pickedRows(repo(), "a.txt") != null);
        onEdt(() -> assertFalse(status.model().pickedRows(repo(), "a.txt").test(0)));
    }

    @Test
    public void aPickFromAFileSavedSinceTheReadWaitsForTheNext() throws Exception {
        repository();
        open();
        final Buffer file = showFile();
        until(() -> file.getSigns() != null && file.getSigns().get(file.getLine(49)) != null);
        // Five lines above, saved, and line fifty, now row 54, picked at once.
        onEdt(() -> {
            h.editor().setDot(file.getLine(0), 0);
            h.editor().insertString("a\nb\nc\nd\ne\n");
            file.save();
            h.editor().setDot(file.getLine(54), 0);
            HunkCommands.vcsPickHunk(h.editor());
        });
        until(() -> "Picked a change of a.txt".equals(h.status()));
        onEdt(() -> {
            final java.util.function.IntPredicate picked = status.model().pickedRows(repo(), "a.txt");
            assertTrue(picked.test(54), "line fifty's change");
            assertFalse(picked.test(7), "not line three's");
            file.kill();
        });
    }

    @Test
    public void picksFromAFileWaitingOnTheStatusAreEachTaken() throws Exception {
        repository();
        open();
        final Buffer file = showFile();
        until(() -> file.getSigns() != null && file.getSigns().get(file.getLine(49)) != null);
        // a.txt's diff unread: both wait on the read.
        onEdt(() -> {
            h.editor().setDot(file.getLine(2), 0);
            HunkCommands.vcsPickHunk(h.editor());
            h.editor().setDot(file.getLine(49), 0);
            HunkCommands.vcsPickHunk(h.editor());
        });
        until(() -> {
            final java.util.function.IntPredicate picked = status.model().pickedRows(repo(), "a.txt");
            return picked != null && picked.test(2) && picked.test(49);
        });
        onEdt(() -> file.kill());
    }

    @Test
    public void aFilesSignsAreGitsWhileTheStatusHasItsDiff() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "  y\n{\n{\n{\n  y\n");
        git.commitAll("first");
        // One { of three gone: git's diff says the second, j's the third.
        git.write("a.txt", "  y\n{\n{\n  y\n");
        open();
        final Buffer[] file = new Buffer[1];
        onEdt(() -> {
            file[0] = Editor.getBuffer(org.armedbear.j.File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file[0].initialized())
                file[0].initialize();
            file[0].load();
            h.showBuffer(file[0]);
        });
        until(() -> file[0].getSigns() != null && file[0].getSigns().get(file[0].getLine(2)) != null);
        onEdt(() -> {
            assertNull(file[0].getSigns().get(file[0].getLine(1)), "j's own, below the third");
            h.editor().setDot(file[0].getLine(2), 0);
            HunkCommands.vcsPickHunk(h.editor());
        });
        until(() -> file[0].getSigns().get(file[0].getLine(1)) != null);
        onEdt(() -> {
            final org.armedbear.j.Signs.Sign sign = file[0].getSigns().get(file[0].getLine(1));
            assertEquals(org.armedbear.j.Signs.Kind.DELETED, sign.kind());
            assertTrue(sign.picked());
            assertNull(file[0].getSigns().get(file[0].getLine(2)), "git's, below the second, where its box is");
            assertEquals(org.armedbear.j.util.PickState.ALL, state(editorPicks(file[0]), file[0].getLine(1)));
            // The status gone and nothing picked, the signs are j's own again.
            status.dispose();
            status.model().unpickAll();
        });
        until(() -> file[0].getSigns().get(file[0].getLine(2)) != null);
        onEdt(() -> {
            assertNull(file[0].getSigns().get(file[0].getLine(1)));
            file[0].kill();
        });
    }

    // file's column of picks, or null.
    private static EditorPickColumn editorPicks(Buffer file) {
        for (org.armedbear.j.GutterColumn c : file.getGutterColumns())
            if (c instanceof EditorPickColumn e)
                return e;
        return null;
    }

    // What col's box at line shows, or null for none; as painted, with
    // something picked.
    private static org.armedbear.j.util.PickState state(EditorPickColumn col, org.armedbear.j.Line line) {
        return col.isClickable(line, 0, 0) ? col.state(line) : null;
    }

    @Test
    public void aStashsFilesAreNotPicked() throws Exception {
        repository();
        open();
        onEdt(
            () -> assertFalse(
                StatusPicks.pickable(new StatusLine(Kind.FILE, "x", "k", "k", repo(), StatusData.STASHES))));
    }

    @Test
    public void aHunkPickedInAClosedFileIsStillTaken() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> !text().contains("  -line 50"));
        onEdt(() -> status.refresh());
        until(() -> !text().stream().anyMatch(t -> t.startsWith("Reading")));
        onEdt(() -> assertEquals("a hunk of a.txt", StatusActions.names(StatusActions.targets(status, h.editor()))));
    }

    @Test
    public void pickingAgainUnpicksAndAHunkOfAPickedFileSplitsIt() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pick("  M  a.txt");
        onEdt(
            () -> assertEquals(
                org.armedbear.j.util.PickState.ALL,
                status.picks().state(after(line("  M  a.txt"), "  @@ -47"))));
        // Unpick one hunk: the file's other stays picked.
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(() -> {
            assertEquals(org.armedbear.j.util.PickState.SOME, status.picks().state(line("  M  a.txt")));
            assertEquals(
                org.armedbear.j.util.PickState.ALL,
                status.picks().state(after(line("  M  a.txt"), "  @@ -1,6")));
            assertEquals(
                org.armedbear.j.util.PickState.NONE,
                status.picks().state(after(line("  M  a.txt"), "  @@ -47")));
            assertTrue(
                status.getVirtualText()
                        .get("vcs.status.picks")
                        .stream()
                        .anyMatch(v -> org.armedbear.j.VirtualText.text(v.rows().get(0)).contains("1/2")),
                "1/2 after the file");
        });
        pickAfter("  M  a.txt", "  @@ -1,6");
        onEdt(() -> assertTrue(status.picks().isEmpty(), "picked again: unpicked"));
    }

    @Test
    public void everyHunkPickedIsTheFilePicked() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -1,6");
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(() -> {
            assertEquals(org.armedbear.j.util.PickState.ALL, status.picks().state(line("  M  a.txt")));
            assertTrue(status.getVirtualText().get("vcs.status.picks").isEmpty(), "no count on a file picked whole");
        });
        // Picked again, the file unpicks all of it.
        pick("  M  a.txt");
        onEdt(() -> assertTrue(status.picks().isEmpty()));
    }

    @Test
    public void ctrlSemicolonUnpicksAll() throws Exception {
        repository();
        open();
        pick("  M  a.txt");
        onEdt(() -> {
            assertEquals(
                "vcsStatusUnpickAll",
                status.getMode()
                        .getKeyMap()
                        .lookup('\0', KeyEvent.VK_SEMICOLON, org.armedbear.j.Constants.CTRL_MASK)
                        .getCommand());
            VcsStatusMode.vcsStatusUnpickAll(h.editor());
            assertTrue(status.picks().isEmpty());
        });
    }

    @Test
    public void aHunkIsPickedInTheDiffBeside() throws Exception {
        repository();
        open();
        final StatusDiffBuffer diff = paneOn("  M  a.txt", "  @@ -47");
        onEdt(() -> {
            assertTrue(
                diff.getGutterColumns().stream().anyMatch(c -> c instanceof StatusPickColumn),
                "the pane has picks");
            h.editor().setDot(paneLine(diff, "  @@ -47"), 0);
            VcsStatusMode.vcsStatusPick(h.editor());
            assertEquals(org.armedbear.j.util.PickState.ALL, status.picks().state(paneLine(diff, "  @@ -47")));
            assertEquals(org.armedbear.j.util.PickState.SOME, status.picks().state(line("  M  a.txt")));
        });
    }

    @Test
    public void aHunkPickedBesideAFileClosedHereIsTaken() throws Exception {
        repository();
        open();
        final StatusDiffBuffer diff = paneOn("  M  a.txt", "  @@ -47");
        onEdt(() -> {
            h.editor().setDot(paneLine(diff, "  @@ -47"), 0);
            VcsStatusMode.vcsStatusPick(h.editor());
        });
        // The file closed in the status: its diff read for the pick.
        until(() -> !status.picks().unread(status.model()));
        onEdt(() -> assertEquals("a hunk of a.txt", StatusActions.names(StatusActions.targets(status, h.editor()))));
    }

    @Test
    public void picksStayWhenTheWriteFails() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -47");
        Files.writeString(git.root.resolve(".git/index.lock"), "");
        try {
            act("  M  b.txt", Op.STAGE);
            until(() -> !status.isWriting());
            until(() -> !text().stream().anyMatch(t -> t.startsWith("Reading")));
            onEdt(() -> assertFalse(status.picks().isEmpty(), "kept to try again"));
        }
        finally {
            Files.delete(git.root.resolve(".git/index.lock"));
        }
    }

    @Test
    public void enterOnADiffLineVisitsItWithSomethingPicked() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pick("  ?  c.txt");
        onEdt(() -> {
            h.editor().setDot(after(line("  M  a.txt"), "  +line fifty"), 0);
            VcsStatusMode.vcsStatusVisit(h.editor());
            assertNull(
                Editor.getBufferList()
                        .findBuffer(org.armedbear.j.File.getInstance(git.root.resolve("c.txt").toString())),
                "not the picked file");
        });
    }

    @Test
    public void aShiftClickPicksFromTheLastClickOfItsKind() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        onEdt(() -> {
            final StatusPickColumn col = (StatusPickColumn) status.getGutterColumns()
                    .stream()
                    .filter(c -> c instanceof StatusPickColumn)
                    .findFirst()
                    .orElseThrow();
            // Files, untracked, unstaged then staged: a.txt between is
            // picked whole, its hunks not apart.
            col.clicked(h.editor(), line("  ?  c.txt"), 0, 0);
            col.shiftClicked(h.editor(), line("  M  b.txt"), 0, 0);
            for (String f : List.of("  ?  c.txt", "  M  a.txt", "  M  b.txt"))
                assertEquals(org.armedbear.j.util.PickState.ALL, status.picks().state(line(f)), f);
            // Unpicked, a Shift click unpicks them back to it.
            col.clicked(h.editor(), line("  M  b.txt"), 0, 0);
            col.shiftClicked(h.editor(), line("  ?  c.txt"), 0, 0);
            assertTrue(status.picks().isEmpty());
            // A line: - with the + made for it, the hunk's every change, so
            // the hunk is picked, its lines' boxes giving way to its own.
            col.clicked(h.editor(), after(line("  M  a.txt"), "  -line 50"), 0, 0);
            assertNull(col.state(after(line("  M  a.txt"), "  +line fifty")));
            assertEquals(
                org.armedbear.j.util.PickState.ALL,
                status.picks().state(after(line("  M  a.txt"), "  @@ -47")));
            assertEquals(
                org.armedbear.j.util.PickState.NONE,
                status.picks().state(after(line("  M  a.txt"), "  @@ -1,6")));
        });
    }

    @Test
    public void unpickingHunksNotPickedPicksNothing() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        onEdt(() -> {
            final StatusPickColumn col = (StatusPickColumn) status.getGutterColumns()
                    .stream()
                    .filter(c -> c instanceof StatusPickColumn)
                    .findFirst()
                    .orElseThrow();
            final StatusLine first = after(line("  M  a.txt"), "  @@ -1,6");
            col.clicked(h.editor(), first, 0, 0);
            col.clicked(h.editor(), first, 0, 0);
            // From a hunk unpicked, a Shift click unpicks: none of the file is picked.
            col.shiftClicked(h.editor(), after(line("  M  a.txt"), "  @@ -47"), 0, 0);
            assertTrue(status.picks().isEmpty());
        });
    }

    @Test
    public void aPickIsDroppedWhenATwinOfItsHunkComes() throws Exception {
        final StringBuilder thirty = new StringBuilder();
        for (int i = 1; i <= 30; i++)
            thirty.append(i == 5 || i == 25 ? "same" : "line " + i).append('\n');
        git = GitFixture.init(tmp);
        git.write("a.txt", thirty.toString());
        git.commitAll("first");
        final String late = thirty.toString().replaceFirst("(?s)(.*)same", "$1other");
        git.write("a.txt", late);
        open();
        openFile("  M  a.txt", "  +other");
        pickAfter("  M  a.txt", "  @@ -22");
        onEdt(() -> assertFalse(status.picks().isEmpty()));
        // The same change above it: its id would now be the new one's.
        git.write("a.txt", late.replaceFirst("same", "other"));
        onEdt(() -> status.refresh());
        until(() -> status.picks().isEmpty());
        until(() -> h.status().equals("A pick was dropped: The number of identical hunks has changed"));
    }

    @Test
    public void aSelectionComesBeforeWhatIsPicked() throws Exception {
        repository();
        open();
        pick("  ?  c.txt");
        onEdt(() -> {
            h.editor().setMark(new Position(line("  M  a.txt"), 0));
            h.editor().setDot(line("  M  a.txt"), 4);
            assertEquals("a.txt", StatusActions.names(StatusActions.targets(status, h.editor())));
            h.editor().setMark(null);
            assertEquals("c.txt", StatusActions.names(StatusActions.targets(status, h.editor())));
        });
    }

    @Test
    public void theGutterTakesNoClickWhileNothingIsPicked() throws Exception {
        repository();
        open();
        onEdt(() -> {
            final StatusPickColumn col = (StatusPickColumn) status.getGutterColumns()
                    .stream()
                    .filter(c -> c instanceof StatusPickColumn)
                    .findFirst()
                    .orElseThrow();
            assertFalse(col.isClickable(line("  M  a.txt"), 0, 0), "a click there moves the caret");
        });
        pick("  ?  c.txt");
        onEdt(() -> {
            final StatusPickColumn col = (StatusPickColumn) status.getGutterColumns()
                    .stream()
                    .filter(c -> c instanceof StatusPickColumn)
                    .findFirst()
                    .orElseThrow();
            assertTrue(col.isClickable(line("  M  a.txt"), 0, 0));
        });
    }

    @Test
    public void aPickSurvivesARefreshAndGoesWithItsHunk() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  -line 50");
        pickAfter("  M  a.txt", "  @@ -47");
        onEdt(() -> status.refresh());
        until(() -> !text().stream().anyMatch(t -> t.startsWith("Reading")));
        onEdt(() -> assertFalse(status.picks().isEmpty(), "kept over a refresh"));
        // Its change changed: the pick is dropped, and said so.
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line FIFTY\n"));
        onEdt(() -> status.refresh());
        until(() -> status.picks().isEmpty());
        until(() -> h.status().equals("A pick was dropped: The selected hunk has changed: select it again"));
    }

    @Test
    public void enterOpensEachPickedFile() throws Exception {
        repository();
        open();
        pick("  M  a.txt");
        pick("  ?  c.txt");
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            VcsStatusMode.vcsStatusVisit(h.editor());
        });
        onEdt(() -> {
            assertNotNull(
                Editor.getBufferList()
                        .findBuffer(org.armedbear.j.File.getInstance(git.root.resolve("a.txt").toString())));
            assertNotNull(
                Editor.getBufferList()
                        .findBuffer(org.armedbear.j.File.getInstance(git.root.resolve("c.txt").toString())));
        });
    }

    @Test
    public void enterOffAPickedLineVisitsAsUsual() throws Exception {
        repository();
        open();
        pick("  M  a.txt");
        onEdt(() -> {
            h.editor().setDot(line("  ?  c.txt"), 0);
            VcsStatusMode.vcsStatusVisit(h.editor());
            assertNull(
                Editor.getBufferList()
                        .findBuffer(org.armedbear.j.File.getInstance(git.root.resolve("a.txt").toString())),
                "not the picked file");
            assertNotNull(
                Editor.getBufferList()
                        .findBuffer(org.armedbear.j.File.getInstance(git.root.resolve("c.txt").toString())),
                "the file at the caret");
        });
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

    // a.txt with a both-modified conflict, plus an unstaged b.txt to mix with.
    private void conflicted() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "base\n");
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "theirs\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
        git.write("b.txt", "b changed\n");
    }

    @Test
    public void uOnAConflictStagedResolvedPutsTheConflictBack() throws Exception {
        conflicted();
        git.write("a.txt", "resolved\n");
        git.git("add", "a.txt");
        open();
        act("  M  a.txt", Op.UNSTAGE);
        until(() -> text().contains("  U  a.txt (both modified)"));
        assertEquals("resolved\n", git.read("a.txt"), "the file as it was");
    }

    @Test
    public void uLeavesAnEarlierMergesConflictBehind() throws Exception {
        // git keeps the committed merge's record of a.txt's conflict; an
        // am stopped since is in progress, but it is not this one's.
        conflicted();
        git.write("a.txt", "resolved\n");
        git.git("add", "a.txt");
        git.git("commit", "-q", "--no-edit");
        git.write("bad.patch", """
                               From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001
                               From: T <t@example.com>
                               Subject: [PATCH] bad

                               ---
                               diff --git a/c.txt b/c.txt
                               --- a/c.txt
                               +++ b/c.txt
                               @@ -1 +1 @@
                               -not there
                               +x
                               """);
        assertFalse(git.run("am", "bad.patch").succeeded(), "am stops");
        git.write("a.txt", "edited\n");
        git.git("add", "a.txt");
        open();
        act("  M  a.txt", Op.UNSTAGE);
        until(() -> git.run("diff", "--cached", "--name-only").out().isEmpty());
        assertTrue(git.run("ls-files", "-u").out().isEmpty(), "no conflict brought back");
    }

    @Test
    public void uOnAnotherFileInAMergeStillUnstagesIt() throws Exception {
        conflicted();
        git.git("add", "b.txt");
        open();
        act("  M  b.txt", Op.UNSTAGE);
        until(() -> text().contains("Unstaged changes (1)"));
        assertTrue(text().contains("  U  a.txt (both modified)"), "the conflict as it was");
    }

    @Test
    public void xOnAnUnmergedFileOpensTheResolvePopup() throws Exception {
        conflicted();
        open();
        act("  U  a.txt (both modified)", Op.DISCARD);
        assertTrue(asked.isEmpty(), "no discard question: " + asked);
        final TransientPopup popup = TransientPopup.getOpen();
        assertNotNull(popup, "the resolve popup opened");
        assertNotNull(popup.getTransient().item("o"), "offers ours");
        onEdt(() -> TransientPopup.getOpen().close(false));
    }

    @Test
    public void sOnAnUnmergedFileCountsTheMarkersLeft() throws Exception {
        conflicted();
        open();
        answer = false;
        act("  U  a.txt (both modified)", Op.STAGE);
        until(() -> !asked.isEmpty());
        assertEquals(1, asked.size(), asked.toString());
        assertEquals("Mark a.txt resolved? 1 conflict is left.", asked.get(0));
        // With the markers gone, the count drops off.
        asked.clear();
        git.write("a.txt", "resolved\n");
        act("  U  a.txt (both modified)", Op.STAGE);
        until(() -> !asked.isEmpty());
        assertEquals("Mark a.txt resolved?", asked.get(0));
    }

    @Test
    public void xOnAMixOfUnmergedAndOtherIsRefused() throws Exception {
        conflicted();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  U  a.txt (both modified)"), 0);
            h.editor().setMark(new Position(line("  M  b.txt"), 0));
            StatusActions.run(h.editor(), Op.DISCARD);
        });
        assertEquals("Resolve the conflicts on their own", h.status());
        assertTrue(asked.isEmpty(), "no discard question: " + asked);
    }

    @Test
    public void sCountsTheMarkersInAFileNotUtf8() throws Exception {
        conflicted();
        Files.write(
            git.root.resolve("a.txt"),
            "<<<<<<< HEAD\ncaf\u00e9\n=======\nx\n>>>>>>> other\n".getBytes(StandardCharsets.ISO_8859_1));
        open();
        answer = false;
        act("  U  a.txt (both modified)", Op.STAGE);
        until(() -> !asked.isEmpty());
        assertEquals("Mark a.txt resolved? 1 conflict is left.", asked.get(0));
    }

    @Test
    public void sOnAConflictAndAnotherFileNamesOnlyTheConflict() throws Exception {
        conflicted();
        open();
        answer = false;
        onEdt(() -> {
            h.editor().setDot(line("  U  a.txt (both modified)"), 0);
            h.editor().setMark(new Position(line("  M  b.txt"), 0));
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> !asked.isEmpty());
        assertEquals(List.of("Mark a.txt resolved? 1 conflict is left."), asked);
    }

    @Test
    public void aConflictCommandRefusesAMixAsXDoes() throws Exception {
        conflicted();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  U  a.txt (both modified)"), 0);
            h.editor().setMark(new Position(line("  M  b.txt"), 0));
            ResolveCommands.vcsResolveOurs(h.editor());
        });
        assertEquals("Resolve the conflicts on their own", h.status());
        assertTrue(git.read("a.txt").contains("<<<<<<<"), "a.txt left as it was");
    }

    @Test
    public void aResolveTakesThePickedConflictsAndLeavesTheRestPicked() throws Exception {
        conflicted();
        open();
        pick("  U  a.txt (both modified)");
        pick("  M  b.txt");
        onEdt(() -> {
            h.editor().setDot(status.getFirstLine(), 0);
            ResolveCommands.vcsResolveOurs(h.editor());
        });
        until(() -> !text().contains("  U  a.txt (both modified)"));
        assertEquals("ours\n", git.read("a.txt"));
        onEdt(() -> assertEquals("b.txt", StatusActions.names(StatusActions.targets(status, h.editor()))));
    }

    @Test
    public void aResolveWithNoConflictPickedSaysSo() throws Exception {
        conflicted();
        open();
        pick("  M  b.txt");
        onEdt(() -> {
            h.editor().setDot(line("  U  a.txt (both modified)"), 0);
            ResolveCommands.vcsResolveOurs(h.editor());
        });
        assertEquals("No conflict is picked", h.status());
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
        open();
        until(() -> text().contains("Staged changes (1)"));
        final Target.Files[] target = new Target.Files[1];
        onEdt(() -> target[0] = new Target.Files(repo(), "staged", status.files(repo(), "staged")));
        final Target.Files t = target[0];
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

    // ---- markers in what s stages, the file not unmerged

    private static final String MARKERS = "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> other\n";

    private boolean staged(String path) {
        return git.run("diff", "--cached", "--name-only").out().lines().anyMatch(path::equals);
    }

    @Test
    public void sAsksBeforeStagingAddedMarkers() throws Exception {
        repository();
        git.write("a.txt", SIXTY + MARKERS);
        open();
        answer = false;
        act("  M  a.txt", Op.STAGE);
        until(() -> !asked.isEmpty());
        assertEquals(List.of("Stage a.txt? 1 conflict is left."), asked);
        assertFalse(staged("a.txt"), "not without a yes");
        answer = true;
        asked.clear();
        act("  M  a.txt", Op.STAGE);
        until(() -> staged("a.txt"));
    }

    @Test
    public void sStagesMarkersAlreadyCommittedWithoutAsking() throws Exception {
        // A file that holds markers as its content: the change adds none.
        git = GitFixture.init(tmp);
        git.write("a.txt", MARKERS);
        git.commitAll("first");
        git.write("a.txt", MARKERS + "more\n");
        open();
        act("  M  a.txt", Op.STAGE);
        until(() -> staged("a.txt"));
        assertTrue(asked.isEmpty(), asked.toString());
    }

    @Test
    public void sAsksBeforeStagingANewFileWithMarkers() throws Exception {
        repository();
        git.write("c.txt", MARKERS);
        open();
        answer = false;
        act("  ?  c.txt", Op.STAGE);
        until(() -> !asked.isEmpty());
        assertEquals(List.of("Stage c.txt? 1 conflict is left."), asked);
    }

    @Test
    public void sAsksBeforeStagingAHunkThatAddsMarkers() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 50\n", MARKERS));
        open();
        openFile("  M  a.txt", "  +theirs");
        answer = false;
        onEdt(() -> {
            h.editor().setDot(after(line("  M  a.txt"), "  @@ -47"), 0);
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> !asked.isEmpty());
        assertEquals(List.of("Stage a hunk of a.txt? 1 conflict is left."), asked);
        assertFalse(staged("a.txt"), "not without a yes");
    }

    @Test
    public void stageAllAsksWithAddedMarkers() throws Exception {
        repository();
        git.write("a.txt", SIXTY + MARKERS);
        open();
        answer = false;
        onEdt(() -> StatusActions.all(h.editor(), true));
        until(() -> !asked.isEmpty());
        assertEquals(List.of("Stage every change? 1 conflict is left."), asked);
        assertFalse(staged("a.txt"), "not without a yes");
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
            final List<Target> t = StatusActions.targets(status, h.editor());
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
        // Resolution may need a fresh diff before asking about the discard.
        until(() -> !asked.isEmpty());
        assertEquals(1, asked.size(), "asked first; status=" + h.status());
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

    @Test
    public void aPickIsDroppedWhenChangedTextHasTheSameJavaHash() throws Exception {
        repository();
        git.write("a.txt", SIXTY.replace("line 3\n", "Aa\n"));
        open();
        openFile("  M  a.txt", "  +Aa");
        pickAfter("  M  a.txt", "  @@ -1,");
        git.write("a.txt", SIXTY.replace("line 3\n", "BB\n"));
        onEdt(status::refresh);
        until(() -> text().contains("  +BB"));
        onEdt(() -> assertTrue(status.picks().isEmpty()));
        assertTrue(String.valueOf(h.status()).contains("pick was dropped"), h.status());
    }

    @Test
    public void aPickIsDroppedWhenOnlyItsFinalNewlineChanges() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "old\n");
        git.commitAll("first");
        git.write("a.txt", "new\n");
        open();
        openFile("  M  a.txt", "  +new");
        pickAfter("  M  a.txt", "  @@ -1");
        git.write("a.txt", "new");
        onEdt(status::refresh);
        until(
            () -> status.model()
                    .unstaged(repo(), "a.txt")
                    .diff()
                    .hunks()
                    .stream()
                    .flatMap(h -> h.lines().stream())
                    .anyMatch(l -> l.noNewline()));
        onEdt(() -> assertTrue(status.picks().isEmpty()));
    }

    @Test
    public void aPickedLinesMeaningSurvivesContextChangesAfterAnotherCommit() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "old\nkeep\n");
        git.commitAll("first");
        git.write("a.txt", "NEW\nextra\nkeep\n");
        open();
        openFile("  M  a.txt", "  +extra");
        onEdt(() -> status.pick(List.of(line("  +extra"))));
        // A separate change committed above the picked addition supplies
        // more context without changing the hunk's removed/added bytes.
        git.write("a.txt", "context\nold\nkeep\n");
        git.commitAll("context above");
        git.write("a.txt", "context\nNEW\nextra\nkeep\n");
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("   context"));
        onEdt(() -> {
            assertFalse(status.picks().isEmpty());
            StatusActions.run(h.editor(), Op.STAGE);
        });
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("context\nold\nextra\nkeep\n", git.index("a.txt"));
    }

    @Test
    public void picksOfLiteralPathsContainingRenderingSeparatorsAreConsumed() throws Exception {
        git = GitFixture.init(tmp);
        String name = "file|@node#1.txt";
        git.write(name, "old\n");
        git.commitAll("first");
        git.write(name, "new\n");
        open();
        openFile("  M  " + name, "  +new");
        pickAfter("  M  " + name, "  @@ -1");
        onEdt(() -> StatusActions.run(h.editor(), Op.STAGE));
        until(() -> text().contains("Staged changes (1)"));
        assertEquals("new\n", git.index(name));
        onEdt(() -> assertTrue(status.picks().isEmpty()));
    }

    @Test
    public void oneStaleHunkPreventsStagingAnyOfTheRequestedTargets() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  +line three");
        final List<Target> captured = new ArrayList<>();
        onEdt(
            () -> captured.addAll(
                StatusActions.targetsOf(status.model(), List.of(line("  +line three"), line("  ?  c.txt")))));
        git.write("a.txt", SIXTY.replace("line 3\n", "revised three\n"));
        onEdt(status::refresh);
        until(() -> text().contains("  +revised three"));
        onEdt(() -> {
            StatusActions.runOn(h.editor(), status.model(), Op.STAGE, captured, Map.of(), false);
            assertFalse(status.isWriting());
            assertTrue(h.status().contains("selected hunk has changed"), h.status());
        });
        assertEquals(SIXTY, git.index("a.txt"));
        assertEquals("b.txt", git.git("diff", "--cached", "--name-only").strip());
    }

    private Target.Part capturedUnopenedHunk() {
        final var repository = repo();
        final FileItem unread = status.model().unstaged(repository, "a.txt");
        assertEquals(null, unread.diff());
        final var diff = repository.backend().readDiff(repository, false, List.of("a.txt"), Whitespace.NONE).get(0);
        final FileItem captured = new FileItem(unread.change(), unread.letter(), unread.staged(), diff);
        return Target.Part.of(repository, "unstaged", captured, diff.hunks().get(0), null);
    }

    @Test
    public void resolvingAnUnreadSelectionReadsTheDiffAndReturnsCurrentTargets() throws Exception {
        repository();
        open();
        final AtomicReference<Selections.Resolution> result = new AtomicReference<>();
        onEdt(() -> status.model().resolveTargets(List.of(capturedUnopenedHunk()), result::set));
        until(() -> result.get() != null);
        onEdt(() -> {
            assertTrue(result.get().ready(), result.get().failure());
            final Target.Part resolved = (Target.Part) result.get().targets().get(0);
            assertSame(status.model().unstaged(repo(), "a.txt"), resolved.file());
        });
    }

    @Test
    public void resolvingAFailedDiffReadStopsAndReportsTheFailure() throws Exception {
        repository();
        open();
        final Target.Part[] captured = new Target.Part[1];
        onEdt(() -> captured[0] = capturedUnopenedHunk());
        git.git("config", "diff.algorithm", "invalid-algorithm");
        final AtomicReference<Selections.Resolution> result = new AtomicReference<>();
        final int[] reads = { 0 };
        final RepositoryStatus.Listener listener = new RepositoryStatus.Listener() {
            @Override
            public void refreshed() {
                reads[0]++;
            }

            @Override
            public void picked() {}

            @Override
            public void read(int dropped, RepositoryStatus.Drop why) {}
        };
        onEdt(() -> {
            status.model().hold(result, listener);
            status.model().resolveTargets(List.of(captured[0]), result::set);
        });
        until(() -> result.get() != null);
        onEdt(() -> {
            assertFalse(result.get().ready());
            assertTrue(result.get().unread().isEmpty());
            assertEquals(Selections.Reason.READ_FAILED, result.get().problems().get(0).reason());
            assertTrue(reads[0] <= 2, "bounded reads: " + reads[0]);
            status.model().release(result, listener);
        });
    }

    @Test
    public void anExplicitCommitRefusesACapturedHunkThatChanged() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  +line three");
        final PartialCommit[] commit = new PartialCommit[1];
        onEdt(
            () -> commit[0] = PartialCommit.ofTargets(
                status.model(),
                StatusActions.targetsOf(status.model(), List.of(line("  +line three")))));
        git.write("a.txt", SIXTY.replace("line 3\n", "revised three\n"));
        onEdt(status::refresh);
        until(() -> text().contains("  +revised three"));
        onEdt(() -> {
            final var refused =
                    org.junit.jupiter.api.Assertions.assertThrows(PartialCommit.Refused.class, () -> commit[0].plan());
            assertTrue(refused.getMessage().contains("selected hunk has changed"), refused.getMessage());
        });
    }

    @Test
    public void aMoveRefusesChangedContentEvenWhenTheHunkHeaderStillMatches() throws Exception {
        repository();
        open();
        openFile("  M  a.txt", "  +line three");
        final List<Target> captured = new ArrayList<>();
        onEdt(() -> captured.addAll(StatusActions.targetsOf(status.model(), List.of(line("  +line three")))));
        git.write("a.txt", SIXTY.replace("line 3\n", "revised three\n").replace("line 50\n", "line fifty\n"));
        onEdt(status::refresh);
        until(() -> text().contains("  +revised three"));
        final AtomicReference<String> said = new AtomicReference<>();
        onEdt(() -> ChangelistCommands.moveTargets(status.model(), captured, "Feature", said::set));
        assertTrue(said.get().contains("selected hunk has changed"), said.get());
    }

    @Test
    public void anUnreadDiscardSelectionReportsTheReadThenAsksBeforeDiscarding() throws Exception {
        repository();
        open();
        onEdt(() -> {
            StatusActions
                    .runOn(h.editor(), status.model(), Op.DISCARD, List.of(capturedUnopenedHunk()), Map.of(), false);
            assertTrue(h.status().contains("Reading the selected files' diffs"), h.status());
            assertTrue(asked.isEmpty(), "confirmation waits for the current diff");
        });
        until(() -> !asked.isEmpty() && !status.isWriting());
        assertEquals(1, asked.size());
        assertTrue(git.read("a.txt").contains("line 3\n"));
        assertTrue(git.read("a.txt").contains("line fifty\n"), "the other hunk stays");
    }

    @Test
    public void anAmbiguousPickIsDroppedWithTheResolversExplanation() throws Exception {
        git = GitFixture.init(tmp);
        final String base =
                SIXTY.replace("line 3\n", "old\n").replace("line 30\n", "old\n").replace("line 50\n", "old\n");
        git.write("a.txt", base);
        git.commitAll("first");
        git.write("a.txt", base.replaceFirst("old\n", "new\n").replaceFirst("old\n", "new\n"));
        open();
        openFile("  M  a.txt", "  +new");
        pickAfter("  M  a.txt", "  @@ -1,");
        git.write("a.txt", base.replace("old\n", "new\n"));
        onEdt(status::refresh);
        until(() -> status.model().unstaged(repo(), "a.txt").diff().hunks().size() == 3);
        onEdt(() -> {
            assertTrue(status.picks().isEmpty());
            assertTrue(h.status().contains("identical hunks"), h.status());
        });
    }

    @Test
    public void stagedAndUnstagedPicksOfOneRealPathStayDistinct() throws Exception {
        git = GitFixture.init(tmp);
        final String base = SIXTY.replace("line 3\n", "same\n").replace("line 50\n", "same\n");
        final String indexed = base.replaceFirst("same\n", "changed\n");
        final String working = indexed.replaceFirst("same\n", "changed\n");
        git.write("a.txt", base);
        git.commitAll("first");
        git.write("a.txt", indexed);
        git.git("add", "a.txt");
        git.write("a.txt", working);
        open();
        onEdt(() -> {
            status.toggle(after(line("Unstaged changes (1)"), "  M  a.txt"));
            status.toggle(after(line("Staged changes (1)"), "  M  a.txt"));
        });
        until(() -> text().stream().filter(text -> text.equals("  +changed")).count() == 2);
        onEdt(() -> {
            final StatusLine staged = after(line("Staged changes (1)"), "  @@");
            final StatusLine unstaged = after(line("Unstaged changes (1)"), "  @@");
            assertTrue(staged.file().staged());
            assertFalse(unstaged.file().staged());
            final StatusPicks picks = status.picks();
            assertEquals(
                picks.hunkId(staged.file(), staged.hunk()),
                picks.hunkId(unstaged.file(), unstaged.hunk()),
                "identical changed bytes deliberately give the same hunk id in both sections");
            status.pick(List.of(staged, unstaged));
            final List<Target> targets = picks.targets();
            assertEquals(2, targets.size());
            assertEquals(
                Set.of("staged", "unstaged"),
                targets.stream().map(Target::section).collect(java.util.stream.Collectors.toSet()));
            final Target stagedTarget =
                    targets.stream().filter(target -> target.section().equals("staged")).findFirst().orElseThrow();
            status.unpick(picks.used(targets).get(stagedTarget));
            assertEquals(org.armedbear.j.util.PickState.NONE, picks.state(staged));
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.state(unstaged));
            assertEquals(1, picks.targets().size());
            assertEquals("unstaged", picks.targets().get(0).section());
            status.pick(List.of(staged));
            status.pick(List.of(unstaged));
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.state(staged));
            assertEquals(org.armedbear.j.util.PickState.NONE, picks.state(unstaged));
        });
        assertEquals(indexed, git.index("a.txt"));
        assertEquals(working, git.read("a.txt"));
    }
}
