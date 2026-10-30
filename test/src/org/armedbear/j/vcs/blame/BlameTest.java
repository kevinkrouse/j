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
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.git.GitFixture;
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
        GitFixture.reset();
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
        assertNull(at(1), "a line added has none");
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
