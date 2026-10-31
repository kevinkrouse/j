/*
 * BlameTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.blame;

import static org.armedbear.j.Constants.BINARY_MODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.TextLine;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.log.RevisionBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A buffer's blame: read while wanted, its lines' commits moving with them, read again when the file is committed. */
public class BlameTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;
    private final AtomicInteger told = new AtomicInteger();
    private final Runnable listener = told::incrementAndGet;

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            final Blame b = file == null ? null : Blame.of(file);
            if (b != null)
                b.removeListener(listener);
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
            if (file != null)
                Editor.getBufferList().remove(file);
        });
        DiffWhitespace.forget();
        GitFixture.reset();
    }

    private void open(String path) throws Exception {
        open(path, null);
    }

    // The same, the file read in encoding, or as detected for null.
    private void open(String path, String encoding) throws Exception {
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(git.root.resolve(path).toString()));
            if (encoding != null)
                file.getFile().setEncoding(encoding);
            if (!file.initialized())
                file.initialize();
            file.load();
            h.showBuffer(file);
        });
    }

    private void commitAs(String author, String message) {
        git.git("add", "-A");
        git.git("commit", "-q", "--author=" + author + " <a@example.com>", "-m", message);
    }

    // Whether the blame shown has finished.
    private boolean read() throws Exception {
        final boolean[] done = new boolean[1];
        onEdt(() -> done[0] = Blame.of(file).data() != null && Blame.of(file).data().isDone());
        return done[0];
    }

    private BlameCommit at(int line) throws Exception {
        final BlameCommit[] c = new BlameCommit[1];
        onEdt(() -> c[0] = Blame.of(file).data().commit(file.getLine(line)));
        return c[0];
    }

    @Test
    public void aLineAddedWithTextTheFileHadIsUnknownNotUncommitted() {
        final Line a = new TextLine("a");
        final Line b = new TextLine("b");
        final BlameData d = new BlameData(List.of(a, b));
        d.lines(0, 2, new BlameCommit("1234", "Ann", "", 0, 0, "first", "f.txt", null, null, false), 0);
        // As an undo or a reload puts it back, in a new Line: known again at the next read.
        assertNull(d.commit(new TextLine("b")), "text the file had");
        assertSame(BlameData.NOT_COMMITTED, d.commit(new TextLine("new")), "text it didn't");
    }

    @Test
    public void readWhileWantedEachLineItsCommitAndAnEditMovesItWithItsLine() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a\nB\n");
        commitAs("Bob", "second");
        open("f.txt");
        onEdt(() -> Blame.of(file).addListener(listener));
        until(this::read);
        assertTrue(told.get() > 0, "told");
        assertEquals("Ann", at(0).author());
        assertEquals("Bob", at(1).author());
        final Line second = file.getLine(1);
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(0), 1);
            h.editor().insertString("\nnew");
        });
        assertEquals("Ann", at(0).author());
        assertSame(BlameData.NOT_COMMITTED, at(1), "a line added is not committed yet");
        assertSame(second, file.getLine(2));
        assertEquals("Bob", at(2).author(), "moved with its line");
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(0), 1);
            h.editor().insertString("!");
        });
        assertSame(BlameData.NOT_COMMITTED, at(0), "changed since");
    }

    @Test
    public void committedItIsReadAgain() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a\nB\n");
        open("f.txt");
        onEdt(() -> Blame.of(file).addListener(listener));
        until(this::read);
        assertTrue(at(1).isUncommitted());
        commitAs("Bob", "second");
        // As the watcher or a commit from the status would.
        onEdt(() -> {
            Repositories.forFile(file.getFile()).invalidate();
            file.checkVCS();
        });
        until(() -> !at(1).isUncommitted());
        assertEquals("Bob", at(1).author());
    }

    @Test
    public void aUtf16FileIsBlamedAsSaved() throws Exception {
        committedAs("UnicodeLittle", new byte[] { (byte) 0xff, (byte) 0xfe }, "a\nb\nc\n".getBytes("UTF-16LE"));
    }

    @Test
    public void aUtf8FileWithAByteOrderMarkIsBlamedAsSaved() throws Exception {
        committedAs(null, new byte[] { (byte) 0xef, (byte) 0xbb, (byte) 0xbf }, "a\nb\nc\n".getBytes("UTF-8"));
    }

    // A file of bom and text, committed, read in encoding: every line its commit's.
    private void committedAs(String encoding, byte[] bom, byte[] text) throws Exception {
        git = GitFixture.init(tmp);
        final byte[] bytes = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(text, 0, bytes, bom.length, text.length);
        java.nio.file.Files.write(git.root.resolve("f.txt"), bytes);
        commitAs("Ann", "first");
        open("f.txt", encoding);
        onEdt(() -> Blame.of(file).addListener(listener));
        until(this::read);
        for (int i = 0; i < 3; i++)
            assertEquals("Ann", at(i).author(), "line " + i);
    }

    @Test
    public void aBinaryBufferHasNone() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.bin", "a\n");
        commitAs("Ann", "first");
        open("f.bin");
        onEdt(() -> {
            file.setMode(Editor.getModeList().getMode(BINARY_MODE));
            assertNull(Blame.of(file));
        });
    }

    @Test
    public void aRevisionBufferIsBlamedAtItsRevisionAndNeverAgain() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\nb\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a\nB\n");
        commitAs("Bob", "second");
        final String first = git.git("rev-parse", "HEAD~").strip();
        file = new RevisionBuffer(Repositories.forDirectory(git.root), "f.txt", first, "a\nb\n");
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            Editor.getBufferList().add(file);
            h.showBuffer(file);
            Blame.of(file).addListener(listener);
        });
        until(this::read);
        // Its file at that revision, not the working tree's: both lines Ann's.
        assertEquals("Ann", at(0).author());
        assertEquals("Ann", at(1).author());
        assertEquals(first, at(0).hash());
        final BlameData read = data();
        // The revision is fixed: checking the repository reads nothing new.
        told.set(0);
        onEdt(() -> file.checkVCS());
        Thread.sleep(2L * Blame.DELAY_MILLIS);
        assertEquals(0, told.get(), "not read again");
        assertSame(read, data(), "the same blame");
    }

    @Test
    public void aRevisionsFailedReadIsTriedAgainAsItsRepositoryChanges() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a\n");
        commitAs("Ann", "first");
        final VcsRepository r = Repositories.forDirectory(git.root);
        // A branch made only later.
        file = new RevisionBuffer(r, "f.txt", "later", "a\n");
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            Editor.getBufferList().add(file);
            h.showBuffer(file);
            Blame.of(file).addListener(listener);
        });
        until(() -> failure() != null);
        git.git("branch", "later");
        r.invalidate();
        r.refresh();
        until(() -> failure() == null && read() && data().error() == null);
        assertEquals("Ann", at(0).author());
    }

    @Test
    public void ignoringWhitespaceBlamesAgainPassingOverAWhitespaceChange() throws Exception {
        git = GitFixture.init(tmp);
        git.write("f.txt", "a b\n");
        commitAs("Ann", "first");
        git.write("f.txt", "a  b\n");
        commitAs("Bob", "spaces");
        open("f.txt");
        onEdt(() -> Blame.of(file).addListener(listener));
        until(this::read);
        assertEquals("Bob", at(0).author());
        onEdt(() -> DiffWhitespace.choose(Whitespace.ALL));
        until(() -> read() && "Ann".equals(at(0).author()));
        onEdt(() -> DiffWhitespace.choose(Whitespace.NONE));
        until(() -> read() && "Bob".equals(at(0).author()));
    }

    private BlameData failure() throws Exception {
        final BlameData[] d = new BlameData[1];
        onEdt(() -> d[0] = Blame.of(file).failure());
        return d[0];
    }

    private BlameData data() throws Exception {
        final BlameData[] d = new BlameData[1];
        onEdt(() -> d[0] = Blame.of(file).data());
        return d[0];
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
