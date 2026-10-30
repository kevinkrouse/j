/*
 * BlameColumnTest.java
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

import java.awt.Color;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.TextLine;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The blame column: its entries, runs, age steps, width, and the toggle. */
public class BlameColumnTest {
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

    private static BlameCommit commit(String hash, String author, long time, String summary) {
        final String mail = author.toLowerCase().replace(' ', '.') + "@example.com";
        return new BlameCommit(hash, author, mail, time, summary, "f.txt", null, null, false);
    }

    // format for commit alone.
    private static String expand(String format, BlameCommit commit, long nowMillis, int maxWidth) {
        return BlameColumn.entries(format, List.of(commit), nowMillis, maxWidth).get(commit);
    }

    // Lines of text, chained as a buffer's are, in a BlameData.
    private static BlameData data(List<Line> lines) {
        for (int i = 1; i < lines.size(); i++) {
            lines.get(i - 1).setNext(lines.get(i));
            lines.get(i).setPrevious(lines.get(i - 1));
        }
        return new BlameData(lines);
    }

    private static List<Line> lines(String... text) {
        return Arrays.stream(text).map(t -> (Line) new TextLine(t)).toList();
    }

    @Test
    public void formatExpansion() {
        final BlameCommit c =
                commit("0123456789abcdef0123456789abcdef01234567", "Ann Lee", 1_760_000_000L, "a summary");
        final Instant t = Instant.ofEpochSecond(1_760_000_000L);
        final String date =
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(t);
        final String isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(t);
        assertEquals(date + " Lee", expand("%d %a", c, 0, 30));
        assertEquals("ann.lee", expand("%e", c, 0, 30));
        assertEquals(isoDate + " Ann Lee", expand("%D %A", c, 0, 30));
        assertEquals("0123456", expand("%h", c, 0, 30));
        assertEquals("a summary", expand("%s", c, 0, 30));
        final long now = (1_760_000_000L + 86_400L * 10) * 1000;
        assertEquals("10d", expand("%r", c, now, 30));
        assertEquals("now", expand("%r", c, 1_760_000_000L * 1000, 30));
        assertEquals("%d", expand("%%d", c, 0, 30));
        assertEquals("%z", expand("%z", c, 0, 30));
    }

    @Test
    public void anEntryPastTheCapEndsInAnEllipsisExactlyTheCapLong() {
        final BlameCommit c =
                commit("0123456789abcdef0123456789abcdef01234567", "Ann", 1_760_000_000L, "a longer summary");
        final String e = expand("%s", c, 0, 10);
        assertEquals(10, e.length());
        assertEquals("a longer \u2026", e);
    }

    @Test
    public void runsBreakWhereTheCommitChanges() {
        final BlameCommit a = commit("a".repeat(40), "Ann", 300, "A");
        final BlameCommit b = commit("b".repeat(40), "Bob", 200, "B");
        final List<Line> ls = lines("a0", "a1", "b2", "a3");
        final BlameData d = data(ls);
        d.lines(0, 2, a);
        d.lines(2, 1, b);
        d.lines(3, 1, a);
        assertTrue(BlameColumn.isRunHead(d, ls.get(0)), "first line");
        assertFalse(BlameColumn.isRunHead(d, ls.get(1)), "same as before");
        assertTrue(BlameColumn.isRunHead(d, ls.get(2)), "a new commit");
        assertTrue(BlameColumn.isRunHead(d, ls.get(3)), "back to A after B");
    }

    @Test
    public void aChangedLineIsNotCommittedAndBreaksTheRun() {
        final BlameCommit a = commit("a".repeat(40), "Ann", 300, "A");
        final List<Line> ls = lines("a0", "a1");
        final BlameData d = data(ls);
        d.lines(0, 2, a);
        ls.get(1).setText("changed");
        assertEquals(BlameData.NOT_COMMITTED, d.commit(ls.get(1)));
        assertTrue(BlameColumn.isRunHead(d, ls.get(1)), "a changed line starts a run");
    }

    @Test
    public void aLineAddedSinceTheReadHasNoCommit() {
        final BlameCommit a = commit("a".repeat(40), "Ann", 300, "A");
        final List<Line> ls = lines("a0", "new");
        final BlameData d = data(ls);
        d.lines(0, 1, a);
        assertNull(d.commit(ls.get(1)));
        assertTrue(BlameColumn.isRunHead(d, ls.get(1)));
    }

    @Test
    public void theFieldsArePaddedSoTheNextLinesUp() {
        final BlameCommit a = commit("a".repeat(40), "Ann Lee", 0, "A");
        final BlameCommit b = commit("b".repeat(40), "Bo Kowalski", 0, "B");
        final Map<BlameCommit, String> e = BlameColumn.entries("%a %h", List.of(a, b), 0, 30);
        assertEquals("Lee      aaaaaaa", e.get(a));
        assertEquals("Kowalski bbbbbbb", e.get(b));
        assertEquals("Lee", BlameColumn.entries("%h %a", List.of(a, b), 0, 30).get(a).substring(8), "none trailing");
    }

    @Test
    public void theShortNamesAreTheLastNameAndTheMailsUser() {
        assertEquals("Lee", BlameColumn.lastName("Ann Lee"));
        assertEquals("ann", BlameColumn.lastName("ann"));
        assertEquals("", BlameColumn.lastName(""));
        assertEquals("ann.lee", BlameColumn.mailUser("ann.lee@example.com"));
        assertEquals("ann", BlameColumn.mailUser("ann"));
    }

    @Test
    public void ageStepsTheNewestAloneAtZeroTheRestOntoOneToSeven() {
        assertEquals(0, BlameColumn.step(0, 1));
        assertEquals(0, BlameColumn.step(0, 3));
        assertEquals(1, BlameColumn.step(1, 2));
        assertEquals(1, BlameColumn.step(1, 3));
        assertEquals(4, BlameColumn.step(2, 3));
        assertEquals(7, BlameColumn.step(7, 8));
        assertEquals(1, BlameColumn.step(1, 100));
        assertEquals(7, BlameColumn.step(99, 100));
    }

    @Test
    public void theNewestIsWhiteOnDarkTheRestCoolAndDimmer() {
        final Color background = new Color(0x1E1E1E);
        final Color[] colors = BlameColumn.colors(background);
        assertEquals(Color.WHITE, colors[0]);
        assertEquals(Color.BLACK, BlameColumn.colors(Color.WHITE)[0]);
        for (int s = 1; s < colors.length; s++) {
            assertTrue(colors[s].getBlue() > colors[s].getRed(), "cool: " + colors[s]);
            if (s > 1)
                assertTrue(brightness(colors[s]) < brightness(colors[s - 1]), "dimmer at " + s);
        }
    }

    private static int brightness(Color c) {
        return c.getRed() + c.getGreen() + c.getBlue();
    }

    @Test
    public void theBufferCommitsAreRankedByAuthorTimeUncommittedLeftOut() {
        final BlameCommit newest = commit("c".repeat(40), "C", 300, "newest");
        final BlameCommit middle = commit("b".repeat(40), "B", 200, "middle");
        final BlameCommit oldest = commit("a".repeat(40), "A", 100, "oldest");
        final BlameData d = data(lines("0", "1", "2", "3"));
        d.lines(0, 1, oldest);
        d.lines(1, 1, newest);
        d.lines(2, 1, middle);
        d.lines(3, 1, BlameData.NOT_COMMITTED);
        final Map<BlameCommit, Integer> steps = BlameColumn.ageSteps(d);
        assertEquals(3, steps.size(), "uncommitted not ranked");
        assertEquals(0, steps.get(newest).intValue());
        assertEquals(1, steps.get(middle).intValue());
        assertEquals(4, steps.get(oldest).intValue());
    }

    @Test
    public void toggleAddsTheColumnReadsAndDropsIt() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\nc\n");
        git.git("add", "-A");
        git.git("commit", "-q", "--author=Ann <a@example.com>", "-m", "first");
        open("f.txt");
        final int before = Display.getGutterWidth(file);
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> column() != null && column().width() > 0);
        assertTrue(Display.getGutterWidth(file) > before, "the gutter grew");
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        assertNull(column());
        assertNull(file.getBlame(), "the blame dropped");
    }

    @Test
    public void blameReadAlreadyShowsAtOnce() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        git.git("add", "-A");
        git.git("commit", "-q", "--author=Ann <a@example.com>", "-m", "first");
        open("f.txt");
        final Runnable other = () -> {};
        onEdt(() -> Blame.of(file).addListener(other));
        until(() -> file.getBlame().data() != null && file.getBlame().data().isDone());
        onEdt(() -> {
            BlameCommands.vcsBlame(h.editor());
            assertTrue(column().width() > 0, "shown without another read");
            assertEquals("Blamed", h.status());
            BlameCommands.vcsBlame(h.editor());
            file.getBlame().removeListener(other);
        });
    }

    @Test
    public void notBlameableSaysWhy() throws Exception {
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            BlameCommands.vcsBlame(h.editor());
            assertTrue(h.status().contains("No blame"), h.status());
            assertTrue(h.buffer().getGutterColumns().isEmpty());
        });
    }

    @Test
    public void theWidthIsCappedAtThePropertyPlusPadding() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\nc\n");
        git.git("add", "-A");
        git.git("commit", "-q", "--author=Ann <a@example.com>", "-m", "first");
        open("f.txt");
        file.setProperty(Property.VCS_BLAME_MAX_WIDTH, 10);
        onEdt(() -> BlameCommands.vcsBlame(h.editor()));
        until(() -> column() != null && column().width() > 0);
        // The bar and its gap, the capped entry, and a character of padding.
        assertEquals(BlameColumn.textLeft() + 11 * Display.getCharWidth(), column().width());
    }

    // The column on the file, or null. Safe off the event thread: it reads
    // the buffer's column list, which the toggle changes on the EDT.
    private BlameColumn column() {
        for (GutterColumn g : file.getGutterColumns())
            if (g instanceof BlameColumn b)
                return b;
        return null;
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

    private static void onEdt(ThrowingRunnable r) throws Exception {
        final Exception[] thrown = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            }
            catch (Exception e) {
                thrown[0] = e;
            }
        });
        if (thrown[0] != null)
            throw thrown[0];
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void until(ThrowingSupplier done) throws Exception {
        for (int i = 0; i < 400; i++) {
            if (done.get())
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out");
    }

    private interface ThrowingSupplier {
        boolean get() throws Exception;
    }
}
