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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Getting around conflicts: next and previous, the context menu, and the toolbar group. */
public class ConflictCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;

    @AfterEach
    public void tearDown() throws Exception {
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
        until(() -> file.getConflicts() == null);
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
