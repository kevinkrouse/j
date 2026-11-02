/*
 * ConflictsTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.VirtualText;
import org.armedbear.j.VirtualText.Placement;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A buffer's conflicts: the shades and links while its file is unmerged, and the four takes. */
public class ConflictsTest {
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

    // A merge conflict on a.txt, with the base shown: zdiff3.
    private void conflict() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "merge.conflictStyle", "zdiff3");
        git.write("a.txt", "one\ntwo\nthree\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "one\nTHEIRS\nthree\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "one\nOURS\nthree\n");
        git.commitAll("ours");
        git.run("merge", "other");
    }

    // The same, without a base: a two-way merge.
    private void twoWay() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "merge.conflictStyle", "merge");
        git.write("a.txt", "one\ntwo\nthree\n");
        git.commitAll("base");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "one\nTHEIRS\nthree\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "one\nOURS\nthree\n");
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

    private List<VirtualText> shades() throws Exception {
        final List<VirtualText>[] got = new List[1];
        onEdt(() -> got[0] = file.getVirtualText().get(Conflicts.OWNER));
        return got[0] == null ? List.of() : got[0];
    }

    private long count(Placement placement) throws Exception {
        return shades().stream().filter(v -> v.placement() == placement).count();
    }

    private void refresh() throws Exception {
        onEdt(() -> {
            Repositories.forFile(file.getFile()).invalidate();
            file.checkVCS();
        });
    }

    private String text() {
        final List<String> lines = new java.util.ArrayList<>();
        for (Line l = file.getFirstLine(); l != null; l = l.next())
            lines.add(l.getText());
        // j keeps the empty last line for the file's trailing newline;
        // joining with \n puts it back as the newline.
        return String.join("\n", lines);
    }

    @Test
    public void anUnmergedFileIsShadedWithLinks() throws Exception {
        conflict();
        open("a.txt");
        assertNotNull(file.getConflicts(), "attached");
        // The region: opener, ours, base marker, base, separator, theirs, closer.
        assertEquals(7, count(Placement.BACKGROUND), "every line of the region shaded");
        assertEquals(1, count(Placement.ABOVE), "one link row");
        final VirtualText row =
                shades().stream().filter(v -> v.placement() == Placement.ABOVE).findFirst().orElseThrow();
        final String links = VirtualText.text(row.rows().get(0));
        // Named from the markers' own labels: the opener's and the closer's.
        assertTrue(links.contains("Keep HEAD"), links);
        assertTrue(links.contains("Keep other"), links);
        assertTrue(links.contains("Both"), links);
        assertTrue(links.contains("Base"), links);
    }

    @Test
    public void aTwoWayRegionHasNoBaseLink() throws Exception {
        twoWay();
        open("a.txt");
        final VirtualText row =
                shades().stream().filter(v -> v.placement() == Placement.ABOVE).findFirst().orElseThrow();
        final String links = VirtualText.text(row.rows().get(0));
        assertFalse(links.contains("Base"), links);
    }

    @Test
    public void markersInAFileNotUnmergedAreLeftAlone() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> x\n");
        git.commitAll("text");
        open("a.txt");
        assertNull(file.getConflicts(), "not attached");
        assertTrue(shades().isEmpty());
    }

    @Test
    public void typingAboveARegionMovesItsShading() throws Exception {
        conflict();
        open("a.txt");
        final Line opener = line(1);
        dot(0);
        onEdt(() -> {
            h.editor().insertString("typed\n");
            file.renumber();
        });
        until(() -> file.getConflicts() != null && file.getConflicts().regionAt(opener) != null);
        assertEquals(2, opener.lineNumber(), "the opener moved down with the line");
        assertFalse(file.getVirtualText().on(opener, Placement.BACKGROUND).isEmpty(), "still shaded");
    }

    @Test
    public void deletingTheCloserByHandDropsTheRegion() throws Exception {
        conflict();
        open("a.txt");
        // The closer is the ">>>>>>>" line; delete it whole.
        final Line closer = line(7);
        assertTrue(closer.getText().startsWith(">>>>>>>"), closer.getText());
        onEdt(() -> {
            h.editor().setDot(closer.previous(), closer.previous().length());
            h.editor().setMark(new Position(closer, closer.length()));
            h.editor().deleteRegion();
        });
        until(() -> file.getConflicts() != null && file.getConflicts().regionAt(line(1)) == null);
        assertEquals(0, count(Placement.ABOVE), "no links left");
    }

    @Test
    public void stagingTheFileDropsItAll() throws Exception {
        conflict();
        open("a.txt");
        assertNotNull(file.getConflicts());
        git.git("add", "a.txt");
        refresh();
        until(() -> file.getConflicts() == null);
        assertTrue(shades().isEmpty(), "cleared");
    }

    @Test
    public void oursTakesOurLines() throws Exception {
        conflict();
        open("a.txt");
        dot(1);
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        assertEquals("one\nOURS\nthree\n", text());
    }

    @Test
    public void theirsTakesTheirLines() throws Exception {
        conflict();
        open("a.txt");
        dot(1);
        onEdt(() -> ConflictCommands.vcsConflictTheirs(h.editor()));
        assertEquals("one\nTHEIRS\nthree\n", text());
    }

    @Test
    public void bothTakesOursThenTheirs() throws Exception {
        conflict();
        open("a.txt");
        dot(1);
        onEdt(() -> ConflictCommands.vcsConflictBoth(h.editor()));
        assertEquals("one\nOURS\nTHEIRS\nthree\n", text());
    }

    @Test
    public void baseTakesTheBase() throws Exception {
        conflict();
        open("a.txt");
        dot(1);
        onEdt(() -> ConflictCommands.vcsConflictBase(h.editor()));
        assertEquals("one\ntwo\nthree\n", text());
    }

    @Test
    public void oneUndoRestoresTheRegion() throws Exception {
        conflict();
        open("a.txt");
        dot(1);
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        onEdt(() -> org.armedbear.j.EditCommands.undo(h.editor()));
        assertTrue(text().contains("<<<<<<<"), "the markers back");
        assertNotNull(file.getConflicts().regionAt(line(1)));
    }

    @Test
    public void baseIsRefusedWithoutABase() throws Exception {
        twoWay();
        open("a.txt");
        dot(1);
        onEdt(() -> ConflictCommands.vcsConflictBase(h.editor()));
        assertTrue(text().contains("<<<<<<<"), "nothing changed");
        assertTrue(h.status().contains("no base"), h.status());
    }

    @Test
    public void outsideAConflictSaysSo() throws Exception {
        conflict();
        open("a.txt");
        dot(0);
        onEdt(() -> ConflictCommands.vcsConflictOurs(h.editor()));
        assertTrue(h.status().contains("No conflict here"), h.status());
    }

    @Test
    public void aReadOnlyBufferIsLeftAlone() throws Exception {
        conflict();
        open("a.txt");
        dot(1);
        onEdt(() -> {
            file.setForceReadOnly(true);
            ConflictCommands.vcsConflictOurs(h.editor());
        });
        assertTrue(text().contains("<<<<<<<"), "nothing changed");
        assertTrue(h.status().contains("read only"), h.status());
    }

    @Test
    public void aLinkWhoseConflictChangedSaysSo() throws Exception {
        conflict();
        open("a.txt");
        final Runnable keepOurs = links().get(0).action();
        // The opener deleted whole: the region it named is gone.
        final Line opener = line(1);
        onEdt(() -> {
            h.editor().setDot(opener, 0);
            h.editor().setMark(new Position(opener.next(), 0));
            h.editor().deleteRegion();
        });
        until(
            () -> file.getVirtualText().get(Conflicts.OWNER).stream().noneMatch(v -> v.placement() == Placement.ABOVE));
        final String before = text();
        onEdt(keepOurs);
        assertEquals(before, text(), "nothing taken");
        assertEquals("That conflict changed", h.status());
    }

    @Test
    public void aCloserOnTheLastLineTakesNoNewline() throws Exception {
        conflict();
        // Still unmerged, the file's last line its closer.
        git.write("a.txt", "one\n<<<<<<< HEAD\nOURS\n=======\nTHEIRS\n>>>>>>> other");
        open("a.txt");
        dot(2);
        onEdt(() -> ConflictCommands.vcsConflictTheirs(h.editor()));
        assertEquals("one\nTHEIRS", text());
    }

    @Test
    public void theCaretsSideLightsTheMarkersAroundIt() throws Exception {
        conflict();
        open("a.txt");
        // opener 1, OURS 2, base marker 3, two 4, separator 5, THEIRS 6, closer 7.
        caret(2);
        final java.awt.Color lit = foreground(1);
        assertEquals(lit, foreground(3), "ours ends at the base's marker");
        assertNotEquals(lit, foreground(5), "the separator dim");
        assertNotEquals(lit, foreground(7), "the closer dim");
        caret(4);
        assertEquals(lit, foreground(3), "the base's marker lit");
        assertEquals(lit, foreground(5), "the separator lit");
        assertNotEquals(lit, foreground(1), "the opener dim");
        caret(6);
        assertEquals(lit, foreground(5), "the separator lit");
        assertEquals(lit, foreground(7), "the closer lit");
        assertNotEquals(lit, foreground(3), "the base's marker dim");
        caret(0);
        assertNotEquals(lit, foreground(7), "outside, all dim");
        assertNull(file.getVirtualText().on(line(2), Placement.BACKGROUND).get(0).foreground(), "a side's own text");
    }

    @Test
    public void withNoBaseOursLightsTheSeparator() throws Exception {
        twoWay();
        open("a.txt");
        // opener 1, OURS 2, separator 3, THEIRS 4, closer 5.
        caret(2);
        assertEquals(foreground(1), foreground(3), "the separator lit with the opener");
        assertNotEquals(foreground(1), foreground(5), "the closer dim");
    }

    @Test
    public void eachLinkShowsItsKey() throws Exception {
        conflict();
        open("a.txt");
        final VirtualText row =
                shades().stream().filter(v -> v.placement() == Placement.ABOVE).findFirst().orElseThrow();
        final String links = VirtualText.text(row.rows().get(0));
        // As j's menus write the keys: Shift Alt Left.
        assertTrue(links.contains("Keep HEAD (Shift Alt Left)"), links);
        assertTrue(links.contains("Keep other (Shift Alt Right)"), links);
        assertTrue(links.contains("Both \u00b7 Base"), "both and base have no key: " + links);
    }

    @Test
    public void typingInASideShowsNothingAgain() throws Exception {
        conflict();
        open("a.txt");
        final int[] version = new int[1];
        onEdt(() -> version[0] = file.getVirtualText().rowsVersion());
        dot(2);
        onEdt(() -> h.editor().insertString("x"));
        // The rescan, after the change, on the event thread.
        onEdt(() -> {});
        onEdt(() -> assertEquals(version[0], file.getVirtualText().rowsVersion(), "the same regions not set again"));
        assertTrue(text().contains("xOURS"), text());
    }

    // The caret on line n, as a command would leave it.
    private void caret(int n) throws Exception {
        final Line l = line(n);
        onEdt(() -> {
            h.editor().setDot(l, 0);
            Conflicts.viewChanged(h.editor());
        });
    }

    private java.awt.Color foreground(int n) throws Exception {
        final java.awt.Color[] c = new java.awt.Color[1];
        onEdt(() -> c[0] = file.getVirtualText().on(line(n), Placement.BACKGROUND).get(0).foreground());
        return c[0];
    }

    // The link row's runs that run something.
    private List<VirtualText.Run> links() throws Exception {
        final VirtualText row =
                shades().stream().filter(v -> v.placement() == Placement.ABOVE).findFirst().orElseThrow();
        return row.rows().get(0).stream().filter(r -> r.action() != null).toList();
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
