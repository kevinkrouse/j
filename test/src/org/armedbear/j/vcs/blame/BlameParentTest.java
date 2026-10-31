/*
 * BlameParentTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.blame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.log.RevisionBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commands on the caret line's commit, from its blame, and the column's menu of them. */
public class BlameParentTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            if (file != null) {
                for (GutterColumn c : List.copyOf(file.getGutterColumns()))
                    if (c instanceof BlameColumn column) {
                        file.removeGutterColumn(column);
                        column.blame().removeListener(column.changed());
                    }
                Editor.getBufferList().remove(file);
            }
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
        });
        GitFixture.reset();
    }

    @Test
    public void theParentOpensBlamedWithTheCaretNearTheLine() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\nthree\n");
        commitAs("Ann", "first");
        git.write("f.txt", "one\nTWO\nthree\n");
        commitAs("Bob", "second");
        git.write("f.txt", "one\nTWO\nTHREE\n");
        commitAs("Cat", "third");
        open("f.txt");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
        // The caret on 'THREE', last changed by the third commit.
        onEdt(() -> h.editor().setDot(file.getLine(2), 0));
        onEdt(() -> BlameCommands.vcsBlameParent(h.editor()));
        until(() -> Editor.currentEditor().getBuffer() instanceof RevisionBuffer);
        final RevisionBuffer parent = (RevisionBuffer) Editor.currentEditor().getBuffer();
        assertEquals("f.txt", parent.path());
        assertEquals(git.git("rev-parse", "HEAD~").strip(), parent.revision(), "the second commit");
        assertEquals("one\nTWO\nthree\n", text(parent));
        // The caret on the nearest line: 'three' in the parent's version.
        assertEquals(2, Editor.currentEditor().getDotLineNumber());
        // Blamed, at that revision: 'three' is Ann's, 'TWO' Bob's.
        until(() -> parent.getBlame() != null && parent.getBlame().data() != null && parent.getBlame().data().isDone());
        assertEquals("Ann", parent.getBlame().data().commit(parent.getLine(2)).author());
        assertEquals("Bob", parent.getBlame().data().commit(parent.getLine(1)).author());
        assertTrue(hasColumn(parent), "the column is on");
    }

    @Test
    public void againDigsFurtherBack() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\n");
        commitAs("Ann", "first");
        git.write("f.txt", "ONE\n");
        commitAs("Bob", "second");
        git.write("f.txt", "ONCE\n");
        commitAs("Cat", "third");
        open("f.txt");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
        onEdt(() -> h.editor().setDot(file.getLine(0), 0));
        onEdt(() -> BlameCommands.vcsBlameParent(h.editor()));
        until(() -> Editor.currentEditor().getBuffer() instanceof RevisionBuffer);
        final RevisionBuffer second = (RevisionBuffer) Editor.currentEditor().getBuffer();
        assertEquals("ONE\n", text(second));
        until(() -> second.getBlame() != null && second.getBlame().data() != null && second.getBlame().data().isDone());
        // In the parent buffer, again: the first commit's file.
        onEdt(() -> BlameCommands.vcsBlameParent(Editor.currentEditor()));
        until(() -> {
            final Buffer b = Editor.currentEditor().getBuffer();
            return b instanceof RevisionBuffer && b != second;
        });
        final RevisionBuffer first = (RevisionBuffer) Editor.currentEditor().getBuffer();
        assertEquals(git.git("rev-parse", "HEAD~2").strip(), first.revision());
        assertEquals("one\n", text(first));
    }

    @Test
    public void aRenameIsFollowedToTheOldPath() throws Exception {
        git = GitFixture.init(tmp);
        git.write("old.txt", "a\nb\n");
        commitAs("Ann", "first");
        git.git("mv", "old.txt", "new.txt");
        git.write("new.txt", "a\nB\n");
        commitAs("Bob", "rename and change");
        open("new.txt");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
        onEdt(() -> h.editor().setDot(file.getLine(1), 0));
        onEdt(() -> BlameCommands.vcsBlameParent(h.editor()));
        until(() -> Editor.currentEditor().getBuffer() instanceof RevisionBuffer);
        final RevisionBuffer parent = (RevisionBuffer) Editor.currentEditor().getBuffer();
        assertEquals("old.txt", parent.path(), "the old path");
        assertEquals("a\nb\n", text(parent));
    }

    @Test
    public void noParentIsSaid() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        commitAs("Ann", "root");
        open("f.txt");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
        onEdt(() -> h.editor().setDot(file.getLine(0), 0));
        onEdt(() -> BlameCommands.vcsBlameParent(h.editor()));
        assertTrue(h.status().startsWith("No parent"), h.status());
    }

    @Test
    public void notCommittedYetIsSaid() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        commitAs("Ann", "root");
        open("f.txt");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
        onEdt(() -> {
            h.editor().setDot(file.getLine(0), 1);
            h.editor().insertString(" changed");
        });
        onEdt(() -> BlameCommands.vcsBlameParent(h.editor()));
        assertEquals("Not committed yet", h.status());
    }

    @Test
    public void noBlameIsSaid() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        commitAs("Ann", "root");
        open("f.txt");
        onEdt(() -> BlameCommands.vcsBlameParent(h.editor()));
        assertTrue(h.status().contains("No blame"), h.status());
    }

    @Test
    public void theRevisionOpensAtTheLinesOwnCommitOnThatLine() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\nthree\n");
        commitAs("Ann", "first");
        git.write("f.txt", "one\nTWO\nthree\n");
        commitAs("Bob", "second");
        git.write("f.txt", "zero\none\nTWO\nthree\n");
        commitAs("Cat", "third");
        blamed("f.txt");
        // 'TWO', Bob's, a line further down since.
        onEdt(() -> h.editor().setDot(file.getLine(2), 0));
        onEdt(() -> BlameCommands.vcsBlameRevision(h.editor()));
        until(() -> Editor.currentEditor().getBuffer() instanceof RevisionBuffer);
        final RevisionBuffer at = (RevisionBuffer) Editor.currentEditor().getBuffer();
        assertEquals(git.git("rev-parse", "HEAD~").strip(), at.revision());
        assertEquals("one\nTWO\nthree\n", text(at));
        assertEquals(1, Editor.currentEditor().getDotLineNumber(), "'TWO' there");
        until(() -> at.getBlame() != null && at.getBlame().data() != null && at.getBlame().data().isDone());
        assertTrue(hasColumn(at), "the column is on");
        onEdt(() -> assertEquals(1, at.getGutterColumns().size(), "once"));
    }

    @Test
    public void theHashAndTheMessageAreCopied() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        git.git("add", "-A");
        git.git("commit", "-q", "--author=Ann <a@example.com>", "-m", "root", "-m", "Its body.");
        blamed("f.txt");
        onEdt(() -> {
            h.editor().setDot(file.getLine(0), 0);
            BlameCommands.vcsBlameCopyHash(h.editor());
        });
        assertEquals(git.git("rev-parse", "HEAD").strip(), Editor.getKillRing().peek());
        onEdt(() -> BlameCommands.vcsBlameCopyMessage(h.editor()));
        until(() -> "root\n\nIts body.".equals(Editor.getKillRing().peek()));
    }

    @Test
    public void theDiffIsTheCommitsChangeSideBySide() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        git.write("f.txt", "one\nTWO\n");
        commitAs("Bob", "second");
        blamed("f.txt");
        onEdt(() -> {
            h.editor().setDot(file.getLine(1), 0);
            BlameCommands.vcsBlameDiff(h.editor());
        });
        final String head = git.git("rev-parse", "--short=7", "HEAD").strip();
        final String parent = git.git("rev-parse", "--short=7", "HEAD~").strip();
        until(() -> comparison("f.txt @ " + head) != null);
        final CompareBuffer c = comparison("f.txt @ " + head);
        try {
            assertEquals("f.txt @ " + parent, c.getOld().getTitle());
            assertTrue(text(c).contains("TWO"), text(c));
            assertTrue(text(c.getOld()).contains("two"), text(c.getOld()));
        }
        finally {
            onEdt(() -> {
                Editor.getBufferList().remove(c.getOld());
                Editor.getBufferList().remove(c);
            });
        }
    }

    @Test
    public void theMenuOffersTheLinesCommitOrJustHiding() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "root");
        blamed("f.txt");
        onEdt(() -> {
            BlameColumn column = null;
            for (GutterColumn c : file.getGutterColumns())
                if (c instanceof BlameColumn b)
                    column = b;
            final JPopupMenu committed = column.contextMenu(h.editor(), file.getLine(0), 0, 0);
            assertEquals(
                List.of(
                    "vcsBlameDiff",
                    "vcsBlameShowCommit",
                    "vcsBlameRevision",
                    "vcsBlameParent",
                    "vcsBlameCopyHash",
                    "vcsBlameCopyMessage",
                    "vcsBlame"),
                commands(committed));
            assertFalse(item(committed, "vcsBlameParent").isEnabled(), "the root has no parent");
            h.editor().setDot(file.getLine(1), 1);
            h.editor().insertString("changed");
            assertEquals(List.of("vcsBlame"), commands(column.contextMenu(h.editor(), file.getLine(1), 0, 0)));
        });
    }

    @Test
    public void theEditorsMenuShowsOrHidesTheColumn() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        commitAs("Ann", "root");
        open("f.txt");
        onEdt(() -> {
            h.editor().setDot(file.getLine(0), 0);
            assertEquals("Show Blame", item(file.getMode().getContextMenu(h.editor()), "vcsBlame").getText());
            BlameCommands.vcsBlame(h.editor());
            assertEquals("Hide Blame", item(file.getMode().getContextMenu(h.editor()), "vcsBlame").getText());
            h.showBuffer(h.buffer());
            assertNull(
                item(h.buffer().getMode().getContextMenu(h.editor()), "vcsBlame"),
                "none in a buffer that can't be blamed");
        });
    }

    // The file blamed, its column on and read.
    private void blamed(String path) throws Exception {
        open(path);
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
    }

    private static CompareBuffer comparison(String title) {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CompareBuffer c && title.equals(c.getTitle()))
                return c;
        return null;
    }

    private static List<String> commands(JPopupMenu menu) {
        final List<String> commands = new ArrayList<>();
        for (var c : menu.getComponents())
            if (c instanceof JMenuItem i)
                commands.add(i.getActionCommand());
        return commands;
    }

    private static JMenuItem item(JPopupMenu menu, String command) {
        for (var c : menu.getComponents())
            if (c instanceof JMenuItem i && command.equals(i.getActionCommand()))
                return i;
        return null;
    }

    private static boolean hasColumn(Buffer b) {
        for (GutterColumn c : b.getGutterColumns())
            if (c instanceof BlameColumn)
                return true;
        return false;
    }

    private static String text(Buffer b) {
        final StringBuilder s = new StringBuilder();
        for (var l = b.getFirstLine(); l != null; l = l.next())
            s.append(l.getText()).append('\n');
        return s.toString();
    }

    private void commitAs(String author, String message) {
        git.git("add", "-A");
        git.git("commit", "-q", "--author=" + author + " <a@example.com>", "-m", message);
    }

    private void open(String path) throws Exception {
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(git.root.resolve(path).toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            h.showBuffer(file);
        });
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void until(java.util.function.BooleanSupplier done) throws Exception {
        for (int i = 0; i < 400; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out");
    }
}
