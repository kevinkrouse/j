/*
 * EditorHunksTest.java
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

package org.armedbear.j.vcs.signs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.ChangeMarks;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.Signs;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class EditorHunksTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer buffer;

    private static final String TEN;

    static {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++)
            sb.append("line ").append(i).append('\n');
        TEN = sb.toString();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (h != null)
            SwingUtilities.invokeAndWait(h.editor()::closeCaretPopup);
        if (buffer != null) {
            buffer.setSignsUpdater(null);
            Editor.getBufferList().remove(buffer);
        }
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        GitFixture.reset();
    }

    // a.txt committed, then opened, its signs read.
    private void open(String committed) throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", committed);
        git.commitAll("first");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            buffer = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!buffer.initialized())
                buffer.initialize();
            buffer.load();
            h.showBuffer(buffer);
            h.editor().setDot(buffer.getFirstLine(), 0);
        });
        until(() -> buffer.getSigns() != null);
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError(
                "timed out; index " + (git == null ? null : String.valueOf(git.index("a.txt")).replace("\n", "|"))
                        + " signs " + describe() + " status " + h.status());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private String describe() {
        final StringBuilder sb = new StringBuilder();
        if (buffer == null || buffer.getSigns() == null)
            return "none";
        for (Line l = buffer.getFirstLine(); l != null; l = l.next()) {
            final Signs.Sign s = buffer.getSigns().get(l);
            sb.append(s == null ? "." : s.staged() ? "s" : s.kind().name().substring(0, 1));
        }
        return sb.toString();
    }

    private Line line(int n) {
        return buffer.getLine(n);
    }

    private Signs.Sign sign(int n) {
        return buffer.getSigns().get(line(n));
    }

    // Replaces line n's text, as typing would.
    private void change(int n, String text) throws Exception {
        onEdt(() -> {
            final Line l = line(n);
            h.editor().deleteRegion(new Position(l, 0), new Position(l, l.length()));
            h.editor().insertString(text);
        });
    }

    private void settle() throws Exception {
        Thread.sleep(SignsUpdater.DELAY_MILLIS * 3);
        until(() -> true);
    }

    @Test
    public void aTrackedFilesMarksAreItsVersionControls() throws Exception {
        open(TEN);
        assertTrue(buffer.getSigns().isEmpty());
        change(2, "changed");
        until(() -> sign(2) != null);
        assertEquals(Signs.Kind.CHANGED, sign(2).kind());
        assertFalse(sign(2).staged());
        change(2, "line 3");
        until(() -> buffer.getSigns().isEmpty());
    }

    @Test
    public void stagingAHunkStagesTheBuffersTextUnsaved() throws Exception {
        open(TEN);
        change(2, "staged from the buffer");
        until(() -> sign(2) != null);
        onEdt(() -> {
            h.editor().setDot(line(2), 0);
            HunkCommands.vcsStageHunk(h.editor());
        });
        until(() -> TEN.replace("line 3\n", "staged from the buffer\n").equals(git.index("a.txt")));
        assertEquals(TEN, git.read("a.txt"), "the file is not saved");
        until(() -> sign(2) != null && sign(2).staged());
        assertEquals(Signs.Kind.CHANGED, sign(2).kind());

        onEdt(() -> HunkCommands.vcsUnstageHunk(h.editor()));
        until(() -> TEN.equals(git.index("a.txt")));
        until(() -> sign(2) != null && !sign(2).staged());
    }

    @Test
    public void aSelectionStagesItsLinesOnly() throws Exception {
        open(TEN);
        change(2, "three");
        change(3, "four");
        until(() -> sign(3) != null);
        onEdt(() -> {
            h.editor().setMark(new Position(line(3), 0));
            h.editor().setDot(line(3), 2);
            HunkCommands.vcsStageHunk(h.editor());
        });
        until(() -> TEN.replace("line 4\n", "four\n").equals(git.index("a.txt")));
    }

    @Test
    public void resetPutsTheHunkBackAsStagedAndUndoTakesItBack() throws Exception {
        open(TEN);
        change(4, "five");
        onEdt(() -> h.editor().setDot(line(4), 0));
        settle();
        onEdt(() -> HunkCommands.resetHunk(h.editor()));
        until(() -> buffer.getText().equals(TEN));
        onEdt(() -> org.armedbear.j.EditCommands.undo(h.editor()));
        until(() -> line(4).getText().equals("five"));
    }

    @Test
    public void resetTakesAddedLinesOutAndPutsDeletedOnesBack() throws Exception {
        open(TEN);
        onEdt(() -> {
            h.editor().setDot(line(3), 0);
            h.editor().insertString("added\n");
        });
        settle();
        assertEquals("added", line(3).getText(), "inserted");
        onEdt(() -> {
            h.editor().setDot(line(3), 0);
            HunkCommands.resetHunk(h.editor());
        });
        until(() -> line(3).getText().equals("line 4"));
        // Lines 6 and 7 deleted: the mark is on line 5, and reset puts them back.
        onEdt(() -> h.editor().deleteRegion(new Position(line(5), 0), new Position(line(7), 0)));
        settle();
        until(() -> sign(4) != null && sign(4).kind() == Signs.Kind.DELETED);
        onEdt(() -> {
            h.editor().setDot(line(4), 0);
            HunkCommands.resetHunk(h.editor());
        });
        until(() -> buffer.getText().equals(TEN));
    }

    @Test
    public void aCrlfFileStagesWithItsLineEnds() throws Exception {
        open("one\r\ntwo\r\nthree\r\n");
        change(1, "TWO");
        until(() -> sign(1) != null);
        onEdt(() -> {
            h.editor().setDot(line(1), 0);
            HunkCommands.vcsStageHunk(h.editor());
        });
        until(() -> "one\r\nTWO\r\nthree\r\n".equals(git.index("a.txt")));
    }

    @Test
    public void aFilesByteOrderMarkStaysInTheIndex() throws Exception {
        // UTF-8's mark, as the fixture writes ISO-8859-1.
        open("\u00ef\u00bb\u00bfone\ntwo\n");
        until(() -> describe().equals("..."));
        change(0, "ONE");
        until(() -> sign(0) != null);
        onEdt(() -> {
            h.editor().setDot(line(0), 0);
            HunkCommands.vcsStageHunk(h.editor());
        });
        // The index's bytes, as ISO-8859-1.
        until(() -> "\u00ef\u00bb\u00bfONE\ntwo\n".equals(git.index("a.txt")));
    }

    @Test
    public void aHunkOfAUtf16FileIsNotStaged() throws Exception {
        final byte[] text = "one\ntwo\n".getBytes(StandardCharsets.UTF_16LE);
        final byte[] bytes = new byte[text.length + 2];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xfe;
        System.arraycopy(text, 0, bytes, 2, text.length);
        git = GitFixture.init(tmp);
        Files.write(git.root.resolve("a.txt"), bytes);
        git.commitAll("first");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            buffer = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!buffer.initialized())
                buffer.initialize();
            buffer.load();
            h.showBuffer(buffer);
        });
        until(() -> buffer.getSigns() != null);
        change(1, "TWO");
        until(() -> sign(1) != null);
        onEdt(() -> {
            h.editor().setDot(line(1), 0);
            HunkCommands.vcsStageHunk(h.editor());
        });
        until(() -> String.valueOf(h.status()).contains("UTF-16"));
        assertEquals(new String(bytes, StandardCharsets.ISO_8859_1), git.index("a.txt"), "the index as it was");
    }

    @Test
    public void theLastLineOfAFileWithNoNewlineStages() throws Exception {
        open("one\ntwo");
        change(1, "TWO");
        until(() -> sign(1) != null);
        onEdt(() -> {
            h.editor().setDot(line(1), 0);
            HunkCommands.vcsStageHunk(h.editor());
        });
        until(() -> "one\nTWO".equals(git.index("a.txt")));
    }

    private void stageAt(int n) throws Exception {
        onEdt(() -> {
            h.editor().setDot(line(n), 0);
            HunkCommands.vcsStageHunk(h.editor());
        });
    }

    @Test
    public void savedAsAnotherFileItsSignsAreThatFiles() throws Exception {
        open(TEN);
        git.write("b.txt", TEN);
        git.commitAll("b");
        onEdt(() -> {
            buffer.setFile(File.getInstance(git.root.resolve("b.txt").toString()));
            buffer.checkVCS(); // As Save As does.
        });
        assertEquals("b.txt", buffer.getSignsUpdater().getPath());
        change(2, "b's");
        until(() -> sign(2) != null);
        stageAt(2);
        until(() -> TEN.replace("line 3\n", "b's\n").equals(git.index("b.txt")));
        assertEquals(TEN, git.index("a.txt"), "a.txt untouched");
    }

    @Test
    public void undoWorksTheSignsOutAgain() throws Exception {
        open(TEN);
        onEdt(() -> h.editor().deleteRegion(new Position(line(5), 0), new Position(line(6), 0)));
        until(() -> sign(4) != null);
        onEdt(() -> org.armedbear.j.EditCommands.undo(h.editor()));
        until(() -> buffer.getSigns().isEmpty());
    }

    @Test
    public void linesAddedBelowALastLineWithNoNewlineStage() throws Exception {
        open("one\ntwo");
        onEdt(() -> {
            final Line l = line(1);
            h.editor().setDot(l, l.length());
            h.editor().insertString("\nthree");
        });
        until(() -> sign(2) != null);
        assertEquals(Signs.Kind.CHANGED, sign(1).kind(), "two gained its newline");
        stageAt(2);
        until(() -> "one\ntwo\nthree".equals(git.index("a.txt")));
    }

    @Test
    public void resetPutsTheLastNewlineBack() throws Exception {
        open("one\ntwo\n");
        onEdt(() -> {
            final Line l = line(1);
            h.editor().deleteRegion(new Position(l, l.length()), new Position(buffer.getLastLine(), 0));
        });
        until(() -> sign(1) != null);
        onEdt(() -> {
            h.editor().setDot(line(1), 0);
            HunkCommands.resetHunk(h.editor());
        });
        until(() -> buffer.getText().equals("one\ntwo\n"));
        until(() -> buffer.getSigns().isEmpty());
    }

    @Test
    public void anEmptiedBufferStillHasAMark() throws Exception {
        open(TEN);
        onEdt(() -> {
            h.editor()
                    .deleteRegion(
                        new Position(buffer.getFirstLine(), 0),
                        new Position(buffer.getLastLine(), buffer.getLastLine().length()));
        });
        until(() -> buffer.getSigns() != null && !buffer.getSigns().isEmpty());
        assertEquals(Signs.Kind.DELETED, sign(0).kind());
    }

    @Test
    public void aStagedDeletionUnderALineChangedAgainIsWhereItsMarkIs() throws Exception {
        open(TEN);
        git.write("a.txt", TEN.replace("line 8\n", ""));
        git.git("add", "a.txt");
        git.write("a.txt", TEN);
        onEdt(() -> {
            buffer.reload();
            buffer.checkVCS();
        });
        // The buffer has line 8 back, and line 7 changed.
        change(6, "seven");
        until(() -> sign(6) != null && !sign(6).staged());
        onEdt(() -> {
            h.editor().setDot(line(0), 0);
            HunkCommands.vcsUnstageHunk(h.editor());
        });
        Thread.sleep(500);
        assertEquals(TEN.replace("line 8\n", ""), git.index("a.txt"), "nothing staged at line 1");
        onEdt(() -> {
            h.editor().setDot(line(5), 0);
            HunkCommands.vcsUnstageHunk(h.editor());
        });
        until(() -> TEN.equals(git.index("a.txt")));
    }

    @Test
    public void aStagedRenameIsAgainstItsOldName() throws Exception {
        open(TEN);
        git.git("mv", "a.txt", "b.txt");
        final Buffer[] b = new Buffer[1];
        onEdt(() -> {
            org.armedbear.j.vcs.Repositories.forFile(buffer.getFile()).snapshotNow();
            b[0] = Editor.getBuffer(File.getInstance(git.root.resolve("b.txt").toString()));
            if (!b[0].initialized())
                b[0].initialize();
            b[0].load();
        });
        until(() -> b[0].getSigns() != null);
        assertTrue(b[0].getSigns().isEmpty(), "unchanged against a.txt, not all added");
        b[0].setSignsUpdater(null);
        Editor.getBufferList().remove(b[0]);
    }

    @Test
    public void vcsSignsOffShowsJsMarksAtOnce() throws Exception {
        open(TEN);
        change(2, "x");
        until(() -> sign(2) != null);
        buffer.setProperty(org.armedbear.j.Property.VCS_SIGNS, false);
        assertEquals(null, buffer.getSigns());
    }

    @Test
    public void nextChangeGoesByHunk() throws Exception {
        open(TEN);
        change(2, "x");
        change(3, "y");
        change(7, "z");
        settle();
        until(() -> sign(7) != null);
        onEdt(() -> {
            h.editor().setDot(line(0), 0);
            ChangeMarks.nextChange(h.editor());
        });
        assertEquals(2, h.editor().getDotLineNumber());
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertEquals(7, h.editor().getDotLineNumber(), "the next hunk, not the hunk's next line");
        onEdt(() -> ChangeMarks.previousChange(h.editor()));
        assertEquals(2, h.editor().getDotLineNumber());
        onEdt(() -> ChangeMarks.previousChange(h.editor()));
        assertEquals(7, h.editor().getDotLineNumber(), "before the first: around to the last");
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertEquals(2, h.editor().getDotLineNumber(), "past the last: around to the first");
    }

    @Test
    public void previewShowsTheHunksLinesUntilTheNextCommand() throws Exception {
        open(TEN);
        change(4, "line 5!");
        onEdt(() -> h.editor().setDot(line(4), 0));
        settle();
        onEdt(() -> HunkCommands.previewHunk(h.editor()));
        until(() -> HunkPreview.showing(h.editor()));
        assertEquals("[-line 5, +line 5!]", HunkPreview.rows(h.editor()).toString());
        // Only the character added differs.
        assertEquals(0, HunkPreview.rows(h.editor()).get(0).differs().size());
        assertArrayEquals(new int[] { 6, 7 }, HunkPreview.rows(h.editor()).get(1).differs().get(0));
        onEdt(() -> HunkPreview.close(h.editor()));
        assertFalse(HunkPreview.showing(h.editor()));
    }

    @Test
    public void previewShowsAddedLines() throws Exception {
        open(TEN);
        onEdt(() -> {
            h.editor().setDot(line(9), line(9).length());
            h.editor().insertString("\nlast");
            h.editor().setDot(line(5), 0);
            h.editor().insertString("middle\n");
            h.editor().setDot(line(0), 0);
            h.editor().insertString("first\n");
        });
        settle();
        until(() -> sign(12) != null);
        for (int n : new int[] { 0, 6, 12 }) {
            final int at = n;
            onEdt(() -> {
                HunkPreview.close(h.editor());
                h.editor().setDot(line(at), 0);
                HunkCommands.previewHunk(h.editor());
            });
            until(() -> HunkPreview.showing(h.editor()));
            assertEquals("[+" + line(at).getText() + "]", HunkPreview.rows(h.editor()).toString(), "line " + at);
        }
    }

    @Test
    public void anOpenPreviewFollowsToTheNextAndPreviousHunk() throws Exception {
        open(TEN);
        change(2, "x");
        change(5, "y");
        change(8, "z");
        settle();
        until(() -> sign(8) != null);
        onEdt(() -> {
            h.vim();
            buffer.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
            h.editor().setDot(line(5), 0);
            HunkCommands.previewHunk(h.editor());
        });
        until(() -> HunkPreview.showing(h.editor()));
        assertEquals("Hunk 2 of 3", HunkPreview.title(h.editor()));
        assertEquals(
            List.of("previousChange", "nextChange", "vcsStageHunk", "revertLines"),
            HunkPreview.buttons(h.editor()));
        onEdt(() -> {
            h.keys("]");
            assertTrue(HunkPreview.showing(h.editor()), "[ or ] begins a key sequence: no command yet");
            h.keys("c");
            assertTrue(HunkPreview.showing(h.editor()), "kept until the next hunk's shows, so it doesn't flash");
        });
        until(() -> "Hunk 3 of 3".equals(HunkPreview.title(h.editor())));
        assertEquals("[-line 9, +z]", HunkPreview.rows(h.editor()).toString());
        assertEquals("nextChange", HunkPreview.buttons(h.editor()).get(1), "past the last: around to the first");
        onEdt(() -> h.keys("]c"));
        until(() -> "Hunk 1 of 3".equals(HunkPreview.title(h.editor())));
        onEdt(() -> h.keys("[c"));
        until(() -> "Hunk 3 of 3".equals(HunkPreview.title(h.editor())));
        onEdt(() -> h.keys("j"));
        assertFalse(HunkPreview.showing(h.editor()), "another command closes it");
    }

    @Test
    public void previewShowsAStagedHunkWithUnstageNotReset() throws Exception {
        open(TEN);
        change(4, "five");
        onEdt(() -> h.editor().setDot(line(4), 0));
        settle();
        onEdt(() -> HunkCommands.vcsStageHunk(h.editor()));
        until(() -> sign(4) != null && sign(4).staged());
        onEdt(() -> HunkCommands.previewHunk(h.editor()));
        until(() -> HunkPreview.showing(h.editor()));
        assertEquals("Hunk 1 of 1 (staged)", HunkPreview.title(h.editor()));
        assertEquals("[-line 5, +five]", HunkPreview.rows(h.editor()).toString());
        assertEquals(
            List.of("(previousChange)", "(nextChange)", "vcsUnstageHunk", "(revertLines)"),
            HunkPreview.buttons(h.editor()));
    }

    @Test
    public void previewShowsAFileNewToTheIndexAsStagedAdded() throws Exception {
        open(TEN);
        git.write("new.txt", "x\ny\n");
        git.git("add", "new.txt");
        onEdt(() -> {
            final Buffer other = Editor.getBuffer(File.getInstance(git.root.resolve("new.txt").toString()));
            if (!other.initialized())
                other.initialize();
            other.load();
            buffer.setSignsUpdater(null);
            Editor.getBufferList().remove(buffer);
            buffer = other;
            h.showBuffer(other);
            h.editor().setDot(other.getFirstLine(), 0);
        });
        until(() -> buffer.getSigns() != null && sign(0) != null);
        onEdt(() -> HunkCommands.previewHunk(h.editor()));
        until(() -> HunkPreview.showing(h.editor()));
        assertEquals("[+x, +y]", HunkPreview.rows(h.editor()).toString());
        assertEquals("Hunk 1 of 1 (staged)", HunkPreview.title(h.editor()));
    }

    @Test
    public void aResetDoesNothingToAnotherBufferSwitchedToMeanwhile() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", TEN);
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", TEN.replace("line 5", "five"));
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final Buffer[] other = new Buffer[1];
        onEdt(() -> {
            buffer = load("a.txt");
            other[0] = load("b.txt");
            h.showBuffer(buffer);
            h.editor().setDot(line(4), 0);
        });
        until(() -> buffer.getSigns() != null && sign(4) != null);
        assertEquals(buffer.getModCount(), other[0].getModCount(), "both unedited");
        onEdt(() -> {
            HunkCommands.resetHunk(h.editor());
            h.showBuffer(other[0]);
        });
        Thread.sleep(500);
        onEdt(() -> {});
        assertEquals("b", other[0].getFirstLine().getText());
        assertEquals("five", line(4).getText(), "not reset either: it was not showing");
        other[0].setSignsUpdater(null);
        Editor.getBufferList().remove(other[0]);
    }

    private Buffer load(String name) {
        final Buffer b = Editor.getBuffer(File.getInstance(git.root.resolve(name).toString()));
        if (!b.initialized())
            b.initialize();
        b.load();
        return b;
    }

    @Test
    public void aWebPageOfATrackedFileHasNoSigns() throws Exception {
        open(TEN);
        git.write("page.html", "<p>a</p>\n");
        git.git("add", "page.html");
        final Buffer[] page = new Buffer[1];
        onEdt(() -> {
            page[0] = org.armedbear.j.mode.web.WebBuffer
                    .createWebBuffer(File.getInstance(git.root.resolve("page.html").toString()), null, null);
            org.armedbear.j.vcs.VersionControl.attachSigns(page[0]);
        });
        assertEquals(null, page[0].getSignsUpdater(), "its text is the page rendered, not the file's");
    }

    @Test
    public void anUntrackedFileKeepsJsOwnMarks() throws Exception {
        open(TEN);
        git.write("new.txt", "x\n");
        final Buffer[] other = new Buffer[1];
        onEdt(() -> {
            other[0] = Editor.getBuffer(File.getInstance(git.root.resolve("new.txt").toString()));
            if (!other[0].initialized())
                other[0].initialize();
            other[0].load();
        });
        settle();
        assertNotNull(other[0].getSignsUpdater());
        assertEquals(null, other[0].getSigns(), "nothing in the index: j's marks");
        other[0].setSignsUpdater(null);
        Editor.getBufferList().remove(other[0]);
    }
}
