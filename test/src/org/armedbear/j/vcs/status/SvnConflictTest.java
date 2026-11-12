/*
 * SvnConflictTest.java
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Role;
import org.armedbear.j.vcs.svn.SvnFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** svn's text conflicts taken and resolved, and a tree conflict refused, as the status resolves them. */
public class SvnConflictTest {
    @TempDir
    Path tmp;
    private SvnFixture svn;
    private VcsRepository repository;

    @BeforeEach
    void home() {
        EditorHarness.useScratchHome();
    }

    @AfterEach
    void reset() {
        SvnFixture.reset();
    }

    // a.txt changed here and there, a text conflict; t.txt changed here,
    // deleted there, a tree conflict.
    private void conflicts() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("a.txt", "base\n");
        svn.write("t.txt", "t\n");
        svn.add("a.txt", "t.txt");
        svn.commit("first");
        final SvnFixture other = svn.second();
        other.write("a.txt", "theirs\n");
        other.svn("rm", "-q", "--", "t.txt");
        other.commit("theirs");
        svn.write("a.txt", "ours\n");
        svn.write("t.txt", "t changed\n");
        svn.run("update");
        repository = Repositories.forDirectory(svn.root);
        repository.invalidate();
    }

    private List<FileItem> unmerged() {
        final StatusData d = repository.backend().readStatus(repository, (s, p) -> false, Whitespace.NONE);
        final StatusData.Section s = d.section(Role.UNMERGED);
        final List<FileItem> files = new ArrayList<>();
        if (s != null)
            for (StatusData.Item i : s.items())
                files.add((FileItem) i);
        return files;
    }

    private Target.Files target(String path) {
        for (FileItem f : unmerged())
            if (f.path().equals(path))
                return new Target.Files(repository, StatusData.UNMERGED, List.of(f));
        throw new AssertionError("no conflict of " + path);
    }

    @Test
    public void theConflictIsListedWithItsSides() throws Exception {
        conflicts();
        assertEquals(List.of("a.txt", "t.txt"), unmerged().stream().map(FileItem::path).sorted().toList());
        final VcsBackend.ConflictSides sides = repository.backend().readConflictSides(repository, "a.txt");
        assertEquals("ours\n", new String(sides.ours(), StandardCharsets.UTF_8));
        assertEquals("theirs\n", new String(sides.theirs(), StandardCharsets.UTF_8));
        assertNull(repository.backend().readConflictSides(repository, "t.txt"), "a tree conflict has no text sides");
    }

    @Test
    public void takeOurs() throws Exception {
        conflicts();
        assertNull(ResolveCommands.resolve(List.of(target("a.txt")), ResolveCommands.Take.OURS));
        assertEquals("ours\n", svn.read("a.txt"));
        assertFalse(svn.svn("status", "--", "a.txt").startsWith("C"), svn.svn("status"));
    }

    @Test
    public void takeTheirs() throws Exception {
        conflicts();
        assertNull(ResolveCommands.resolve(List.of(target("a.txt")), ResolveCommands.Take.THEIRS));
        assertEquals("theirs\n", svn.read("a.txt"));
        assertEquals("", svn.svn("status", "--", "a.txt").strip(), "theirs is what the update brought");
    }

    @Test
    public void resolveAsEdited() throws Exception {
        conflicts();
        svn.write("a.txt", "merged by hand\n");
        final VcsResult r = repository.backend().stage(repository, List.of("a.txt"));
        assertTrue(r.succeeded(), r.stderr());
        assertEquals("merged by hand\n", svn.read("a.txt"));
        assertTrue(svn.svn("status", "--", "a.txt").startsWith("M"), svn.svn("status"));
    }

    @Test
    public void aTextAndPropertyConflictIsRefused() throws Exception {
        svn = SvnFixture.init(tmp);
        svn.write("f.txt", "base\n");
        svn.add("f.txt");
        svn.propset("k", "v1", "f.txt");
        svn.commit("first");
        final SvnFixture other = svn.second();
        other.write("f.txt", "theirs\n");
        other.propset("k", "v2", "f.txt");
        other.commit("theirs");
        svn.write("f.txt", "ours\n");
        svn.propset("k", "v3", "f.txt");
        svn.run("update");
        repository = Repositories.forDirectory(svn.root);
        repository.invalidate();
        final VcsResult r = repository.backend().stage(repository, List.of("f.txt"));
        assertFalse(r.succeeded());
        assertTrue(r.stderr().contains("properties"), r.stderr());
        assertTrue(svn.svn("status", "--", "f.txt").startsWith("CC"), svn.svn("status"));
    }

    @Test
    public void aTreeConflictIsRefused() throws Exception {
        conflicts();
        final String failed = ResolveCommands.resolve(List.of(target("t.txt")), ResolveCommands.Take.OURS);
        assertNotNull(failed);
        assertTrue(failed.contains("tree conflict"), failed);
        assertTrue(svn.svn("status", "--", "t.txt").contains("C"), "still in conflict");
    }
}
