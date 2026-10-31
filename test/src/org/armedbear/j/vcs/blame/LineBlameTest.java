/*
 * LineBlameTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.EditCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.VirtualText;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.log.RevisionBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The caret line's blame: shown after it rests, moved with it, and gone where the gutter or a preference says. */
public class LineBlameTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;
    // A second buffer a test opens, removed after it.
    private Buffer other;

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(BlameText::reset);
        onEdt(() -> {
            if (file != null) {
                for (GutterColumn c : List.copyOf(file.getGutterColumns()))
                    if (c instanceof BlameColumn column) {
                        file.removeGutterColumn(column);
                        column.blame().removeListener(column.changed());
                    }
                Editor.getBufferList().remove(file);
            }
            if (other != null)
                Editor.getBufferList().remove(other);
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
        });
        GitFixture.reset();
    }

    @Test
    public void restingOnACommittedLineShowsAuthorAndSummary() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        open("f.txt");
        rest(0);
        final String s = untilText(0);
        assertTrue(s.startsWith("Ann, "), s);
        assertTrue(s.endsWith(" \u00b7 first"), s);
    }

    @Test
    public void theRunCarriesTheHashAndTakesNoClick() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\n");
        commitAs("Ann", "first");
        final String hash = git.git("rev-parse", "HEAD").strip();
        open("f.txt");
        rest(0);
        untilText(0);
        final VirtualText.Run run = untilRun(0);
        assertNull(run.action(), "a click past the line's end moves the caret");
        assertEquals(hash, run.tooltip(), "the hash as its tool tip");
    }

    @Test
    public void movingClearsTheOldLineAndShowsTheNew() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        git.write("f.txt", "one\nTWO\n");
        commitAs("Cat", "third");
        open("f.txt");
        rest(0);
        untilText(0);
        // Move the caret: the old line clears at once, the new shows after.
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(1), 0);
            BlameText.viewChanged(h.editor());
            BlameText.show();
        });
        assertNull(eolText(0), "cleared where the caret left");
        final String s = untilText(1);
        assertTrue(s.endsWith(" \u00b7 third"), s);
    }

    @Test
    public void anEditedLineSaysNotCommittedYet() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        open("f.txt");
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(1), 3);
            h.editor().insertString("!");
        });
        rest(1);
        assertEquals("Not committed yet", untilText(1));
    }

    @Test
    public void aLineAddedSinceTheReadSaysNotCommittedYet() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        open("f.txt");
        rest(0);
        untilDone();
        // A line opened after the first, as Enter at its end or vim's o.
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(0), 3);
            EditCommands.newline(h.editor());
            h.editor().insertString("new");
        });
        rest(1);
        assertEquals("Not committed yet", untilText(1));
    }

    @Test
    public void shownOnARunsFirstLineWithTheColumnOn() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        open("f.txt");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        untilDone();
        // The column gives the date and author; the text adds the summary.
        rest(0);
        final String s = untilText(0);
        assertTrue(s.endsWith(" \u00b7 first"), s);
    }

    @Test
    public void setOffByThreeSpacesInALighterColor() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\n");
        commitAs("Ann", "first");
        open("f.txt");
        rest(0);
        untilText(0);
        onEdt(() -> {
            final List<VirtualText.Run> row = file.getVirtualText().get(BlameText.OWNER).get(0).rows().get(0);
            // EOL text is a space after the line's end; the gap is two more.
            assertEquals("  ", row.get(0).text());
            assertNotNull(row.get(1).color(), "a color of its own, lighter than the dimmed default");
        });
    }

    @Test
    public void noneOverTheMaxLines() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\nthree\n");
        commitAs("Ann", "first");
        open("f.txt");
        file.setProperty(Property.VCS_BLAME_MAX_LINES, 2);
        rest(0);
        assertNull(eolText(0), "too long to blame");
    }

    @Test
    public void noneWithThePreferenceOff() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\n");
        commitAs("Ann", "first");
        open("f.txt");
        file.setProperty(Property.VCS_LINE_BLAME, false);
        rest(0);
        assertNull(eolText(0), "turned off");
    }

    @Test
    public void noneForAnUntrackedFile() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\n");
        commitAs("Ann", "first");
        git.write("new.txt", "x\ny\n");
        open("new.txt");
        rest(0);
        // The blame reads and says there is no history: nothing shows.
        untilDone();
        assertNull(eolText(0), "untracked shows nothing");
    }

    @Test
    public void noneOutsideARepository() throws Exception {
        // A file in the temporary directory but not in any repository.
        final Path outside = tmp.resolve("outside.txt");
        java.nio.file.Files.writeString(outside, "one\n");
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(outside.toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            h.showBuffer(file);
        });
        rest(0);
        assertNull(eolText(0), "no repository to blame");
    }

    @Test
    public void theListenerMovesWithTheBuffer() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "one\n");
        commitAs("Ann", "first");
        git.write("b.txt", "two\n");
        commitAs("Bob", "second");
        open("a.txt");
        rest(0);
        untilDone();
        final Buffer a = file;
        assertNotNull(a.getBlame(), "read while looked at");
        // Switch to another buffer with the column off: the old one stops being read.
        onEdt(() -> {
            final Buffer b = Editor.getBuffer(File.getInstance(git.root.resolve("b.txt").toString()));
            other = b;
            if (!b.initialized())
                b.initialize();
            b.load();
            Editor.getBufferList().add(b);
            h.showBuffer(b);
            h.editor().moveDotTo(b.getLine(0), 0);
            BlameText.viewChanged(h.editor());
            BlameText.show();
        });
        assertNull(a.getBlame(), "the old buffer's blame dropped when the caret left it");
    }

    @Test
    public void aRevisionBufferShowsItsRevisionsBlame() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "one\ntwo\n");
        commitAs("Ann", "first");
        git.write("f.txt", "one\nTWO\n");
        commitAs("Bob", "second");
        final String first = git.git("rev-parse", "HEAD~").strip();
        file = new RevisionBuffer(Repositories.forDirectory(git.root), "f.txt", first, "one\ntwo\n");
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            Editor.getBufferList().add(file);
            h.showBuffer(file);
        });
        rest(0);
        final String s = untilText(0);
        assertTrue(s.startsWith("Ann, "), s);
        assertTrue(s.endsWith(" \u00b7 first"), s);
    }

    // Rest the caret on a line: as the timer firing would.
    private void rest(int line) throws Exception {
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(line), 0);
            BlameText.viewChanged(h.editor());
            BlameText.show();
        });
    }

    private String untilText(int line) throws Exception {
        for (int i = 0; i < 400; i++) {
            final String s = eolText(line);
            if (s != null)
                return s;
            Thread.sleep(10);
        }
        throw new AssertionError("no line blame shown on line " + line);
    }

    private VirtualText.Run untilRun(int line) throws Exception {
        for (int i = 0; i < 400; i++) {
            final VirtualText.Run r = eolRun(line);
            if (r != null)
                return r;
            Thread.sleep(10);
        }
        throw new AssertionError("no run on line " + line);
    }

    private void untilDone() throws Exception {
        until(() -> {
            final Blame b = file.getBlame();
            return b != null && b.data() != null && b.data().isDone();
        });
    }

    private String eolText(int line) throws Exception {
        final VirtualText.Run r = eolRun(line);
        return r == null ? null : r.text();
    }

    private VirtualText.Run eolRun(int line) throws Exception {
        final VirtualText.Run[] r = new VirtualText.Run[1];
        onEdt(() -> {
            for (VirtualText v : file.getVirtualText().get(BlameText.OWNER))
                if (v.line() == file.getLine(line) && !v.rows().isEmpty() && !v.rows().get(0).isEmpty())
                    r[0] = v.rows().get(0).get(v.rows().get(0).size() - 1);
        });
        return r[0];
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
