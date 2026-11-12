/*
 * SvnChangelistTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.status;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.svn.SvnFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** svn's own changelists: sections of the status, moved between whole files, committed. */
public class SvnChangelistTest {
    @TempDir
    Path tmp;
    private SvnFixture svn;
    private EditorHarness h;
    private VcsStatusBuffer status;

    @BeforeEach
    public void setUp() {
        CommitCommands.show = (editor, buffer) -> {
            h.showBuffer(buffer);
            return h.editor();
        };
    }

    @AfterEach
    public void tearDown() throws Exception {
        CommitCommands.show = (editor, buffer) -> editor.activateInOtherWindow(buffer);
        final List<Buffer> buffers = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            buffers.add(b);
        SwingUtilities.invokeAndWait(() -> {
            for (Buffer b : buffers)
                if (b instanceof VcsStatusBuffer || b instanceof CommitMessageBuffer) {
                    b.dispose();
                    Editor.getBufferList().remove(b);
                }
        });
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        SwingUtilities.invokeAndWait(RepositoryStatus::closeAll);
        SvnFixture.reset();
    }

    // a.txt and b.txt committed and changed, the status open.
    private void open() throws Exception {
        svn = SvnFixture.init(tmp);
        for (String p : List.of("a.txt", "b.txt"))
            svn.write(p, "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n");
        svn.add("a.txt", "b.txt");
        svn.commit("first");
        for (String p : List.of("a.txt", "b.txt"))
            svn.write(p, "ONE\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\nTWELVE\n");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> text().contains("Changes (2)"));
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(svn.root);
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 400; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + text() + " / " + (h == null ? "" : h.status()));
    }

    private List<String> text() {
        final List<String> list = new ArrayList<>();
        if (status != null)
            for (Line l = status.getFirstLine(); l != null; l = l.next())
                list.add(l.getText());
        return list;
    }

    // The file at path, and the section it is in.
    private Target.Files whole(String path) {
        final StatusData d = status.model().getData(repo());
        for (StatusData.Section s : d.sections())
            for (StatusData.Item i : s.items())
                if (i instanceof FileItem f && f.path().equals(path))
                    return new Target.Files(repo(), s.id(), List.of(f));
        throw new AssertionError("no " + path);
    }

    private String move(Target t, String to) throws Exception {
        final AtomicReference<String> said = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
            () -> ChangelistCommands.moveTargets(
                status.model(),
                List.of(t),
                new ChangelistCommands.Destination(repo(), to, true),
                said::set));
        until(() -> said.get() != null);
        return said.get();
    }

    @Test
    public void movesToANewListToAnotherAndOut() throws Exception {
        open();
        assertTrue(move(whole("a.txt"), "feature").startsWith("Moved"));
        until(() -> text().contains("feature (1)") && text().contains("Changes (1)"));
        assertTrue(svn.svn("status").contains("--- Changelist 'feature':"), svn.svn("status"));
        assertEquals("feature", ChangelistCommands.nativeList(status.model(), repo(), whole("a.txt").section()));

        assertTrue(move(whole("a.txt"), "other").startsWith("Moved"));
        until(() -> text().contains("other (1)") && !text().contains("feature (1)"));

        assertTrue(move(whole("a.txt"), ChangelistCommands.NO_LIST).startsWith("Moved"));
        until(() -> text().contains("Changes (2)"));
        assertFalse(svn.svn("status").contains("Changelist"));
        assertNull(RepositoryLists.of(repo()), "j's own store isn't kept for svn");
    }

    @Test
    public void aPickedFileMovedIsUnpickedAndADirectoryRefused() throws Exception {
        open();
        SwingUtilities.invokeAndWait(() -> status.model().pickTargets(List.of(whole("a.txt"))));
        final String said = move(whole("a.txt"), "feature");
        assertTrue(said.contains("unpicked"), said);
        SwingUtilities.invokeAndWait(() -> assertTrue(status.model().picks().isEmpty()));
        svn.write("d/x.txt", "x\n");
        svn.add("d");
        repo().invalidate();
        SwingUtilities.invokeAndWait(() -> status.model().refresh());
        until(() -> {
            try {
                whole("d");
                return true;
            }
            catch (AssertionError e) {
                return false;
            }
        });
        assertEquals("svn's changelists take files, not directories", move(whole("d"), "feature"));
    }

    @Test
    public void aHunkIsRefused() throws Exception {
        open();
        status.model().readDiffsThen(java.util.Set.of(new FileKey(repo(), "working", "a.txt")), () -> {});
        until(() -> whole("a.txt").files().get(0).diff() != null);
        final FileItem a = whole("a.txt").files().get(0);
        final String said = move(new Target.Part(repo(), "working", a, a.diff().hunks().get(0), null), "feature");
        assertEquals("svn's changelists take whole files: select the file", said);
    }

    @Test
    public void commitsAList() throws Exception {
        open();
        move(whole("a.txt"), "feature");
        until(() -> text().contains("feature (1)"));
        SwingUtilities.invokeAndWait(() -> {
            for (Line l = status.getFirstLine(); l != null; l = l.next())
                if (l.getText().startsWith("feature ("))
                    h.editor().setDot(l, 0);
            CommitCommands.vcsCommitList(h.editor());
        });
        until(() -> {
            for (Buffer b : Editor.getBufferList())
                if (b instanceof CommitMessageBuffer)
                    return true;
            return false;
        });
        CommitMessageBuffer m = null;
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CommitMessageBuffer c)
                m = c;
        final CommitMessageBuffer message = m;
        SwingUtilities.invokeAndWait(() -> {
            final String text = message.getText();
            CommitCommands.replace(h.editor(), message, "the list\n\n" + text.substring(text.indexOf("# ")));
            CommitCommands.vcsCommitFinish(h.editor());
        });
        until(() -> !Editor.getBufferList().contains(message));
        svn.update();
        final String log = svn.svn("log", "-v", "-l", "1", "-q");
        assertTrue(log.contains("/a.txt") && !log.contains("/b.txt"), log);
        assertTrue(svn.svn("status").contains("b.txt"));
    }
}
