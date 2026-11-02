/*
 * ConflictCommandsTest.java
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

package org.armedbear.j.vcs.conflicts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.ChangeMarks;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.ToolBar;
import org.armedbear.j.ToolBarButton;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusActions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Getting around conflicts: next and previous, the context menu, and the toolbar group. */
public class ConflictCommandsTest {
    @TempDir
    Path tmp;

    private static final StatusActions.Confirm CONFLICT_CONFIRM = StatusActions.confirm;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;

    @AfterEach
    public void tearDown() throws Exception {
        StatusActions.confirm = CONFLICT_CONFIRM;
        onEdt(() -> {
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
            if (file != null)
                Editor.getBufferList().remove(file);
        });
        GitFixture.reset();
    }

    // A merge with two conflicts, each with a base: zdiff3. Openers on
    // lines 1 and 16 (0-based), closers on 7 and 22.
    private void twoConflicts() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "merge.conflictStyle", "zdiff3");
        git.write("a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "1\nX2\n3\n4\n5\n6\n7\n8\n9\n10\nX11\n12\n13\n14\n15\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "1\nY2\n3\n4\n5\n6\n7\n8\n9\n10\nY11\n12\n13\n14\n15\n");
        git.commitAll("ours");
        git.run("merge", "other");
    }

    // A two-conflict merge with no base: a two-way merge.
    private void twoConflictsNoBase() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "merge.conflictStyle", "merge");
        git.write("a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "1\nX2\n3\n4\n5\n6\n7\n8\n9\n10\nX11\n12\n13\n14\n15\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "1\nY2\n3\n4\n5\n6\n7\n8\n9\n10\nY11\n12\n13\n14\n15\n");
        git.commitAll("ours");
        git.run("merge", "other");
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

    // Take every region ours, one at a time, until none is left.
    private void takeAllOurs() throws Exception {
        for (int i = 0; i < 10; i++) {
            final int[] first = new int[1];
            onEdt(() -> {
                final Conflicts c = file.getConflicts();
                first[0] = c == null || c.openers().isEmpty() ? -1 : c.openers().get(0).lineNumber();
            });
            if (first[0] < 0)
                return;
            dot(first[0] + 1); // inside the region
            onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
            until(() -> {
                final Conflicts c = file.getConflicts();
                return c == null || c.openers().isEmpty() || c.openers().get(0).lineNumber() != first[0];
            });
        }
    }

    // ---- take-all

    @Test
    public void takeAllOursTakesEveryRegionAsOneUndo() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(20);
        onEdt(() -> ConflictCommands.vcsConflictAllOurs(h.editor()));
        // Both regions gone, ours everywhere.
        assertFalse(text().contains("<<<<<<<"), text());
        assertTrue(text().contains("Y2"), text());
        assertTrue(text().contains("Y11"), text());
        assertFalse(text().contains("X2"), text());
        assertFalse(text().contains("X11"), text());
        // One undo restores both regions whole, and the caret.
        onEdt(() -> org.armedbear.j.EditCommands.undo(h.editor()));
        assertTrue(text().contains("<<<<<<<"), "markers back");
        assertFalse(file.isModified(), "unmodified");
        assertEquals(20, h.editor().getDotLineNumber());
        until(() -> file.getConflicts() != null && file.getConflicts().openers().size() == 2);
    }

    @Test
    public void oneUndoRestoresATakeAndTheCaretMovedOn() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(2);
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        assertTrue(h.editor().getDotLineNumber() > 2, "on to the next conflict");
        onEdt(() -> org.armedbear.j.EditCommands.undo(h.editor()));
        assertEquals(2, text().split("<<<<<<<", -1).length - 1, "both regions back");
        assertFalse(file.isModified(), "unmodified");
        assertEquals(2, h.editor().getDotLineNumber());
    }

    @Test
    public void takeAllTheirsTakesEveryRegion() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        onEdt(() -> ConflictCommands.vcsConflictAllTheirs(h.editor()));
        assertFalse(text().contains("<<<<<<<"), text());
        assertTrue(text().contains("X2"), text());
        assertTrue(text().contains("X11"), text());
        assertFalse(text().contains("Y2"), text());
    }

    @Test
    public void takeAllBothKeepsOursThenTheirs() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        onEdt(() -> ConflictCommands.vcsConflictAllBoth(h.editor()));
        assertFalse(text().contains("<<<<<<<"), text());
        // Each region: ours then theirs, no base.
        assertTrue(text().contains("Y2\nX2"), text());
        assertTrue(text().contains("Y11\nX11"), text());
    }

    @Test
    public void takeAllKeepsCleanlyMergedLinesElsewhere() throws Exception {
        // A file where theirs also changed a line that merges cleanly: the
        // take-all keeps it, unlike the status's checkout --ours.
        git = GitFixture.init(tmp);
        git.git("config", "merge.conflictStyle", "zdiff3");
        git.write("a.txt", "a\nb\nc\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "a\nTHEIRS\nc\nCHANGED BY THEM\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "a\nOURS\nc\n");
        git.commitAll("ours");
        git.run("merge", "other");
        open("a.txt");
        dot(3);
        onEdt(() -> ConflictCommands.vcsConflictAllOurs(h.editor()));
        assertTrue(text().contains("CHANGED BY THEM"), "theirs' clean change kept: " + text());
        assertTrue(text().contains("OURS"), text());
    }

    @Test
    public void takeAllIsRefusedInAReadOnlyBuffer() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        onEdt(() -> {
            file.setForceReadOnly(true);
            ConflictCommands.vcsConflictAllOurs(h.editor());
        });
        assertTrue(text().contains("<<<<<<<"), "nothing changed");
        assertTrue(h.status().contains("read only"), h.status());
    }

    @Test
    public void takeAllWithNoConflictSaysSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "clean\n");
        git.commitAll("clean");
        open("a.txt");
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictAllOurs(h.editor()));
        assertEquals("No conflicts here", h.status());
    }

    // ---- marking resolved

    @Test
    public void resolvedRefusesWithRegionsLeft() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        assertEquals("2 conflicts are left", h.status());
        assertFalse(staged("a.txt"), "nothing staged");
    }

    @Test
    public void resolvedStagesTheFileAndDropsTheConflicts() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        onEdt(() -> file.save());
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        until(() -> resolvedNow());
        assertTrue(staged("a.txt"), "staged");
        assertTrue(file.getVirtualText().get(Conflicts.OWNER).isEmpty(), "nothing shown");
        assertEquals("Marked a.txt resolved", h.status());
    }

    @Test
    public void resolvedSavesAModifiedBufferFirst() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        // Modified, not saved: the command saves, then stages.
        assertTrue(file.isModified(), "modified before");
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        until(() -> resolvedNow());
        assertFalse(file.isModified(), "saved");
        assertTrue(staged("a.txt"), "staged");
    }

    @Test
    public void resolvedAsksWithAStrayMarkerLeft() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        onEdt(() -> file.save());
        // A stray opener with no closer on disk: no region, but a marker.
        git.write("a.txt", text() + "<<<<<<< HEAD\n");
        final List<String> asked = new ArrayList<>();
        StatusActions.confirm = (e, q, title) -> {
            asked.add(title + ": " + q);
            return true;
        };
        try {
            onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
            until(() -> resolvedNow());
            assertEquals(List.of("Mark Resolved: Mark a.txt resolved? 1 conflict is left."), asked);
            assertTrue(staged("a.txt"), "staged after yes");
        }
        finally {
            StatusActions.confirm = CONFLICT_CONFIRM;
        }
    }

    @Test
    public void resolvedDoesNotStageWhenTheStrayAskIsRefused() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        onEdt(() -> file.save());
        git.write("a.txt", text() + "<<<<<<< HEAD\n");
        final AtomicBoolean asked = new AtomicBoolean();
        StatusActions.confirm = (e, q, title) -> {
            asked.set(true);
            return false;
        };
        try {
            onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
            until(asked::get);
            assertFalse(staged("a.txt"), "not staged when refused");
            assertNotNull(file.getConflicts(), "still unmerged");
        }
        finally {
            StatusActions.confirm = CONFLICT_CONFIRM;
        }
    }

    @Test
    public void resolvedSaysSoForAFileNotUnmerged() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "clean\n");
        git.commitAll("clean");
        open("a.txt");
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        assertEquals("Not an unmerged file", h.status());
    }

    // ---- a two-repository project stages in the file's own repository

    @Test
    public void resolvedStagesInOwnRepositoryOfAProject() throws Exception {
        // A .j-project with two repositories; the conflicted file is in
        // the second. It stages there, not the first.
        Path ws = tmp.resolve("ws");
        java.nio.file.Files.createDirectories(ws.resolve(".j-project"));
        java.nio.file.Files.createDirectories(ws.resolve("a-repo"));
        java.nio.file.Files.createDirectories(ws.resolve("b-repo"));
        GitFixture a = GitFixture.init(ws.resolve("a-repo"));
        GitFixture b = GitFixture.init(ws.resolve("b-repo"));
        // An a.txt in a-repo too, changed: staged there, the stage went astray.
        a.write("a.txt", "a\n");
        a.commitAll("a");
        a.write("a.txt", "changed\n");
        b.git("config", "merge.conflictStyle", "zdiff3");
        b.write("a.txt", "one\ntwo\nthree\n");
        b.commitAll("base");
        b.git("checkout", "-q", "-b", "other");
        b.write("a.txt", "one\nTHEIRS\nthree\n");
        b.commitAll("theirs");
        b.git("checkout", "-q", "main");
        b.write("a.txt", "one\nOURS\nthree\n");
        b.commitAll("ours");
        b.run("merge", "other");
        Repositories.invalidateAll();
        openAt(ws.resolve("b-repo/a.txt"));
        assertNotNull(file.getConflicts(), "attached in b-repo");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        onEdt(() -> file.save());
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        until(() -> resolvedNow());
        assertTrue(b.run("ls-files", "--", "a.txt").out().contains("a.txt"), "tracked in b-repo");
        assertFalse(b.run("ls-files", "-u", "--", "a.txt").out().contains("a.txt"), "no unmerged in b-repo");
        assertTrue(a.run("diff", "--cached", "--name-only").out().isEmpty(), "nothing staged in a-repo");
    }

    // ---- marking unresolved

    @Test
    public void undoAfterResolvedOffersTheConflictBackAndUnresolvedPutsItBack() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(20);
        onEdt(() -> ConflictCommands.vcsConflictAllOurs(h.editor()));
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        until(() -> resolvedNow());
        assertTrue(staged("a.txt"), "staged");
        final String saved = git.read("a.txt");
        // The undo brings the markers back into the buffer: shaded, with the conflict offered back.
        onEdt(() -> org.armedbear.j.EditCommands.undo(h.editor()));
        until(() -> file.getConflicts().openers().size() == 2);
        assertEquals("2 conflicts in a file marked resolved · Mark unresolved", topRow());
        onEdt(() -> ConflictCommands.vcsConflictResolved(h.editor()));
        assertEquals("Already marked resolved", h.status());
        onEdt(() -> ConflictCommands.vcsConflictUnresolved(h.editor()));
        until(() -> file.getConflicts() != null && !file.getConflicts().resolved());
        assertTrue(unmerged(git, "a.txt"), "unmerged again");
        assertEquals(saved, git.read("a.txt"), "the file as it was");
        assertTrue(text().contains("<<<<<<<"), "the buffer's markers kept");
        assertTrue(file.isModified(), "the undo still to save");
        assertTrue(topRow().startsWith("2 conflicts"), topRow());
        assertEquals("Marked a.txt unresolved", h.status());
    }

    @Test
    public void unresolvedRefusesAFileNotMarkedResolved() throws Exception {
        twoConflicts();
        open("a.txt");
        onEdt(() -> ConflictCommands.vcsConflictUnresolved(h.editor()));
        assertEquals("Not marked resolved", h.status());
    }

    @Test
    public void unresolvedRefusesWithNoConflictToPutBack() throws Exception {
        twoConflicts();
        git.write("b.txt", "b\n"); // not in the merge's conflicts
        open("b.txt");
        onEdt(() -> ConflictCommands.vcsConflictUnresolved(h.editor()));
        until(() -> h.status().startsWith("No conflict"));
        assertEquals("No conflict of b.txt to put back", h.status());
    }

    @Test
    public void unresolvedRefusesOnceTheMergeIsCommitted() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        onEdt(() -> file.save());
        git.git("add", "a.txt");
        git.git("commit", "-q", "--no-edit");
        final org.armedbear.j.vcs.VcsRepository r = Repositories.forFile(file.getFile());
        r.invalidate();
        r.refresh();
        until(() -> file.getConflicts() == null);
        onEdt(() -> ConflictCommands.vcsConflictUnresolved(h.editor()));
        until(() -> h.status().startsWith("No merge"));
        assertEquals("No merge, rebase or stash pop in progress to put a conflict back in", h.status());
        assertFalse(unmerged(git, "a.txt"), "left resolved");
    }

    // ---- helpers

    // The file staged, its Conflicts kept on, marked resolved, while the merge goes on.
    private boolean resolvedNow() {
        return file.getConflicts() != null && file.getConflicts().resolved();
    }

    private String topRow() throws Exception {
        final String[] out = new String[1];
        onEdt(
            () -> out[0] = file.getVirtualText()
                    .get(Conflicts.OWNER)
                    .stream()
                    .filter(v -> v.placement() == org.armedbear.j.VirtualText.Placement.TOP)
                    .map(v -> org.armedbear.j.VirtualText.text(v.rows().get(0)))
                    .findFirst()
                    .orElse(""));
        return out[0];
    }

    // Whether a.txt is resolved: no unmerged entries in the index. The
    // content may equal HEAD after take-all, so porcelain can be clean;
    // what matters is the unmerged stage entries are gone.
    private boolean staged(String path) {
        return !unmerged(git, path);
    }

    private static boolean unmerged(GitFixture f, String path) {
        return !f.run("ls-files", "-u", "--", path).out().isEmpty();
    }

    // Opens a file outside the harness's own git fixture.
    private void openAt(Path path) throws Exception {
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(path.toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            h.showBuffer(file);
        });
    }

    private String text() {
        final List<String> lines = new ArrayList<>();
        for (Line l = file.getFirstLine(); l != null; l = l.next())
            lines.add(l.getText());
        return String.join("\n", lines);
    }

    // ---- after a take

    @Test
    public void takingAConflictGoesToTheNext() throws Exception {
        twoConflicts();
        open("a.txt");
        final Line second = line(16);
        dot(3);
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        assertSame(second, h.editor().getDotLine());
    }

    @Test
    public void takingTheLastGoesAroundToTheFirst() throws Exception {
        twoConflicts();
        open("a.txt");
        final Line first = line(1);
        dot(20);
        onEdt(() -> ConflictCommands.vcsConflictTheirs(h.editor()));
        assertSame(first, h.editor().getDotLine());
    }

    @Test
    public void takingTheOnlyOneLeftStaysAtItsLines() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        // The rescan, so the second is the only one left.
        onEdt(() -> {});
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        assertEquals("Y11", h.editor().getDotLine().getText());
    }

    // ---- next and previous

    @Test
    public void nextFromBeforeGoesToTheFirstConflict() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictNext(h.editor()));
        assertEquals(1, h.editor().getDotLineNumber());
        assertFalse(wrapped(), h.status());
    }

    @Test
    public void nextFromInsideARegionGoesToTheOneAfter() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3); // inside the first region
        onEdt(() -> ConflictCommands.vcsConflictNext(h.editor()));
        assertEquals(16, h.editor().getDotLineNumber());
        assertFalse(wrapped(), h.status());
    }

    @Test
    public void nextPastTheLastWrapsToTheFirst() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(20); // inside the second region
        onEdt(() -> ConflictCommands.vcsConflictNext(h.editor()));
        assertEquals(1, h.editor().getDotLineNumber());
        assertEquals("Wrapped to the first conflict", h.status());
    }

    @Test
    public void previousFromAfterGoesToTheLastConflict() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(24); // past both
        onEdt(() -> ConflictCommands.vcsConflictPrevious(h.editor()));
        assertEquals(16, h.editor().getDotLineNumber());
        assertFalse(wrapped(), h.status());
    }

    @Test
    public void previousFromInsideGoesToItsOpener() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(20); // inside the second region
        onEdt(() -> ConflictCommands.vcsConflictPrevious(h.editor()));
        assertEquals(16, h.editor().getDotLineNumber());
        assertFalse(wrapped(), h.status());
    }

    @Test
    public void previousBeforeTheFirstWrapsToTheLast() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictPrevious(h.editor()));
        assertEquals(16, h.editor().getDotLineNumber());
        assertEquals("Wrapped to the last conflict", h.status());
    }

    @Test
    public void nextWithNoConflictSaysSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "clean\n");
        git.commitAll("clean");
        open("a.txt");
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictNext(h.editor()));
        assertEquals("No conflicts", h.status());
    }

    @Test
    public void nextWithEveryRegionTakenSaysSo() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictNext(h.editor()));
        assertEquals("No conflicts", h.status());
    }

    // ---- nextChange and previousChange go by conflict in an unmerged file

    @Test
    public void nextChangeGoesByConflictInAnUnmergedFile() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(0);
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertEquals(1, h.editor().getDotLineNumber(), "to the first conflict's opener");
    }

    @Test
    public void previousChangeGoesByConflictInAnUnmergedFile() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(24);
        onEdt(() -> ChangeMarks.previousChange(h.editor()));
        assertEquals(16, h.editor().getDotLineNumber(), "to the last conflict's opener");
    }

    @Test
    public void hunkStartsGoesByConflictWhileUnmerged() throws Exception {
        twoConflicts();
        open("a.txt");
        final List<Line> starts = new ArrayList<>();
        onEdt(() -> starts.addAll(ChangeMarks.hunkStarts(h.editor())));
        assertEquals(file.getConflicts().openers(), starts, "the conflict openers, not change marks");
    }

    @Test
    public void hunkStartsGoesByChangeAgainOnceStaged() throws Exception {
        twoConflicts();
        open("a.txt");
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        git.git("add", "a.txt");
        refresh();
        until(() -> resolvedNow());
        final List<Line> starts = new ArrayList<>();
        onEdt(() -> starts.addAll(ChangeMarks.hunkStarts(h.editor())));
        // No conflicts left: the buffer's own change starts (lines taken
        // since load), not the conflict openers it went by before.
        assertFalse(starts.isEmpty(), "the buffer has its own changes");
        dot(0);
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertFalse(h.status().contains("conflict"), "went by change, not conflict: " + h.status());
    }

    // ---- context menu

    @Test
    public void theContextMenuHasTheTakesInARegion() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3); // inside the first region, which has a base
        final List<String> items = menuItems();
        assertTrue(items.contains("Keep HEAD"), items.toString());
        assertTrue(items.contains("Keep other"), items.toString());
        assertTrue(items.contains("Keep Both"), items.toString());
        assertTrue(items.contains("Keep Base"), items.toString());
        assertTrue(items.contains("Next Conflict"), items.toString());
        assertTrue(items.contains("Previous Conflict"), items.toString());
    }

    @Test
    public void theContextMenuHasNoBaseWithoutABase() throws Exception {
        twoConflictsNoBase();
        open("a.txt");
        dot(3); // inside a two-way region
        final List<String> items = menuItems();
        assertTrue(items.contains("Keep Both"), items.toString());
        assertFalse(items.contains("Keep Base"), items.toString());
    }

    @Test
    public void theContextMenuHasNothingOutsideARegion() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(0); // before the first region
        assertTrue(menuItems().isEmpty(), menuItems().toString());
    }

    // ---- toolbar group

    @Test
    public void theToolbarGroupShowsWithConflicts() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        final ToolBar tb = group();
        assertTrue(button(tb, "vcsConflictNext").isVisible(), "next shown");
        assertTrue(button(tb, "vcsConflictPrevious").isVisible(), "previous shown");
        assertTrue(button(tb, "vcsConflictOurs").isEnabled(), "ours enabled in a region");
        assertTrue(button(tb, "vcsConflictTheirs").isEnabled(), "theirs enabled in a region");
        assertTrue(button(tb, "vcsConflictBoth").isEnabled(), "both enabled in a region");
        assertTrue(button(tb, "vcsConflictBase").isEnabled(), "base enabled in a region with a base");
        assertTrue(separatorShown(tb), "the separator shown with the group");
    }

    @Test
    public void theTakesDisableOutsideARegion() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(0); // outside any region
        final ToolBar tb = group();
        assertTrue(button(tb, "vcsConflictNext").isEnabled(), "next enabled anywhere in the file");
        assertTrue(button(tb, "vcsConflictPrevious").isEnabled(), "previous enabled anywhere in the file");
        assertFalse(button(tb, "vcsConflictOurs").isEnabled(), "ours disabled outside a region");
        assertFalse(button(tb, "vcsConflictBase").isEnabled(), "base disabled outside a region");
    }

    @Test
    public void theBaseDisablesInATwoWayRegion() throws Exception {
        twoConflictsNoBase();
        open("a.txt");
        dot(3); // inside a two-way region
        final ToolBar tb = group();
        assertTrue(button(tb, "vcsConflictOurs").isEnabled(), "ours enabled in a region");
        assertFalse(button(tb, "vcsConflictBase").isEnabled(), "base disabled with no base");
    }

    @Test
    public void theGroupHidesWhenTheBufferHasNoConflicts() throws Exception {
        twoConflicts();
        open("a.txt");
        dot(3);
        final ToolBar tb = group();
        assertTrue(button(tb, "vcsConflictNext").isVisible(), "shown with conflicts");
        // Take both and stage: the buffer has no conflicts, so the group
        // and its separator go.
        takeAllOurs();
        until(() -> file.getConflicts() != null && file.getConflicts().openers().isEmpty());
        tb.update();
        assertFalse(button(tb, "vcsConflictNext").isVisible(), "hidden with no conflicts");
        assertFalse(separatorShown(tb), "the separator hidden with the group");
    }

    // ---- helpers

    // A toolbar with the conflict group, reading the harness editor.
    private ToolBar group() throws Exception {
        final ToolBar[] tb = new ToolBar[1];
        onEdt(() -> {
            tb[0] = new ToolBar(null);
            ConflictCommands.addToolBarGroup(tb[0], () -> h.editor());
            tb[0].update();
        });
        return tb[0];
    }

    private static ToolBarButton button(ToolBar tb, String command) {
        for (Component c : tb.getComponents())
            if (c instanceof ToolBarButton b && command.equals(b.getActionCommand()))
                return b;
        throw new AssertionError("no button for " + command);
    }

    // Whether the group's separator (the one before the buttons) is shown.
    private boolean separatorShown(ToolBar tb) {
        for (Component c : tb.getComponents())
            if (c instanceof javax.swing.JToolBar.Separator)
                return c.isVisible();
        throw new AssertionError("no separator");
    }

    private List<String> menuItems() throws Exception {
        final JPopupMenu popup = new JPopupMenu();
        onEdt(() -> ConflictCommands.addContextMenuItems(h.editor(), popup));
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < popup.getComponentCount(); i++)
            if (popup.getComponent(i) instanceof javax.swing.JMenuItem item)
                out.add(item.getText());
        return out;
    }

    private boolean wrapped() {
        return h.status() != null && h.status().startsWith("Wrapped");
    }

    private void refresh() throws Exception {
        onEdt(() -> {
            Repositories.forFile(file.getFile()).invalidate();
            file.checkVCS();
        });
    }

    private void dot(int n) throws Exception {
        final Line l = line(n);
        onEdt(() -> h.editor().setDot(l, 0));
    }

    private Line line(int n) {
        Line l = file.getFirstLine();
        for (int i = 0; i < n; i++)
            l = l.next();
        return l;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private void until(java.util.function.BooleanSupplier done) throws Exception {
        for (int i = 0; i < 400; i++) {
            final boolean[] ok = new boolean[1];
            onEdt(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out");
    }
}
