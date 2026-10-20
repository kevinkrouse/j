/*
 * LogBufferTest.java
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

package org.armedbear.j.vcs.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.BufferList;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Formatter;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.KeyMap;
import org.armedbear.j.KeyMapping;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.ProjectRoot;
import org.armedbear.j.Property;
import org.armedbear.j.SessionProperties;
import org.armedbear.j.TextStyle;
import org.armedbear.j.transients.FilterBar;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.TransientArgs;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.RepositoryColors;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.armedbear.j.vcs.status.VcsStatusBuffer;
import org.armedbear.j.vcs.status.VcsStatusMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LogBufferTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private VcsRepository repository;
    private EditorHarness h;
    private LogBuffer log;

    @AfterEach
    public void tearDown() throws Exception {
        // Nothing reads the repository once it is gone.
        if (log != null)
            until(() -> !log.isLoading());
        if (repository != null)
            until(() -> !LogNames.of(repository).isReading());
        onEdt(() -> {
            for (Buffer b : new Buffer[] { log == null ? null : log.diff, log == null ? null : log.files, log })
                if (b != null)
                    Editor.getBufferList().remove(b);
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
        });
        GitFixture.reset();
    }

    private void repo(int commits) throws Exception {
        git = GitFixture.init(tmp);
        for (int i = 0; i < commits; i++) {
            git.write("a.txt", "line " + i + "\n");
            git.commitAll("commit " + i);
        }
        repository = Repositories.forDirectory(git.root);
    }

    // The log in the harness's window, its first page read.
    private void open(int pageSize) throws Exception {
        h = EditorHarness.create();
        onEdt(() -> {
            log = LogBuffer.open(repository, LogQuery.ALL, pageSize);
            log.setWidth(60);
            h.showBuffer(log);
        });
        until(() -> !log.isLoading());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 400; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out");
    }

    private List<String> subjects() {
        final List<String> out = new ArrayList<>();
        for (Line l = log.getFirstLine(); l != null; l = l.next())
            out.add(l instanceof LogLine ll ? ll.commit().subject() : l.getText());
        return out;
    }

    private static List<String> texts(Buffer b) {
        final List<String> out = new ArrayList<>();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }

    @Test
    public void listsTheCommitsNewestFirstEachLaidOutForTheWidth() throws Exception {
        repo(3);
        open(100);
        assertEquals(List.of("commit 2", "commit 1", "commit 0"), subjects());
        assertEquals(
            h.editor().getDisplay().getColumns(),
            log.getFirstLine().getText().length(),
            "the window's width, past the graph");
        assertTrue(log.getFirstLine().getText().startsWith("commit 2  main "), log.getFirstLine().getText());
        assertEquals(0, h.editor().getDotLineNumber());
    }

    @Test
    public void readsTheNextPageAsTheCaretNearsTheEnd() throws Exception {
        repo(5);
        open(2);
        assertEquals(List.of("commit 4", "commit 3"), subjects());
        onEdt(() -> {
            h.editor().setDot(log.getLastLine(), 0);
            log.afterCommand(h.editor());
        });
        until(() -> log.commits().size() == 4 && !log.isLoading());
        assertEquals(List.of("commit 4", "commit 3", "commit 2", "commit 1"), subjects());
        assertEquals(1, h.editor().getDotLineNumber(), "the caret stays");
        onEdt(() -> {
            h.editor().setDot(log.getLastLine(), 0);
            log.afterCommand(h.editor());
        });
        until(() -> log.commits().size() == 5 && !log.isLoading());
        final int read = log.commits().size();
        onEdt(() -> log.afterCommand(h.editor()));
        Thread.sleep(50);
        until(() -> !log.isLoading());
        assertEquals(read, log.commits().size(), "no more after a short page");
    }

    @Test
    public void anEmptyRepositorySaysSo() throws Exception {
        git = GitFixture.init(tmp);
        repository = Repositories.forDirectory(git.root);
        open(100);
        assertEquals(List.of("git: no commits"), subjects());
    }

    @Test
    public void aNarrowerWindowLaysTheRowsOutAgain() throws Exception {
        repo(1);
        open(100);
        onEdt(() -> {
            log.setWidth(0);
            final LogLine l = (LogLine) log.getFirstLine();
            l.layout(LogRow.layout(l.commit(), 20, System.currentTimeMillis() / 1000));
        });
        assertEquals(20, log.getFirstLine().getText().length());
        assertFalse(log.getFirstLine().isModified(), "not an edit");
    }

    @Test
    public void theDetailsShowTheCommitsFilesAndTheFirstFilesDiff() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\ntwo\n");
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", "one\nTWO\n");
        git.write("c.txt", "c\n");
        git.git("commit", "-q", "-am", "Second\n\nWith a body.");
        git.git("add", "c.txt");
        git.git("commit", "-q", "--amend", "--no-edit");
        repository = Repositories.forDirectory(git.root);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.files.commit() != null && log.diff.change() != null);
        final List<String> files = texts(log.files);
        assertEquals("   main ", files.get(0), "its branch's badge");
        assertTrue(files.get(1).startsWith("Commit:   "), files.toString());
        assertTrue(files.contains("  Second"), files.toString());
        assertTrue(files.contains("  With a body."), files.toString());
        assertTrue(files.contains("  M  a.txt"), files.toString());
        assertTrue(files.contains("  A  c.txt"), files.toString());
        assertEquals("a.txt", log.diff.change().path());
        final List<String> diff = texts(log.diff);
        assertTrue(diff.contains("  -two"), diff.toString());
        assertTrue(diff.contains("  +TWO"), diff.toString());
    }

    @Test
    public void theDetailsFollowTheCaretAMomentAfterItStops() throws Exception {
        repo(2);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.files.commit() != null);
        assertEquals("commit 1", log.files.commit().subject());
        onEdt(() -> {
            h.editor().setDot(log.getLine(1), 0);
            log.afterCommand(h.editor());
        });
        assertEquals("commit 1", log.files.commit().subject(), "not at once");
        until(() -> "commit 0".equals(log.files.commit().subject()));
        assertEquals("commit 0", log.shown().subject());
    }

    @Test
    public void altDownGoesToTheNextFilesDiffTheFocusStaying() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.write("b.txt", "b\n");
        git.commitAll("first");
        repository = Repositories.forDirectory(git.root);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null);
        assertEquals("a.txt", log.diff.change().path());
        // The files in a window of their own.
        final EditorHarness files = EditorHarness.create();
        try {
            onEdt(() -> {
                files.showBuffer(log.files);
                files.editor().setDot(log.files.getFirstLine(), 0);
                files.editor().setDot(log.files.nextFile(files.editor(), true), 0);
                Editor.setCurrentEditor(h.editor());
                LogCommands.vcsLogNextFile(h.editor());
            });
            until(() -> log.diff.change() != null && log.diff.change().path().equals("b.txt"));
            assertTrue(log.files.getLine(files.editor().getDotLineNumber()).getText().endsWith("b.txt"));
            onEdt(() -> LogCommands.vcsLogPreviousFile(h.editor()));
            until(() -> log.diff.change().path().equals("a.txt"));
        }
        finally {
            onEdt(() -> {
                files.showBuffer(files.buffer());
                files.close();
            });
        }
    }

    @Test
    public void aHunkOfTheDiffFolds() throws Exception {
        repo(2);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null && log.diff.getLineCount() > 2);
        final int open = log.diff.getLineCount();
        final StatusLine hunk = hunk();
        assertNotNull(hunk);
        assertTrue(hunk.isOpen());
        onEdt(() -> assertTrue(log.diff.toggle(hunk)));
        assertFalse(hunk().isOpen());
        assertTrue(log.diff.getLineCount() < open);
    }

    private StatusLine hunk() {
        for (Line l = log.diff.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.HUNK)
                return s;
        return null;
    }

    @Test
    public void aDiffLinesLineIsItsNewSidesAndAHunksIsItsFirstChange() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n");
        git.commitAll("first");
        git.write("a.txt", "1\n2\n3\n4\n5\nsix\n7\n8\n");
        git.commitAll("second");
        repository = Repositories.forDirectory(git.root);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null && hunk() != null);
        assertEquals(6, VcsStatusMode.lineNumber(hunk()));
        for (Line l = log.diff.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.DIFF && s.getText().endsWith("+six"))
                assertEquals(6, VcsStatusMode.lineNumber(s));
    }

    @Test
    public void quittingClosesItsBuffers() throws Exception {
        repo(1);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.files.commit() != null);
        onEdt(() -> LogCommands.vcsLogQuit(h.editor()));
        assertFalse(Editor.getBufferList().contains(log));
        assertFalse(Editor.getBufferList().contains(log.files));
        assertFalse(Editor.getBufferList().contains(log.diff));
    }

    // A commit that changes lines far apart: three hunks, a long diff.
    private void threeHunks() throws Exception {
        git = GitFixture.init(tmp);
        final StringBuilder was = new StringBuilder();
        for (int i = 0; i < 300; i++)
            was.append("line ").append(i).append('\n');
        git.write("a.txt", was.toString());
        git.commitAll("first");
        git.write(
            "a.txt",
            was.toString().replace("line 10\n", "ten\n").replace("line 150\n", "x\n").replace("line 290\n", "y\n"));
        git.commitAll("second");
        repository = Repositories.forDirectory(git.root);
    }

    // The diff in a window of its own, as the full log has it.
    private EditorHarness diffWindow() throws Exception {
        final EditorHarness d = EditorHarness.create();
        onEdt(() -> {
            d.showBuffer(log.diff);
            d.editor().setDot(log.diff.getFirstLine(), 0);
            d.editor().setTopLine(log.diff.getFirstLine());
        });
        return d;
    }

    private static void close(EditorHarness d) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            d.showBuffer(d.buffer());
            d.close();
        });
    }

    @Test
    public void spaceAndShiftSpaceScrollTheDiffFromTheListInVim() throws Exception {
        threeHunks();
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null && hunk() != null);
        final EditorHarness d = diffWindow();
        try {
            onEdt(() -> {
                h.vim();
                log.setProperty(Property.EDIT_MODE, "vim");
                h.keys("<Space>");
            });
            final int down = d.editor().getTopLine().lineNumber();
            assertTrue(down > 0, "a page on");
            onEdt(() -> h.keys("<S-Space>"));
            assertEquals(0, d.editor().getTopLine().lineNumber(), "and back: Shift Space is not Space");
            onEdt(() -> h.keys("<Space><BS>"));
            assertEquals(0, d.editor().getTopLine().lineNumber(), "Backspace too, as magit's DEL");
        }
        finally {
            close(d);
        }
    }

    @Test
    public void nAndPAndBracketCGoByHunkInTheDiffAroundAtTheEnds() throws Exception {
        threeHunks();
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null && hunk() != null);
        final List<Line> hunks = StatusLine.hunks(log.diff);
        assertEquals(3, hunks.size());
        final EditorHarness d = diffWindow();
        try {
            onEdt(() -> {
                d.editor().setDot(hunks.get(0), 0);
                LogCommands.vcsLogNext(d.editor());
            });
            assertEquals(hunks.get(1), d.editor().getDotLine());
            onEdt(() -> LogCommands.vcsLogPrevious(d.editor()));
            assertEquals(hunks.get(0), d.editor().getDotLine());
            onEdt(() -> LogCommands.vcsLogPrevious(d.editor()));
            assertEquals(hunks.get(2), d.editor().getDotLine(), "before the first: around to the last");
            onEdt(() -> {
                d.vim();
                log.diff.setProperty(Property.EDIT_MODE, "vim");
                d.keys("]c");
            });
            assertEquals(hunks.get(0), d.editor().getDotLine(), "]c, past the last: around to the first");
        }
        finally {
            close(d);
        }
    }

    @Test
    public void tabOpensAndClosesAHunkAsInTheStatusBufferAndShiftTabAll() throws Exception {
        threeHunks();
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null && hunk() != null);
        final EditorHarness d = diffWindow();
        try {
            onEdt(() -> {
                d.editor().setDot(hunk(), 0);
                d.keys("<Tab>");
            });
            assertFalse(hunk().isOpen());
            onEdt(() -> d.keys("<Tab>"));
            assertTrue(hunk().isOpen());
            onEdt(() -> d.keys("<S-Tab>"));
            for (Line l : StatusLine.hunks(log.diff))
                assertFalse(((StatusLine) l).isOpen(), "all closed");
            onEdt(() -> d.keys("<S-Tab>"));
            for (Line l : StatusLine.hunks(log.diff))
                assertTrue(((StatusLine) l).isOpen(), "all open");
        }
        finally {
            close(d);
        }
    }

    @Test
    public void thePopupsKeysAreTheLogsOwn() throws Exception {
        threeHunks();
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null);
        final EditorHarness d = diffWindow();
        try {
            assertFalse(groups(h).contains("File"), "the list has no file to open");
            assertTrue(groups(d).contains("File"));
            for (boolean vim : new boolean[] { false, true }) {
                onEdt(() -> {
                    if (vim) {
                        h.vim();
                        d.vim();
                        log.setProperty(Property.EDIT_MODE, "vim");
                        log.diff.setProperty(Property.EDIT_MODE, "vim");
                    }
                });
                for (EditorHarness e : List.of(h, d)) {
                    final KeyMap km = vim
                            ? VcsLogMode.getMode().getModalKeyMap(e.editor().getBuffer())
                            : VcsLogMode.getMode().getKeyMap(e.editor().getBuffer());
                    for (Transient.Group g : VcsLogMode.getMode().getTransient(e.editor()).groups())
                        for (Transient.Item item : g.items())
                            if (item instanceof Transient.Command c) {
                                assertEquals(1, c.key().length(), c.key());
                                final KeyMapping m = km.lookup(c.key().charAt(0), 0, 0);
                                assertNotNull(m, (vim ? "vim: " : "") + c.key() + " is no key in the log");
                                assertEquals(c.command(), m.getCommand(), (vim ? "vim: " : "") + c.key());
                            }
                }
            }
        }
        finally {
            close(d);
        }
    }

    private static List<String> groups(EditorHarness e) {
        return VcsLogMode.getMode().getTransient(e.editor()).groups().stream().map(Transient.Group::label).toList();
    }

    @Test
    public void dIsRememberedForTheNextLog() throws Exception {
        repo(1);
        open(100);
        final SessionProperties session = Editor.getSessionProperties();
        final String key = "vcsLog.details";
        final String was = session.getStringProperty(key, null);
        try {
            onEdt(() -> {
                LogCommands.showFull(h.editor(), log);
                log.right = false;
                LogCommands.vcsLogToggleDetails(h.editor());
            });
            assertTrue(LogCommands.detailsAtRight(null), "D to the right, for the next log");
            onEdt(() -> LogCommands.vcsLogToggleDetails(h.editor()));
            assertFalse(LogCommands.detailsAtRight(null));
        }
        finally {
            session.setStringProperty(key, was);
        }
    }

    @Test
    public void oOpensNoFileFromTheList() throws Exception {
        repo(1);
        open(100);
        onEdt(() -> LogCommands.vcsLogOpenFile(h.editor()));
        assertEquals("No file here", h.status());
    }

    @Test
    public void killingTheLogOrAPaneClosesItAllOnce() throws Exception {
        repo(1);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.files.commit() != null);
        onEdt(() -> log.files.kill());
        assertTrue(log.isDisposed());
        assertFalse(Editor.getBufferList().contains(log));
        assertFalse(Editor.getBufferList().contains(log.files));
        assertFalse(Editor.getBufferList().contains(log.diff));
        onEdt(() -> log.close(null));
    }

    @Test
    public void aRefreshLooksForTheCaretsCommitInItsFirstPageOnly() throws Exception {
        repo(6);
        open(2);
        onEdt(() -> log.loadMore());
        until(() -> !log.isLoading() && log.commits().size() == 4);
        onEdt(() -> h.editor().setDot(log.getLine(3), 0));
        onEdt(() -> log.refresh());
        until(() -> !log.isLoading() && log.commits().size() == 2);
        assertEquals(0, h.editor().getDotLineNumber(), "not on the first page: the first commit");
        onEdt(() -> log.loadMore());
        until(() -> !log.isLoading() && log.commits().size() == 4);
        assertEquals(0, h.editor().getDotLineNumber(), "a later page leaves the caret be");
    }

    @Test
    public void foldingKeepsTheDiffsPlace() throws Exception {
        threeHunks();
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.diff.change() != null && hunk() != null);
        final EditorHarness d = diffWindow();
        try {
            final String[] keys = new String[2];
            onEdt(() -> {
                final List<Line> hunks = StatusLine.hunks(log.diff);
                keys[0] = ((StatusLine) hunks.get(1)).key();
                keys[1] = ((StatusLine) hunks.get(2)).key();
                d.editor().setTopLine(hunks.get(1));
                d.editor().setDot(hunks.get(2), 0);
                d.keys("<Tab>");
            });
            assertEquals(keys[0], ((StatusLine) d.editor().getTopLine()).key(), "Tab: the top stays");
            onEdt(() -> d.keys("<S-Tab>"));
            assertEquals(keys[0], ((StatusLine) d.editor().getTopLine()).key(), "Shift Tab: the top stays");
            assertEquals(keys[1], ((StatusLine) d.editor().getDotLine()).key(), "and the caret");
        }
        finally {
            close(d);
        }
    }

    // Two commits of a.txt, then one of Ada's adding b/c.txt.
    private void twoAuthors() throws Exception {
        repo(2);
        git.write("b/c.txt", "c\n");
        git.git("add", "-A");
        git.git("commit", "-q", "--author=Ada Lovelace <ada@example.com>", "-m", "by ada");
    }

    @Test
    public void theRefsTreeGoesToARefFiltersToItAndNarrowsAsItIsTyped() throws Exception {
        repo(6);
        git.git("branch", "old", "HEAD~4");
        git.git("tag", "v1", "HEAD~5");
        open(2);
        final LogRefTree tree = onEdtGet(() -> log.refTree(null));
        until(() -> !tree.parts().isEmpty() && !tree.parts().get(0).tips().isEmpty());
        onEdt(() -> {
            tree.setSize(200, 400);
            tree.expandRow(0);
        });
        // A click on "old", past the first page: the caret goes to it, reading on.
        onEdt(() -> click(tree, "old", 1));
        until(() -> !log.isLoading() && commitAtCaret().equals("commit 1"));
        // Enter on it: the log filtered to it.
        onEdt(() -> {
            select(tree, "old");
            key(tree, KeyEvent.VK_ENTER);
        });
        until(() -> !log.isLoading() && subjects().equals(List.of("commit 1", "commit 0")));
        assertEquals(List.of("old"), log.getQuery().revisions());
        // Typing narrows it, a shifted character too; Backspace and Escape widen it again.
        onEdt(() -> {
            tree.keyTyped(
                new KeyEvent(tree, KeyEvent.KEY_TYPED, 0, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_UNDEFINED, 'V'));
            tree.keyTyped(new KeyEvent(tree, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, '1'));
            tree.keyTyped(
                new KeyEvent(tree, KeyEvent.KEY_TYPED, 0, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_UNDEFINED, 'x'));
        });
        assertEquals("Refs: V1", tree.getLabelText());
        onEdt(() -> {
            assertEquals(List.of("Tags", "v1"), rows(tree));
            assertEquals("v1", tree.getSelectionPath().getLastPathComponent().toString(), "the first match, for Enter");
        });
        onEdt(() -> key(tree, KeyEvent.VK_BACK_SPACE));
        assertEquals("V", tree.filter());
        onEdt(() -> key(tree, KeyEvent.VK_ESCAPE));
        assertEquals("", tree.filter());
        assertEquals("Refs", tree.getLabelText());
    }

    @Test
    public void aRefTheLogDoesNotListReadsNothingAndAnAmbiguousNameIsFilteredByItsWholeName() throws Exception {
        repo(6);
        // A commit only a tag reaches, and a branch and a tag both "release".
        git.git("checkout", "-q", "--detach");
        git.write("a.txt", "tagged\n");
        git.commitAll("tagged only");
        git.git("tag", "lone");
        git.git("checkout", "-q", "main");
        git.git("branch", "release", "HEAD~2");
        git.git("tag", "release", "HEAD~4");
        open(2);
        final LogRefTree tree = onEdtGet(() -> log.refTree(null));
        until(() -> !tree.parts().isEmpty() && tree.parts().get(0).tips().size() >= 4);
        onEdt(() -> tree.setSize(200, 400));
        final int read = log.commits().size();
        onEdt(() -> {
            tree.expandRow(rows(tree).indexOf("Tags"));
            click(tree, "lone", 1);
        });
        Thread.sleep(300);
        until(() -> !log.isLoading());
        assertEquals(read, log.commits().size(), "-T is off: not listed, and no page read for it");
        onEdt(() -> {
            select(tree, "release");
            key(tree, KeyEvent.VK_ENTER);
        });
        // The branch is at commit 3, the tag at commit 1.
        until(() -> !log.isLoading() && !subjects().isEmpty() && subjects().get(0).equals("commit 3"));
        assertEquals(List.of("refs/heads/release"), log.getQuery().revisions(), "the branch, not the tag");
    }

    @Test
    public void whatIsOpenStaysOpenWhenTheRefsAreReadAgainAndMoreTagsAreShown() throws Exception {
        repo(2);
        for (int i = 0; i < LogRefTree.TAGS + 2; i++)
            git.git("tag", "t" + i);
        open(100);
        final LogRefTree tree = onEdtGet(() -> log.refTree(null));
        until(() -> !tree.parts().isEmpty() && tree.parts().get(0).tips().size() > LogRefTree.TAGS);
        onEdt(() -> {
            tree.setSize(200, 2000);
            tree.expandRow(rows(tree).indexOf("Tags"));
            select(tree, "… 2 more");
            key(tree, KeyEvent.VK_ENTER);
            assertTrue(rows(tree).contains("t0") && rows(tree).contains("t" + (LogRefTree.TAGS + 1)), "all, open");
        });
        git.git("branch", "new");
        onEdt(() -> log.refresh());
        until(() -> rows(tree).contains("new"));
        onEdt(() -> assertTrue(rows(tree).contains("t0"), "Tags still open: " + rows(tree)));
    }

    @Test
    public void withSeveralRepositoriesEachIsOpenToItsBranchesItsTagsClosed() throws Exception {
        final LogScope scope = project();
        a.git("tag", "a-v1");
        b.git("branch", "dev");
        open(scope, 100);
        final LogRefTree tree = onEdtGet(() -> log.refTree(null));
        until(() -> tree.parts().size() == 2 && !tree.parts().get(0).tips().isEmpty());
        // main, each's default and checked out, first.
        onEdt(() -> assertEquals(List.of("a", "Branches", "main", "Tags", "b", "Branches", "main", "dev"), rows(tree)));
        // Enter on b's dev: dev, in b alone.
        onEdt(() -> {
            select(tree, "dev");
            key(tree, KeyEvent.VK_ENTER);
            assertEquals(List.of("dev"), log.getQuery().revisions());
            assertEquals(List.of("b"), log.getQuery().repositories());
        });
        onEdt(() -> log.setQuery(LogQuery.ALL));
        // -o: by the newest commit first, main first all the same.
        onEdt(() -> {
            log.setRefsOrder("date");
            assertEquals(List.of("a", "Branches", "main", "Tags", "b", "Branches", "main", "dev"), rows(tree));
        });
    }

    private <T> T onEdtGet(java.util.function.Supplier<T> s) throws Exception {
        final List<T> out = new ArrayList<>();
        onEdt(() -> out.add(s.get()));
        return out.get(0);
    }

    private String commitAtCaret() {
        return h.editor().getDotLine() instanceof LogLine l ? l.commit().subject() : "";
    }

    private static List<String> rows(LogRefTree tree) {
        final List<String> out = new ArrayList<>();
        for (int row = 0; row < tree.getRowCount(); row++)
            out.add(tree.getPathForRow(row).getLastPathComponent().toString());
        return out;
    }

    private static void select(LogRefTree tree, String name) {
        for (int row = 0; row < tree.getRowCount(); row++)
            if (tree.getPathForRow(row).getLastPathComponent().toString().equals(name)) {
                tree.setSelectionRow(row);
                return;
            }
        throw new AssertionError("no " + name + " in " + rows(tree));
    }

    private static void click(LogRefTree tree, String name, int count) {
        select(tree, name);
        final java.awt.Rectangle r = tree.getRowBounds(tree.getSelectionRows()[0]);
        tree.mouseClicked(
            new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, r.x + 2, r.y + 2, count, false, MouseEvent.BUTTON1));
    }

    private static void key(LogRefTree tree, int code) {
        tree.keyPressed(new KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED));
        tree.keyReleased(new KeyEvent(tree, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED));
    }

    @Test
    public void aFilterReadsTheLogAgainAndItsChipShowsIt() throws Exception {
        twoAuthors();
        open(100);
        onEdt(() -> h.editor().updateFilterBar());
        final FilterBar bar = h.editor().getFilterBar();
        assertNotNull(bar, "the list has a filter bar");
        assertEquals(
            List.of("-b branch ▾", "-a author ▾", "-p path ▾", "-s since ▾", "-g message ▾", "-c change ▾"),
            bar.text());
        onEdt(() -> {
            LogFilters.AUTHOR.binding().set(h.editor(), "Ada");
            FilterBar.refreshAll(log);
        });
        until(() -> !log.isLoading() && subjects().equals(List.of("by ada")));
        assertTrue(bar.text().contains("-a author: Ada ▾ ×"), bar.text().toString());
        onEdt(() -> LogCommands.vcsLogClearFilters(h.editor()));
        until(() -> !log.isLoading() && log.commits().size() == 3);
        assertEquals(LogQuery.ALL, log.getQuery());
        assertEquals("-a author ▾", bar.text().get(1));
    }

    @Test
    public void aPageReadBeforeTheFilterChangedIsDropped() throws Exception {
        twoAuthors();
        h = EditorHarness.create();
        onEdt(() -> {
            log = LogBuffer.open(repository, LogQuery.ALL, 100);
            log.setWidth(60);
            h.showBuffer(log);
            log.setQuery(LogQuery.ALL.withAuthor("Ada"));
        });
        until(() -> !log.isLoading() && !log.commits().isEmpty());
        Thread.sleep(200);
        until(() -> !log.isLoading());
        assertEquals(List.of("by ada"), subjects());
    }

    @Test
    public void thePathFilterFollowsAFileButNotADirectory() throws Exception {
        twoAuthors();
        open(100);
        final LogNames names = LogNames.of(repository);
        until(() -> !names.paths().isEmpty() && !names.refs().isEmpty());
        assertEquals(List.of("a.txt", "b/", "b/c.txt"), names.paths());
        assertEquals("main", names.refs().get(0).name());
        onEdt(() -> LogFilters.PATH.binding().set(h.editor(), "b/c.txt"));
        assertTrue(log.getQuery().follow());
        until(() -> !log.isLoading());
        assertEquals(List.of("by ada"), subjects());
        onEdt(() -> LogFilters.PATH.binding().set(h.editor(), "b/"));
        assertFalse(log.getQuery().follow());
        until(() -> !log.isLoading());
        assertEquals(List.of("by ada"), subjects());
    }

    @Test
    public void aChipsClickTypesItsValueAndItsCrossClearsIt() throws Exception {
        twoAuthors();
        open(100);
        onEdt(() -> h.editor().updateFilterBar());
        final FilterBar bar = h.editor().getFilterBar();
        onEdt(() -> {
            final Rectangle r = bar.boundsOf(LogFilters.GREP);
            press(bar, r.x + 1, r.y + 1);
        });
        assertNotNull(bar.getField(), "a field over the chip");
        onEdt(() -> bar.acceptField(" ada "));
        assertNull(bar.getField());
        assertEquals("ada", log.getQuery().grep());
        until(() -> !log.isLoading() && subjects().equals(List.of("by ada")));
        onEdt(() -> {
            final Rectangle r = bar.clearBoundsOf(LogFilters.GREP);
            press(bar, r.x + 1, r.y + 1);
        });
        assertNull(log.getQuery().grep());
    }

    private static void press(Component c, int x, int y) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    @Test
    public void theFilesAndDiffHaveNoFilterBar() throws Exception {
        repo(1);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        final EditorHarness d = diffWindow();
        try {
            onEdt(() -> d.editor().updateFilterBar());
            assertNull(d.editor().getFilterBar());
        }
        finally {
            close(d);
        }
    }

    @Test
    public void withDirectoriesListsEachDirectoryBeforeItsFiles() {
        assertEquals(
            List.of("a/", "a/b/", "a/b/c", "a/d", "e"),
            LogNames.withDirectories(List.of("a/b/c", "a/d", "e")));
    }

    @Test
    public void aFilesLogKeepsItsPathWhateverThePopupSays() throws Exception {
        twoAuthors();
        final TransientArgs args = new TransientArgs();
        args.set("-- path", "b/");
        args.set("--grep", "x");
        final LogQuery q = LogFilters.withArguments(LogQuery.file("a.txt"), args, LogScope.of(repository));
        assertEquals("a.txt", q.path());
        assertTrue(q.follow());
        assertEquals("x", q.grep());
        final LogQuery all = LogFilters.withArguments(LogQuery.ALL, args, LogScope.of(repository));
        assertEquals("b/", all.path());
        assertFalse(all.follow(), "a directory's history doesn't follow renames");
        assertFalse(all.tags());
        args.setOn(LogFilters.TAGS.name(), true);
        assertTrue(LogFilters.withArguments(LogQuery.ALL, args, LogScope.of(repository)).tags(), "-T in the popup");
    }

    @Test
    public void theCommitsOnlyTagsReachAreLeftOutUntilAskedFor() throws Exception {
        repo(2);
        // A commit of a tag's own, off the first, as cvs2git makes.
        git.git("checkout", "-q", "HEAD~");
        git.write("tagged.txt", "t\n");
        git.commitAll("made for a tag");
        git.git("tag", "old-tag");
        git.git("checkout", "-q", "main");
        git.git("tag", "on-main", "HEAD");
        open(100);
        assertEquals(List.of("commit 1", "commit 0"), subjects());
        assertTrue(texts(log).get(0).contains("on-main"), "a tag on a branch shows either way");
        onEdt(() -> LogCommands.vcsLogToggleTags(h.editor()));
        until(() -> !log.isLoading() && subjects().size() == 3);
        assertTrue(subjects().contains("made for a tag"));
        onEdt(() -> LogFilters.TAGS.binding().set(h.editor(), false));
        until(() -> !log.isLoading() && subjects().size() == 2);
        onEdt(() -> LogFilters.BRANCH.binding().set(h.editor(), "old-tag"));
        until(() -> !log.isLoading() && subjects().equals(List.of("made for a tag", "commit 0")));
    }

    @Test
    public void dashKeysOpenTheirChipsFieldInEitherEditMode() throws Exception {
        twoAuthors();
        open(100);
        onEdt(() -> h.editor().updateFilterBar());
        final FilterBar bar = h.editor().getFilterBar();
        for (boolean vim : new boolean[] { false, true }) {
            onEdt(() -> {
                if (vim) {
                    h.vim();
                    log.setProperty(Property.EDIT_MODE, "vim");
                }
                h.keys("-a");
            });
            assertNotNull(bar.getField(), (vim ? "vim: " : "") + "-a opens the author's field");
            onEdt(() -> bar.acceptField(vim ? "Ada" : ""));
            assertNull(bar.getField());
        }
        assertEquals("Ada", log.getQuery().author());
    }

    @Test
    public void theFilterPopupsKeysAreTheLogsDashKeys() throws Exception {
        for (KeyMap km : List.of(VcsLogMode.getMode().getKeyMap(), VcsLogMode.getMode().getModalKeyMap())) {
            final KeyMapping dash = km.lookup('-', 0, 0);
            assertNotNull(dash);
            final KeyMap filters = (KeyMap) dash.getCommand();
            for (Transient.Item item : LogFilters.group(null).items()) {
                if (item.key().equals("x"))
                    continue;
                final KeyMapping m = filters.lookup(item.key().charAt(1), 0, 0);
                assertNotNull(m, item.key());
                if (item == LogFilters.TAGS) {
                    assertEquals("vcsLogToggleTags", m.getCommand());
                    continue;
                }
                assertEquals("vcsLogFilter " + item.description(), m.getCommand());
                assertNotNull(LogFilters.named(item.description()));
            }
        }
    }

    @Test
    public void theFilterKeysAreTheListsAlone() throws Exception {
        repo(1);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        final VcsLogMode mode = VcsLogMode.getMode();
        for (char c : new char[] { '-', 'x' }) {
            assertNotNull(mode.getKeyMap(log).lookup(c, 0, 0), c + " in the list");
            assertNotNull(mode.getModalKeyMap(log).lookup(c, 0, 0), c + " in the list, vim");
            for (Buffer pane : new Buffer[] { log.files, log.diff }) {
                assertNull(mode.getKeyMap(pane).lookup(c, 0, 0), c + " in " + pane);
                assertNull(mode.getModalKeyMap(pane).lookup(c, 0, 0), c + " in " + pane + ", vim");
            }
        }
        assertNotNull(mode.getKeyMap(log.diff).lookup('n', 0, 0), "the rest are shared");
    }

    @Test
    public void theStatusLogPopupsFiltersAreTheLogsToStartWith() throws Exception {
        twoAuthors();
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsStatusBuffer[] status = new VcsStatusBuffer[1];
        onEdt(() -> {
            status[0] = VcsStatusBuffer.of(List.of(repository));
            h.showBuffer(status[0]);
            h.editor().setDot(status[0].getFirstLine(), 0);
        });
        try {
            until(() -> !status[0].getFirstLine().getText().startsWith("Reading"));
            onEdt(() -> h.keys("l"));
            final TransientPopup popup = TransientPopup.getOpen();
            assertNotNull(popup, "l opens the log popup");
            for (String key : List.of("-b", "-a", "-p", "-s", "-g", "-c"))
                assertTrue(
                    popup.getTransient().item(key) instanceof Transient.Option o && o.binding() == null,
                    key + " is an argument of the popup");
            final String id = popup.getTransient().id();
            onEdt(() -> {
                popup.close(false);
                Transients.args(h.editor(), id).set("--author", "nobody");
                Transients.args(h.editor(), id).set("-- path", "a.txt");
                LogCommands.vcsLogQuick(h.editor());
            });
            assertTrue(h.editor().getBuffer() instanceof LogBuffer, "the quick log, frameless, in the window");
            log = (LogBuffer) h.editor().getBuffer();
            assertEquals("nobody", log.getQuery().author());
            assertEquals("a.txt", log.getQuery().path());
            assertTrue(log.getQuery().follow(), "a file follows its renames");
            until(() -> "No commits match the filters".equals(log.getFirstLine().getText()));
        }
        finally {
            onEdt(() -> {
                status[0].dispose();
                Editor.getBufferList().remove(status[0]);
            });
        }
    }

    @Test
    public void aFilesLogWithNoCommitsSaysSoAndItsTitleFollowsThePath() throws Exception {
        twoAuthors();
        h = EditorHarness.create();
        onEdt(() -> {
            log = LogBuffer.open(repository, LogQuery.file("new.txt"), 100);
            h.showBuffer(log);
        });
        until(() -> !log.isLoading());
        assertEquals(List.of("git: no commits of new.txt"), subjects());
        assertTrue(log.getFileNameForDisplay().endsWith(" new.txt"), log.getFileNameForDisplay());
        onEdt(() -> LogCommands.vcsLogClearFilters(h.editor()));
        assertFalse(log.getFileNameForDisplay().contains("new.txt"), "the whole log's title");
        onEdt(() -> LogFilters.AUTHOR.binding().set(h.editor(), "nobody"));
        until(() -> !log.isLoading());
        assertEquals(List.of("No commits match the filters"), subjects());
    }

    @Test
    public void eachRepositoryKeepsItsOwnOpeningFilters() throws Exception {
        twoAuthors();
        final VcsRepository other =
                Repositories.forDirectory(GitFixture.init(Files.createDirectories(tmp.resolve("other"))).root);
        final String a = LogFilters.openingId(LogScope.of(repository));
        final String b = LogFilters.openingId(LogScope.of(other));
        assertFalse(a.equals(b));
        h = EditorHarness.create();
        Transients.args(h.buffer(), a).set("<revision>", "feature-x");
        assertEquals(
            "feature-x",
            LogFilters.withArguments(LogQuery.ALL, Transients.args(h.buffer(), a), LogScope.of(repository))
                    .revisions()
                    .get(0));
        assertTrue(
            LogFilters.withArguments(LogQuery.ALL, Transients.args(h.buffer(), b), LogScope.of(other))
                    .revisions()
                    .isEmpty(),
            "not the other repository's");
    }

    @Test
    public void theBadgesAreSmallerTheAuthorStillOnItsColumn() throws Exception {
        twoAuthors();
        open(100);
        final LogLine badged = (LogLine) log.getFirstLine();
        final LogLine plain = (LogLine) badged.next();
        assertTrue(badged.row().spans().stream().anyMatch(s -> s.part() == LogRow.Part.HEAD), "HEAD's badge");
        assertTrue(plain.row().spans().stream().noneMatch(s -> BADGES.contains(s.part())), "no badge of any kind");
        // Measured on both rows: the plain one's text is plain there.
        final int author = authorStart(badged);
        final Display display = h.editor().getDisplay();
        final int[] at = new int[4];
        onEdt(() -> {
            at[0] = display.measure(badged, author);
            at[1] = display.measure(plain, author);
            at[2] = display.measure(badged, badgeEnd(badged));
            at[3] = display.measure(plain, badgeEnd(badged));
        });
        assertEquals(at[1], at[0], "the author where it would be with no badges");
        assertTrue(at[1] > 0);
        assertTrue(at[2] < at[3], "the badges narrower than plain text: " + at[2] + " " + at[3]);
        // The gap's slack spread over its columns, none much wider than another.
        final int gap = badgeEnd(badged);
        final List<Integer> widths = new ArrayList<>();
        onEdt(() -> {
            for (int c = gap; c < author; c++)
                widths.add(display.measure(badged, c + 1) - display.measure(badged, c));
        });
        assertTrue(widths.size() > 1, "a gap of several columns");
        assertTrue(Collections.max(widths) - Collections.min(widths) <= 1, "an even share each: " + widths);
        final LogFormatter formatter = new LogFormatter(log);
        assertEquals(formatter.getStyle(LogFormatter.TEXT), formatter.getStyle(LogFormatter.GAP), "looks as the text");
        assertEquals(formatter.getColor(LogFormatter.TEXT), formatter.getColor(LogFormatter.GAP));
    }

    private static final Set<LogRow.Part> BADGES =
            Set.of(LogRow.Part.HEAD, LogRow.Part.BRANCH, LogRow.Part.REMOTE, LogRow.Part.TAG);

    private static int authorStart(LogLine l) {
        for (LogRow.Span s : l.row().spans())
            if (s.part() == LogRow.Part.AUTHOR)
                return s.start();
        throw new AssertionError("no author");
    }

    private static int badgeEnd(LogLine l) {
        int end = 0;
        for (LogRow.Span s : l.row().spans())
            if (BADGES.contains(s.part()) || s.part() == LogRow.Part.COUNT)
                end = s.end();
        return end;
    }

    @Test
    public void theGraphIsAColumnBeforeTheTextButNotWhenCommitsAreLeftOut() throws Exception {
        twoAuthors();
        open(100);
        assertTrue(log.graphWidth() >= 1);
        final int gutter = Display.getGutterWidth(log);
        assertTrue(log.getGutterColumn().width() > 0);
        assertTrue(gutter >= log.getGutterColumn().width(), "in the gutter");
        assertTrue(((LogLine) log.getFirstLine()).graph() != null);
        // Over its lane, the branch's name.
        final Display display = h.editor().getDisplay();
        final Set<String> tips = new HashSet<>();
        onEdt(() -> {
            for (int x = gutter - log.getGutterColumn().width(); x < gutter; x++)
                tips.add(
                    display.getToolTipText(
                        new MouseEvent(display, 0, 0, 0, x, log.getFirstLine().getHeight() / 2, 0, false)));
        });
        assertTrue(tips.contains("main"), tips.toString());
        final LogLine first = (LogLine) log.getFirstLine();
        assertEquals(LogGraphColumn.color(first.graph().color()), badgeColor(first, "main"), "its lane's color");
        final LogFormatter formatter = new LogFormatter(log);
        assertEquals(
            TextStyle.SMALL,
            formatter.getStyle(LogFormatter.format(LogRow.Part.BRANCH, 3)),
            "a branch's, not the table's");
        assertEquals(TextStyle.SMALL | TextStyle.BOLD, formatter.getStyle(LogFormatter.format(LogRow.Part.HEAD, 3)));
        assertEquals(TextStyle.SMALL, formatter.getStyle(LogFormatter.TAG) & TextStyle.SMALL, "a tag's too");
        assertEquals(0, formatter.getStyle(LogFormatter.DIM) & TextStyle.SMALL, "not the author's");
        onEdt(() -> LogFilters.AUTHOR.binding().set(h.editor(), "Ada"));
        until(() -> !log.isLoading() && subjects().equals(List.of("by ada")));
        assertEquals(null, ((LogLine) log.getFirstLine()).graph(), "an author's commits: no graph");
        assertEquals(0, log.graphWidth());
        assertEquals(0, log.getGutterColumn().width(), "no room kept for it");
        assertEquals(
            h.editor().getDisplay().getColumns(),
            log.getFirstLine().getText().length(),
            "laid out for the room it left");
    }

    // The color of the segment of line that is text.
    private Color badgeColor(LogLine line, String text) {
        final Formatter f = log.getFormatter();
        final LineSegmentList segments = f.formatLine(line);
        for (int i = 0; i < segments.size(); i++)
            if (segments.getSegment(i).getText().strip().equals(text))
                return f.getColor(segments.getSegment(i).getFormat());
        return null;
    }

    // Several repositories.

    private GitFixture a;
    private GitFixture b;

    // A project of repositories a and b, their commits a1 at 100, b2 at
    // 200, a3 at 300 and b4 at 400; and its log, read whole.
    private LogScope project() throws Exception {
        final Path proj = tmp.resolve("proj");
        Files.createDirectories(proj.resolve(ProjectRoot.MARKER));
        Files.createDirectories(proj.resolve("a"));
        Files.createDirectories(proj.resolve("b"));
        a = GitFixture.init(proj.resolve("a"));
        b = GitFixture.init(proj.resolve("b"));
        a.write("x.txt", "1\n");
        a.commitAllAt("a1", 100);
        b.write("x.txt", "2\n");
        b.commitAllAt("b2", 200);
        a.write("y.txt", "3\n");
        a.commitAllAt("a3", 300);
        b.write("y.txt", "4\n");
        b.commitAllAt("b4", 400);
        final List<VcsRepository> rs = Repositories.forProject(File.getInstance(proj.toString()));
        assertEquals(2, rs.size());
        repository = rs.get(0);
        return LogScope.of(rs);
    }

    private void open(LogScope scope, int pageSize) throws Exception {
        h = EditorHarness.create();
        onEdt(() -> {
            log = LogBuffer.open(scope, LogQuery.ALL, pageSize);
            h.showBuffer(log);
        });
        until(() -> !log.isLoading() && !log.commits().isEmpty());
    }

    private String repositoryAt(int line) {
        return log.getScope().name(log.repositoryOf(((LogLine) log.getLine(line)).commit()));
    }

    @Test
    public void severalRepositoriesCommitsAreListedTogetherByTime() throws Exception {
        final LogScope scope = project();
        open(scope, 100);
        assertEquals(List.of("b4", "a3", "b2", "a1"), subjects());
        assertEquals("git log: 2 repositories", log.getTitle());
        assertEquals(
            List.of("b", "a", "b", "a"),
            List.of(repositoryAt(0), repositoryAt(1), repositoryAt(2), repositoryAt(3)));
        // Each repository's lane its own, the strip before them.
        assertEquals(2, log.graphWidth());
        final GutterColumn column = log.getGutterColumn();
        assertEquals(b.root.toString(), column.getToolTipText(log.getFirstLine(), 1, 1), "the strip: its repository");
        // A commit's details are from its repository.
        onEdt(() -> {
            h.editor().setDot(log.getLine(1), 0);
            LogCommands.showFull(h.editor(), log);
        });
        until(() -> log.files.commit() != null && log.files.changes() != null && !log.files.changes().isEmpty());
        assertEquals("a3", log.files.commit().subject());
        assertEquals(List.of(new CommitChange("y.txt", null, FileChange.Kind.ADDED)), log.files.changes());
        // Its repository's badge in its color, then its branch's in its lane's.
        assertEquals("   a   main ", texts(log.files).get(0));
        final Formatter f = VcsLogMode.getMode().getFormatter(log.files);
        final LineSegmentList segments = f.formatLine(log.files.getFirstLine());
        final List<Color> colors = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++)
            if (!segments.getSegment(i).getText().isBlank())
                colors.add(f.getColor(segments.getSegment(i).getFormat()));
        final boolean dark = DefaultTheme.isDark(f.getBackgroundColor());
        assertEquals(
            List.of(
                RepositoryColors.color(scope.colorIndex(scope.named("a")), dark),
                LogGraphColumn.color(((LogLine) log.getLine(1)).graph().color())),
            colors);
        assertTrue(f.getRunBackground(segments.getSegment(1).getFormat()) != null, "a badge's chip");
        for (int i = 0; i < segments.size(); i++)
            if (!segments.getSegment(i).getText().isBlank())
                assertTrue(
                    (f.getStyle(segments.getSegment(i).getFormat()) & TextStyle.SMALL) != 0,
                    "every badge small, the repository's too: " + segments.getSegment(i).getText());
    }

    @Test
    public void aRepositoryPagedApartNeverPutsARowAboveOneListed() throws Exception {
        open(project(), 1);
        until(() -> !log.isLoading() && log.commits().size() == 1);
        assertEquals(List.of("b4"), subjects(), "a's next read before b4 is listed; b's next not");
        for (int i = 0; i < 6 && log.commits().size() < 4; i++) {
            onEdt(() -> log.loadMore());
            until(() -> !log.isLoading());
        }
        assertEquals(List.of("b4", "a3", "b2", "a1"), subjects());
    }

    @Test
    public void theRepositoryChecklistOrAClickOnTheStripShowsOneAlone() throws Exception {
        open(project(), 100);
        final GutterColumn column = log.getGutterColumn();
        assertTrue(column.isClickable(log.getFirstLine(), 1, 1), "the strip: a hand");
        assertFalse(column.isClickable(log.getFirstLine(), column.width() - 1, 1), "the graph");
        // The lists' boxes: a repository's color, a branch's lane's.
        final Transient.Pick repos = (Transient.Pick) LogFilters.REPOSITORY.chooser();
        assertTrue(repos.several(), "a checklist");
        final boolean dark = DefaultTheme.isDark(h.editor().getFormatter().getBackgroundColor());
        assertEquals(
            RepositoryColors.color(log.getScope().colorIndex(log.getScope().named("b")), dark),
            repos.swatch().apply(h.editor(), "b"));
        final Transient.Pick branches = (Transient.Pick) LogFilters.BRANCH.chooser();
        assertEquals(
            LogGraphColumn.color(((LogLine) log.getFirstLine()).graph().color()),
            branches.swatch().apply(h.editor(), "main"),
            "b's main, the first row's");
        assertNull(branches.swatch().apply(h.editor(), "no-such"));
        onEdt(() -> LogFilters.REPOSITORY.binding().set(h.editor(), "a"));
        until(() -> !log.isLoading() && subjects().equals(List.of("a3", "a1")));
        onEdt(() -> LogFilters.REPOSITORY.binding().set(h.editor(), "b, a"));
        until(() -> !log.isLoading() && subjects().size() == 4);
        assertEquals(List.of(), log.getQuery().repositories(), "every one checked: all");
        assertNull(LogFilters.REPOSITORY.binding().get(h.editor()));
        onEdt(() -> LogFilters.REPOSITORY.binding().set(h.editor(), "gone, a"));
        assertEquals(List.of("a", "gone"), log.getQuery().repositories(), "the log's order, then the rest");
        onEdt(() -> LogFilters.REPOSITORY.binding().set(h.editor(), null));
        until(() -> !log.isLoading() && subjects().size() == 4);
        onEdt(() -> assertTrue(log.getGutterColumn().clicked(h.editor(), log.getFirstLine(), 1, 1)));
        until(() -> !log.isLoading() && subjects().equals(List.of("b4", "b2")));
        assertEquals(List.of("b"), log.getQuery().repositories());
        onEdt(() -> log.getGutterColumn().clicked(h.editor(), log.getFirstLine(), 1, 1));
        until(() -> !log.isLoading() && subjects().size() == 4);
        assertTrue(LogFilters.of(log.getScope()).contains(LogFilters.REPOSITORY));
        assertFalse(LogFilters.of(LogScope.of(repository)).contains(LogFilters.REPOSITORY), "not for one");
    }

    @Test
    public void anOpenFieldKeepsItsChipsPlaceAndItsCrossClosesItThenClears() throws Exception {
        open(project(), 100);
        onEdt(() -> h.editor().updateFilterBar());
        final FilterBar bar = h.editor().getFilterBar();
        onEdt(() -> assertTrue(bar.choose("--author")));
        final Rectangle field = bar.getField().getBounds();
        assertEquals(bar.boundsOf(LogFilters.AUTHOR).x, field.x);
        assertTrue(bar.boundsOf(LogFilters.PATH).x >= field.x + field.width, "the next chip isn't under the field");
        onEdt(() -> bar.acceptField(""));
        onEdt(() -> assertTrue(bar.choose("--repository")));
        onEdt(() -> {
            // The value changed as a checklist's does, the field open.
            LogFilters.AUTHOR.binding().set(h.editor(), "Somebody With A Long Name");
            LogFilters.REPOSITORY.binding().set(h.editor(), "a");
            FilterBar.refreshAll(log);
        });
        assertNotNull(bar.getField(), "still open");
        assertEquals(bar.boundsOf(LogFilters.REPOSITORY).x, bar.getField().getX(), "the field moved with its chip");
        final Rectangle cross = bar.clearBoundsOf(LogFilters.REPOSITORY);
        assertNotNull(cross, "a set chip's × shows beside its open field");
        onEdt(
            () -> bar.dispatchEvent(
                new MouseEvent(bar,
                        MouseEvent.MOUSE_PRESSED,
                        0,
                        0,
                        cross.x + 1,
                        cross.y + 1,
                        1,
                        false,
                        MouseEvent.BUTTON1)));
        assertNull(bar.getField());
        assertEquals(List.of(), log.getQuery().repositories(), "cleared, not set again by the field");
    }

    @Test
    public void aPathGoesToTheRepositoryHoldingIt() throws Exception {
        open(project(), 100);
        onEdt(() -> LogFilters.PATH.binding().set(h.editor(), "b/x.txt"));
        until(() -> !log.isLoading() && subjects().equals(List.of("b2")));
        assertTrue(LogNames.paths(log.getScope()).containsAll(List.of("a/", "a/x.txt", "b/y.txt")));
    }

    @Test
    public void theSameCommitInTwoRepositoriesIsTwoRows() throws Exception {
        final Path proj = tmp.resolve("proj");
        Files.createDirectories(proj.resolve(ProjectRoot.MARKER));
        Files.createDirectories(proj.resolve("a"));
        a = GitFixture.init(proj.resolve("a"));
        a.write("x.txt", "1\n");
        a.commitAllAt("first", 100);
        a.write("x.txt", "2\n");
        a.commitAllAt("second", 200);
        a.git("clone", "-q", a.root.toString(), proj.resolve("c").toString());
        final List<VcsRepository> rs = Repositories.forProject(File.getInstance(proj.toString()));
        repository = rs.get(0);
        open(LogScope.of(rs), 100);
        assertEquals(List.of("second", "second", "first", "first"), subjects());
        final LogLine last = (LogLine) log.getLine(3);
        assertEquals("c", repositoryAt(3));
        assertFalse(last.graph().top().isEmpty(), "c's first joins c's second, not a's");
        // The caret stays on c's commit through a refresh, not a's.
        onEdt(() -> h.editor().setDot(log.getLine(3), 0));
        onEdt(() -> log.refresh());
        until(() -> !log.isLoading() && log.commits().size() == 4);
        assertEquals(3, h.editor().getDotLineNumber());
    }

    @Test
    public void anArrowsJumpGoesToItsCommitReadingOnToIt() throws Exception {
        repo(7);
        open(2);
        final String first = git.git("rev-list", "--max-parents=0", "HEAD").strip();
        final String key = LogGraph.key(log.commits().get(0), first);
        onEdt(() -> log.jump(h.editor(), key));
        until(() -> !log.isLoading() && h.editor().getDotLine() instanceof LogLine l && l.commit().id().equals(first));
        assertEquals(6, h.editor().getDotLineNumber(), "read on to the last page");
        onEdt(() -> log.jump(h.editor(), LogGraph.key(log.commits().get(0), log.commits().get(1).id())));
        assertEquals(1, h.editor().getDotLineNumber(), "one read: at once");
    }

    @Test
    public void filtersThatLeaveEveryRepositoryOutListNone() throws Exception {
        open(project(), 100);
        onEdt(() -> LogFilters.PATH.binding().set(h.editor(), "nowhere/x.txt"));
        until(() -> !log.isLoading() && log.getLineCount() == 1 && !(log.getFirstLine() instanceof LogLine));
        assertTrue(log.getFirstLine().getText().contains("no commits"), log.getFirstLine().getText());
        onEdt(() -> LogFilters.PATH.binding().set(h.editor(), null));
        until(() -> !log.isLoading() && subjects().size() == 4);
        onEdt(() -> LogFilters.REPOSITORY.binding().set(h.editor(), "gone"));
        until(() -> !log.isLoading() && subjects().equals(List.of("No commits match the filters")));
    }

    @Test
    public void aJumpsDetailsFollowAndTagsWithABranchReadNothingAgain() throws Exception {
        repo(4);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.files.commit() != null);
        final LogCommit last = log.commits().get(3);
        onEdt(() -> log.jump(h.editor(), LogGraph.key(last, last.id())));
        until(() -> log.files.commit() != null && log.files.commit().isSame(last));
        onEdt(() -> LogFilters.BRANCH.binding().set(h.editor(), "main"));
        until(() -> !log.isLoading() && log.getQuery().revisions().equals(List.of("main")));
        final List<LogCommit> before = List.copyOf(log.commits());
        onEdt(() -> LogCommands.vcsLogToggleTags(h.editor()));
        assertTrue(log.getQuery().tags(), "kept for when the branch is unset");
        assertFalse(log.isLoading(), "not read again");
        assertEquals(before, log.commits());
    }

    @Test
    public void theFullLogIsAFrameViewItsPanesItsChildren() throws Exception {
        repo(2);
        open(100);
        assertNull(log.getFrameView(), "the quick log: a buffer like any other");
        assertEquals(List.of(), log.getChildren());
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        assertEquals(log, log.getFrameView());
        assertEquals(List.of(log.files, log.diff), log.getChildren());
        for (Buffer pane : List.<Buffer>of(log.files, log.diff)) {
            assertEquals(log, pane.getFrameView());
            assertFalse(pane.isPrimary(), "listed under the log");
            assertEquals(log, pane.getPrimary());
        }
        // Going through the buffers, the log is one stop.
        onEdt(() -> {
            final BufferList list = Editor.getBufferList();
            Buffer at = log;
            for (int i = 0; i < list.size(); i++) {
                at = list.getNextListedBuffer(at);
                assertFalse(at == log.files || at == log.diff, "a pane: " + at);
            }
        });
    }

    @Test
    public void theStatusOpensOnTheLogsRepositoryFromItsListFilesOrDiff() throws Exception {
        repo(2);
        open(10);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        for (Buffer b : new Buffer[] { log, log.files, log.diff })
            assertEquals(List.of(repository), VcsStatusMode.repositoriesFor(b), b.toString());
    }

    @Test
    public void theFiltersAreInTheLogsOnePopupNotOnLOrA() throws Exception {
        repo(1);
        open(100);
        for (KeyMap km : List.of(VcsLogMode.getMode().getKeyMap(), VcsLogMode.getMode().getModalKeyMap())) {
            assertNull(km.lookup('l', 0, 0));
            assertNull(km.lookup('a', 0, 0));
        }
        final Transient popup = VcsLogMode.getMode().getTransient(h.editor());
        assertEquals("Filters", popup.groups().get(0).label());
        assertNotNull(popup.item("-a"));
        assertNotNull(popup.item("x"));
    }

    @Test
    public void dashTStepsTheTimeStyleOfTheRowsAndTheDetails() throws Exception {
        repo(2);
        open(100);
        onEdt(() -> LogCommands.showFull(h.editor(), log));
        until(() -> log.files.commit() != null);
        final LogLine first = (LogLine) log.getFirstLine();
        assertEquals(TimeStyle.AGE, log.getTimeStyle());
        // The fixture's commits are dated, months back.
        assertTrue(first.getText().matches(".*  (now|\\d+(m|h|d|w|mo|y))"), first.getText());
        assertTrue(
            texts(log.files).stream().anyMatch(t -> t.matches("Date: +\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} \\(\\w+\\)")),
            texts(log.files).toString());
        final KeyMap dash = (KeyMap) VcsLogMode.getMode().getModalKeyMap().lookup('-', 0, 0).getCommand();
        assertEquals("vcsLogTimeStyle", dash.lookup('t', 0, 0).getCommand());
        onEdt(() -> LogCommands.vcsLogTimeStyle(h.editor()));
        assertEquals(TimeStyle.LOCALE, log.getTimeStyle());
        onEdt(() -> LogCommands.vcsLogTimeStyle(h.editor(), "long-iso"));
        final String row = ((LogLine) log.getFirstLine()).getText();
        assertTrue(row.matches(".*  \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), row);
        assertTrue(
            texts(log.files).stream().anyMatch(t -> t.matches("Date: +\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
            texts(log.files).toString());
        assertNotNull(VcsLogMode.getMode().getTransient(h.editor()).item("-t"), "in the popup's View");
    }

    // Each commit shown, with the count after a folded run's head.
    private List<String> shown() {
        final List<String> out = new ArrayList<>();
        for (Line l = log.getFirstLine(); l != null; l = l.next()) {
            if (l.isHidden())
                continue;
            final LogLine ll = (LogLine) l;
            String text = ll.commit().subject();
            for (LogRow.Span span : ll.row().spans())
                if (span.part() == LogRow.Part.COUNT)
                    text += " " + ll.getText().substring(span.start(), span.end());
            out.add(text);
        }
        return out;
    }

    private Line lineOf(String subject) {
        for (Line l = log.getFirstLine(); l != null; l = l.next())
            if (((LogLine) l).commit().subject().equals(subject))
                return l;
        throw new AssertionError(subject);
    }

    @Test
    public void dashLFoldsEachRunOfPlainCommitsUnderTheCommitAbove() throws Exception {
        repo(6);
        open(100);
        assertEquals(6, shown().size());
        onEdt(() -> h.editor().moveDotTo(lineOf("commit 3"), 0));
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        // The tip has its branch, the root no parent: the four between are plain, under the tip.
        assertEquals(List.of("commit 5 … 4 commits", "commit 0"), shown());
        assertEquals(
            "commit 5",
            LogBuffer.commitAt(h.editor()).subject(),
            "the caret off the lines hidden, onto the head");
        assertEquals(6, log.getLineCount(), "every commit still in the buffer");
        onEdt(() -> LogCommands.vcsLogToggle(h.editor()));
        assertEquals(6, shown().size(), "Tab on the head opens it");
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        assertEquals(6, shown().size(), "-l again shows every commit");
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        assertEquals(2, shown().size(), "and folds every run again");
        onEdt(() -> LogCommands.vcsLogToggleAll(h.editor()));
        assertEquals(6, shown().size(), "Shift Tab opens them all when none is open");
    }

    @Test
    public void aRefBreaksARun() throws Exception {
        git = GitFixture.init(tmp);
        for (int i = 0; i < 6; i++) {
            git.write("a.txt", "line " + i + "\n");
            git.commitAll("commit " + i);
            if (i == 2)
                git.git("tag", "v1");
        }
        repository = Repositories.forDirectory(git.root);
        open(100);
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        // commit 2 has the tag; commit 1 alone between it and the root folds nothing.
        assertEquals(List.of("commit 5 … 2 commits", "commit 2", "commit 1", "commit 0"), shown());
    }

    @Test
    public void nothingThatBendsTheGraphIsEverFolded() throws Exception {
        // A fork, a merge, and a branch whose lane passes enough rows to leave as an arrow.
        git = GitFixture.init(tmp);
        git.write("a.txt", "0\n");
        git.commitAll("root");
        git.git("checkout", "-q", "-b", "side");
        git.write("s.txt", "s\n");
        git.commitAll("side 1");
        git.write("s.txt", "ss\n");
        git.commitAll("side 2");
        git.git("checkout", "-q", "main");
        for (int i = 0; i < 40; i++) {
            git.write("a.txt", i + "\n");
            git.commitAll("main " + i);
        }
        git.git("merge", "-q", "--no-ff", "-m", "Merge side", "side");
        git.write("a.txt", "top\n");
        git.commitAll("top");
        repository = Repositories.forDirectory(git.root);
        open(100);
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        boolean anyHidden = false;
        for (Line l = log.getFirstLine(); l != null; l = l.next()) {
            final LogLine ll = (LogLine) l;
            if (!l.isHidden())
                continue;
            anyHidden = true;
            assertFalse(ll.commit().isMerge(), ll.commit().subject());
            assertTrue(ll.commit().refs().isEmpty(), ll.commit().subject());
            assertTrue(ll.graph().straight(), ll.commit().subject() + ": a row the graph bends or ends in");
        }
        assertTrue(anyHidden, "the long run on main folds");
        assertFalse(lineOf("Merge side").isHidden());
        assertFalse(lineOf("root").isHidden(), "the fork");
    }

    @Test
    public void aRunGrowsAsTheNextPagesAreReadTheCaretLeftAlone() throws Exception {
        repo(8);
        open(3);
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        assertEquals(List.of("commit 7 … 2 commits"), shown());
        // The caret as far down as moving goes, onto the last line shown: the end of the
        // commits read is near, so the next pages are read.
        for (int n : new int[] { 6, 8 }) {
            onEdt(() -> {
                Line last = log.getLastLine();
                while (last.isHidden())
                    last = last.previous();
                h.editor().setDot(last, 0);
                log.afterCommand(h.editor());
            });
            until(() -> log.commits().size() == n && !log.isLoading());
        }
        assertEquals(List.of("commit 7 … 6 commits", "commit 0"), shown());
        for (Line l = log.getFirstLine(); l != null; l = l.next())
            assertTrue(l.getHidden() <= 1, "hidden once, however many pages found its run folded");
        onEdt(() -> {
            h.editor().moveDotTo(lineOf("commit 0"), 3);
            h.editor().moveCaretToDotCol();
        });
        assertEquals(3, h.editor().getDotOffset());
    }

    @Test
    public void pagesComeWithTheCaretOnARunFoldedAtTheEnd() throws Exception {
        repo(12);
        open(4);
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        // One row shown, the run's head: the end is near, whatever the lines hidden.
        onEdt(() -> {
            h.editor().setDot(log.getFirstLine(), 0);
            log.afterCommand(h.editor());
        });
        until(() -> log.commits().size() == 8 && !log.isLoading());
        onEdt(() -> log.afterCommand(h.editor()));
        until(() -> log.commits().size() == 12 && !log.isLoading());
        assertEquals(List.of("commit 11 … 10 commits", "commit 0"), shown());
    }

    @Test
    public void theCaretLandingInAFoldedRunOpensIt() throws Exception {
        repo(6);
        open(100);
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        assertEquals(2, shown().size());
        // As a search finding a commit hidden there does.
        onEdt(() -> {
            h.editor().moveDotTo(lineOf("commit 2"), 0);
            h.editor().updateDisplay();
            log.afterCommand(h.editor());
        });
        assertEquals(6, shown().size());
        assertEquals("commit 5", shown().get(0), "its head's count gone");
        assertFalse(VcsLogMode.getMode().isFolded(lineOf("commit 5")));
    }

    @Test
    public void eachRunIsAFoldTheFoldKeysOpenAndClose() throws Exception {
        repo(6);
        open(100);
        final VcsLogMode mode = VcsLogMode.getMode();
        final Line head = lineOf("commit 5");
        final Line inside = lineOf("commit 3");
        // Every commit shown, its runs open folds: a marker on each one's head, the commit above.
        assertTrue(mode.isFoldStart(log, head));
        assertFalse(mode.isFoldStart(log, lineOf("commit 4")));
        assertFalse(mode.isFolded(head));
        // zc from inside the run folds it.
        onEdt(() -> h.editor().moveDotTo(inside, 0));
        onEdt(() -> assertTrue(mode.setFoldAt(h.editor(), inside, false)));
        assertEquals(List.of("commit 5 … 4 commits", "commit 0"), shown());
        assertTrue(mode.isFolded(head), "a closed fold's marker");
        // za on the head opens it; zM folds every run, zR opens them.
        onEdt(() -> mode.setFoldAt(h.editor(), head, null));
        assertEquals(6, shown().size());
        onEdt(() -> mode.foldAll(h.editor()));
        assertEquals(2, shown().size());
        assertTrue(log.getCollapse());
        onEdt(() -> mode.setAllFolds(h.editor(), true));
        assertEquals(6, shown().size());
        onEdt(() -> mode.setFoldAt(h.editor(), lineOf("commit 0"), false));
        assertEquals(6, shown().size(), "the root heads and is in no run: nothing folds");
    }

    @Test
    public void aFoldedRunsCountComesAfterTheSubjectAndBadges() throws Exception {
        repo(6);
        open(100);
        onEdt(() -> LogCommands.vcsLogCollapseRuns(h.editor()));
        final LogLine head = (LogLine) lineOf("commit 5");
        final List<LogRow.Part> parts = new ArrayList<>();
        for (LogRow.Span span : head.row().spans())
            parts.add(span.part());
        assertEquals(
            List.of(LogRow.Part.SUBJECT, LogRow.Part.HEAD, LogRow.Part.COUNT, LogRow.Part.AUTHOR, LogRow.Part.DATE),
            parts);
        assertTrue(head.getText().startsWith("commit 5  main  … 4 commits"), head.getText());
    }

    @Test
    public void theDashLKeyAndThePopupsViewHaveIt() {
        final org.armedbear.j.KeyMap dash =
                (org.armedbear.j.KeyMap) VcsLogMode.getMode().getKeyMap().lookup('-', 0, 0).getCommand();
        assertEquals("vcsLogCollapseRuns", dash.lookup('l', 0, 0).getCommand());
        assertNotNull(VcsLogMode.getMode().getTransient(null).item("-l"));
    }
}
