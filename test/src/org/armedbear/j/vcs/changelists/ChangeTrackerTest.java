/*
 * ChangeTrackerTest.java
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

package org.armedbear.j.vcs.changelists;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.EditCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Change;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ChangeTrackerTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer buffer;

    private static final String THIRTY;

    static {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++)
            sb.append("line ").append(i).append('\n');
        THIRTY = sb.toString();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (buffer != null) {
            buffer.setSignsUpdater(null);
            Editor.getBufferList().remove(buffer);
        }
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        RepositoryLists.forget();
        GitFixture.reset();
    }

    // a.txt with line 5 and line 20 changed, line 5's change kept in X,
    // the active list Y; then opened.
    private void open() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", THIRTY);
        git.commitAll("first");
        git.write("a.txt", THIRTY.replace("line 5\nline 6\n", "FIVE\nSIX\n").replace("line 20\n", "TWENTY\n"));
        final VcsRepository r = Repositories.forDirectory(git.root);
        final List<Placed> placed = Anchors.place(
            Changelists.initial(),
            "a.txt",
            null,
            DiffParser.parse(git.git("diff", "--no-ext-diff", "a.txt")).get(0));
        final List<Change> cs = Anchors.assignment(placed).changes();
        final Change five = cs.get(0);
        assertTrue(
            ChangelistStore.of(r)
                    .write(
                        Changelists.initial()
                                .withList("X")
                                .withList("Y")
                                .withActive("Y")
                                .withFile(
                                    "a.txt",
                                    Assignment.of(
                                        List.of(
                                            new Change(five.hash(),
                                                    five.row(),
                                                    five.rows(),
                                                    five.oldRow(),
                                                    five.oldRows(),
                                                    "X",
                                                    List.of()),
                                            cs.get(1))))));
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            buffer = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!buffer.initialized())
                buffer.initialize();
            buffer.load();
            h.showBuffer(buffer);
        });
        until(() -> tracker() != null && "X".equals(listOf(4)));
    }

    private ChangeTracker tracker() {
        final SignsUpdater u = buffer.getSignsUpdater();
        return u == null ? null : u.tracker();
    }

    private String listOf(int row) {
        return tracker().listOf(buffer.getLine(row));
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + describe());
    }

    // Each row's list, as the tracker has it.
    private String describe() {
        final StringBuilder sb = new StringBuilder();
        for (Line l = buffer.getFirstLine(); l != null; l = l.next())
            sb.append(l.lineNumber()).append('=').append(tracker() == null ? null : tracker().listOf(l)).append(' ');
        return sb.toString();
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    // Replaces row n's text, as typing would.
    private void change(int n, String text) throws Exception {
        onEdt(() -> {
            final Line l = buffer.getLine(n);
            h.editor().deleteRegion(new Position(l, 0), new Position(l, l.length()));
            h.editor().insertString(text);
        });
    }

    @Test
    public void editsJoinTheListOfTheRangeTheyAreInElseTheActiveOne() throws Exception {
        open();
        onEdt(() -> assertEquals(Changelists.DEFAULT, listOf(19), "kept in the default"));
        // Typed in X's range: X.
        change(4, "FIVE!");
        until(() -> "X".equals(listOf(4)) && buffer.getLine(4).getText().equals("FIVE!"));
        // A line added inside it, between FIVE and SIX: X.
        onEdt(() -> {
            final Line l = buffer.getLine(4);
            h.editor().setDot(l, l.length());
            h.editor().insertString("\nnew");
        });
        until(() -> "X".equals(listOf(5)) && "X".equals(listOf(6)));
        // The line below, unchanged till now, edited: at the range's edge,
        // outside it, the active list's, the range still X.
        change(7, "SEVEN");
        until(() -> "Y".equals(listOf(7)));
        onEdt(() -> assertEquals("X", listOf(6)));
        // Elsewhere, line 12, a row down now: the active list, Y.
        change(12, "TWELVE");
        until(() -> "Y".equals(listOf(12)));
        // Put back as it was: the range emptied, no list.
        change(12, "line 12");
        until(() -> listOf(12) == null);
        onEdt(() -> assertEquals(Changelists.DEFAULT, listOf(20), "the default's change left in it"));
    }

    @Test
    public void undoBringsARangeBackInItsList() throws Exception {
        open();
        // Line 5's change and the lines around it deleted: its lines gone.
        onEdt(() -> h.editor().deleteRegion(new Position(buffer.getLine(3), 0), new Position(buffer.getLine(6), 0)));
        until(() -> buffer.getLine(3).getText().equals("line 7") && "Y".equals(listOf(2)));
        onEdt(() -> EditCommands.undo(h.editor()));
        until(() -> buffer.getLine(4).getText().equals("FIVE") && "X".equals(listOf(4)));
    }

    @Test
    public void savingKeepsTheRangesWhereTheyAreNow() throws Exception {
        open();
        // Two lines added at the top, in the active list, and saved.
        onEdt(() -> {
            h.editor().setDot(buffer.getFirstLine(), 0);
            h.editor().insertString("a\nb\n");
        });
        until(() -> "X".equals(listOf(6)) && "Y".equals(listOf(0)));
        onEdt(() -> buffer.save());
        final ChangelistStore store = ChangelistStore.of(Repositories.forDirectory(git.root));
        until(() -> {
            final Assignment a = store.read().assignment("a.txt");
            return a != null && a.changes().size() == 3;
        });
        final List<Change> kept = store.read().assignment("a.txt").changes();
        assertEquals(List.of("Y", "X", Changelists.DEFAULT), kept.stream().map(Change::list).toList());
        assertEquals(6, kept.get(1).row(), "X's change two rows down");
        assertEquals(List.of(), kept.get(0).parts());
    }

    @Test
    public void aFileKeptWholeAsAnOlderStoreHasItSendsNewEditsToTheActiveList() throws Exception {
        open();
        // The file kept whole in X.
        final ChangelistStore store = ChangelistStore.of(Repositories.forDirectory(git.root));
        onEdt(() -> {
            assertTrue(
                RepositoryLists.of(Repositories.forDirectory(git.root))
                        .update(c -> c.withFile("a.txt", Assignment.whole("X"))));
            tracker().reload();
            buffer.getSignsUpdater().changed();
        });
        until(() -> "X".equals(listOf(19)));
        // An edit elsewhere: the active list's, Y.
        change(12, "TWELVE");
        until(() -> "Y".equals(listOf(12)));
        onEdt(() -> buffer.save());
        until(() -> {
            final Assignment a = store.read().assignment("a.txt");
            return a != null && a.whole() == null && a.changes().size() == 3;
        });
    }

    // A diff of one hunk: from, 1-based, adding added after the lines before it.
    private static org.armedbear.j.vcs.diff.Hunk adding(int from, String... added) {
        final StringBuilder sb = new StringBuilder("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n");
        sb.append("@@ -").append(from - 1).append(",0 +").append(from).append(',').append(added.length).append(" @@\n");
        for (String a : added)
            sb.append('+').append(a).append('\n');
        return DiffParser.parse(sb.toString()).get(0).hunks().get(0);
    }

    @Test
    public void linesADiffAlignsIntoAChangeOtherwiseKeepItsList() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "x\n");
        git.commitAll("first");
        final VcsRepository r = Repositories.forDirectory(git.root);
        onEdt(() -> {
            final RepositoryLists lists = RepositoryLists.of(r);
            assertTrue(lists.update(c -> c.withList("X").withList("Y").withActive("Y")));
            final ChangeTracker t = new ChangeTracker(lists, "a.txt");
            // "}", "", a block, "}", "": the block added after the first
            // "}" and "", as git aligns it, or before the second, as j's may.
            final List<Line> lines = List.of(
                new org.armedbear.j.TextLine("}"),
                new org.armedbear.j.TextLine(""),
                new org.armedbear.j.TextLine("b"),
                new org.armedbear.j.TextLine("}"),
                new org.armedbear.j.TextLine(""));
            final org.armedbear.j.vcs.diff.Hunk git = adding(3, "b", "}", "");
            final org.armedbear.j.vcs.diff.Hunk j = adding(1, "}", "", "b");
            t.update(List.of(new ChangeTracker.Now(git, git.runs().get(0), lines.subList(2, 5), null)), false, lines);
            // Placed in Y, the active list, then moved to X.
            assertTrue(t.move(java.util.Set.copyOf(lines.subList(2, 5)), "X") != ChangeTracker.Moved.NOTHING);
            // Aligned the other way: the lines it takes in, there before
            // with their text, X's too.
            t.update(List.of(new ChangeTracker.Now(j, j.runs().get(0), lines.subList(0, 3), null)), false, lines);
            assertEquals("X", t.listOf(lines.get(0)));
            assertEquals("X", t.listOf(lines.get(1)));
            assertEquals("X", t.listOf(lines.get(2)));
            // And back.
            t.update(List.of(new ChangeTracker.Now(git, git.runs().get(0), lines.subList(2, 5), null)), false, lines);
            assertEquals("X", t.listOf(lines.get(3)));
            assertEquals("X", t.listOf(lines.get(4)));
        });
    }
}
