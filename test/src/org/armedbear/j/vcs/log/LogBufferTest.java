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
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.StatusLine.Kind;
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
        assertEquals(60, log.getFirstLine().getText().length());
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
        assertTrue(files.get(0).startsWith("Commit:   "), files.toString());
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
        assertEquals(6, org.armedbear.j.vcs.status.VcsStatusMode.lineNumber(hunk()));
        for (Line l = log.diff.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.DIFF && s.getText().endsWith("+six"))
                assertEquals(6, org.armedbear.j.vcs.status.VcsStatusMode.lineNumber(s));
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
                log.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
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
                log.diff.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
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
                        log.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
                        log.diff.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
                    }
                });
                for (EditorHarness e : List.of(h, d)) {
                    final org.armedbear.j.KeyMap km = vim
                            ? VcsLogMode.getMode().getModalKeyMap(e.editor().getBuffer())
                            : VcsLogMode.getMode().getKeyMap(e.editor().getBuffer());
                    for (org.armedbear.j.transients.Transient.Group g : VcsLogMode.getMode()
                            .getTransient(e.editor())
                            .groups())
                        for (org.armedbear.j.transients.Transient.Item item : g.items())
                            if (item instanceof org.armedbear.j.transients.Transient.Command c) {
                                assertEquals(1, c.key().length(), c.key());
                                final org.armedbear.j.KeyMapping m = km.lookup(c.key().charAt(0), 0, 0);
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
        return VcsLogMode.getMode()
                .getTransient(e.editor())
                .groups()
                .stream()
                .map(org.armedbear.j.transients.Transient.Group::label)
                .toList();
    }

    @Test
    public void dIsRememberedForTheNextLog() throws Exception {
        repo(1);
        open(100);
        final org.armedbear.j.SessionProperties session = Editor.getSessionProperties();
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
    public void aFilterReadsTheLogAgainAndItsChipShowsIt() throws Exception {
        twoAuthors();
        open(100);
        onEdt(() -> h.editor().updateFilterBar());
        final org.armedbear.j.transients.FilterBar bar = h.editor().getFilterBar();
        assertNotNull(bar, "the list has a filter bar");
        assertEquals(
            List.of("-b branch ▾", "-a author ▾", "-p path ▾", "-d since ▾", "-g message ▾", "-s change ▾"),
            bar.text());
        onEdt(() -> {
            LogFilters.AUTHOR.binding().set(h.editor(), "Ada");
            org.armedbear.j.transients.FilterBar.refreshAll(log);
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
        final org.armedbear.j.transients.FilterBar bar = h.editor().getFilterBar();
        onEdt(() -> {
            final java.awt.Rectangle r = bar.boundsOf(LogFilters.GREP);
            press(bar, r.x + 1, r.y + 1);
        });
        assertNotNull(bar.getField(), "a field over the chip");
        onEdt(() -> bar.acceptField(" ada "));
        assertNull(bar.getField());
        assertEquals("ada", log.getQuery().grep());
        until(() -> !log.isLoading() && subjects().equals(List.of("by ada")));
        onEdt(() -> {
            final java.awt.Rectangle r = bar.clearBoundsOf(LogFilters.GREP);
            press(bar, r.x + 1, r.y + 1);
        });
        assertNull(log.getQuery().grep());
    }

    private static void press(java.awt.Component c, int x, int y) {
        c.dispatchEvent(
            new java.awt.event.MouseEvent(c,
                    java.awt.event.MouseEvent.MOUSE_PRESSED,
                    0,
                    0,
                    x,
                    y,
                    1,
                    false,
                    java.awt.event.MouseEvent.BUTTON1));
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
        final org.armedbear.j.transients.TransientArgs args = new org.armedbear.j.transients.TransientArgs();
        args.set("-- path", "b/");
        args.set("--grep", "x");
        final LogQuery q = LogFilters.withArguments(LogQuery.file("a.txt"), args, repository);
        assertEquals("a.txt", q.path());
        assertTrue(q.follow());
        assertEquals("x", q.grep());
        final LogQuery all = LogFilters.withArguments(LogQuery.ALL, args, repository);
        assertEquals("b/", all.path());
        assertFalse(all.follow(), "a directory's history doesn't follow renames");
    }

    @Test
    public void dashKeysOpenTheirChipsFieldInEitherEditMode() throws Exception {
        twoAuthors();
        open(100);
        onEdt(() -> h.editor().updateFilterBar());
        final org.armedbear.j.transients.FilterBar bar = h.editor().getFilterBar();
        for (boolean vim : new boolean[] { false, true }) {
            onEdt(() -> {
                if (vim) {
                    h.vim();
                    log.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
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
        for (org.armedbear.j.KeyMap km : List
                .of(VcsLogMode.getMode().getKeyMap(), VcsLogMode.getMode().getModalKeyMap())) {
            final org.armedbear.j.KeyMapping dash = km.lookup('-', 0, 0);
            assertNotNull(dash);
            final org.armedbear.j.KeyMap filters = (org.armedbear.j.KeyMap) dash.getCommand();
            for (org.armedbear.j.transients.Transient.Item item : LogFilters.popup().groups().get(0).items()) {
                final org.armedbear.j.KeyMapping m = filters.lookup(item.key().charAt(1), 0, 0);
                assertNotNull(m, item.key());
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
        for (char c : new char[] { 'l', '-', 'x' }) {
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
        final org.armedbear.j.vcs.status.VcsStatusBuffer[] status = new org.armedbear.j.vcs.status.VcsStatusBuffer[1];
        onEdt(() -> {
            status[0] = org.armedbear.j.vcs.status.VcsStatusBuffer.of(List.of(repository));
            h.showBuffer(status[0]);
            h.editor().setDot(status[0].getFirstLine(), 0);
        });
        try {
            until(() -> !status[0].getFirstLine().getText().startsWith("Reading"));
            onEdt(() -> h.keys("l"));
            final org.armedbear.j.transients.TransientPopup popup = org.armedbear.j.transients.TransientPopup.getOpen();
            assertNotNull(popup, "l opens the log popup");
            for (String key : List.of("-b", "-a", "-p", "-d", "-g", "-s"))
                assertTrue(
                    popup.getTransient().item(key) instanceof org.armedbear.j.transients.Transient.Option o
                            && o.binding() == null,
                    key + " is an argument of the popup");
            final String id = popup.getTransient().id();
            onEdt(() -> {
                popup.close(false);
                org.armedbear.j.transients.Transients.args(h.editor(), id).set("--author", "nobody");
                org.armedbear.j.transients.Transients.args(h.editor(), id).set("-- path", "a.txt");
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
        final VcsRepository other = Repositories
                .forDirectory(GitFixture.init(java.nio.file.Files.createDirectories(tmp.resolve("other"))).root);
        final String a = LogFilters.openingId(repository);
        final String b = LogFilters.openingId(other);
        assertFalse(a.equals(b));
        h = EditorHarness.create();
        org.armedbear.j.transients.Transients.args(h.buffer(), a).set("<revision>", "feature-x");
        assertEquals(
            "feature-x",
            LogFilters
                    .withArguments(LogQuery.ALL, org.armedbear.j.transients.Transients.args(h.buffer(), a), repository)
                    .revisions()
                    .get(0));
        assertTrue(
            LogFilters.withArguments(LogQuery.ALL, org.armedbear.j.transients.Transients.args(h.buffer(), b), other)
                    .revisions()
                    .isEmpty(),
            "not the other repository's");
    }
}
