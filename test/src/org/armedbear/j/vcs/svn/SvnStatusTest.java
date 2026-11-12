/*
 * SvnStatusTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.BackendFixture;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Role;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SvnStatusTest {
    @TempDir
    Path tmp;
    private SvnFixture f;
    private VcsRepository repository;

    @AfterEach
    void reset() {
        SvnFixture.reset();
    }

    // A working copy with one committed file of each kind to change.
    private void committed(String... pathsAndTexts) throws Exception {
        f = SvnFixture.init(tmp);
        for (int i = 0; i < pathsAndTexts.length; i += 2)
            f.write(pathsAndTexts[i], pathsAndTexts[i + 1]);
        final String[] paths = new String[pathsAndTexts.length / 2];
        for (int i = 0; i < paths.length; i++)
            paths[i] = pathsAndTexts[2 * i];
        if (paths.length > 0) {
            f.add(paths);
            f.commit("first");
        }
        repository = BackendFixture.repository(f.root, new SvnBackend());
    }

    private Map<String, FileChange> changes() {
        final Snapshot s = repository.backend().readSnapshot(repository);
        assertNull(s.error(), s.error());
        final Map<String, FileChange> out = new HashMap<>();
        for (FileChange c : s.changes())
            out.put(c.path(), c);
        return out;
    }

    private FileDiff diff(String path, Whitespace ignored) {
        repository.snapshotNow();
        final List<FileDiff> diffs = repository.backend().readDiff(repository, false, List.of(path), ignored);
        assertNotNull(diffs);
        return diffs.isEmpty() ? null : diffs.get(0);
    }

    @Test
    public void aWorkingCopyIsFoundAsSvns() throws Exception {
        committed("a.txt", "a\n");
        final VcsRepository found = Repositories.forDirectory(f.root);
        assertNotNull(found);
        assertEquals("svn", found.backend().name());
    }

    @Test
    public void eachKindOfChangeMaps() throws Exception {
        committed(
            "mod.txt",
            "a\nb\n",
            "del.txt",
            "d\n",
            "miss.txt",
            "m\n",
            "prop.txt",
            "p\n",
            "bin.dat",
            "\0\1\2",
            "repl.txt",
            "r\n");
        f.write("mod.txt", "a\nB\n");
        f.svn("rm", "-q", "--", "del.txt");
        Files.delete(f.root.resolve("miss.txt"));
        f.propset("color", "blue", "prop.txt");
        f.write("bin.dat", "\0\3");
        f.svn("rm", "-q", "--", "repl.txt");
        f.write("repl.txt", "new\n");
        f.add("repl.txt");
        f.write("new.txt", "n\n");
        f.add("new.txt");
        f.write("unv.txt", "u\n");
        Files.createDirectories(f.root.resolve("unvdir"));
        f.write("unvdir/x.txt", "x\n");
        f.write("ign.o", "o");
        final Map<String, FileChange> c = changes();
        assertEquals(Kind.MODIFIED, c.get("mod.txt").unstaged());
        assertNull(c.get("mod.txt").staged(), "no index");
        assertEquals(Kind.DELETED, c.get("del.txt").unstaged());
        assertEquals(Kind.DELETED, c.get("miss.txt").unstaged());
        assertEquals("missing", c.get("miss.txt").code());
        assertEquals(Kind.MODIFIED, c.get("prop.txt").unstaged(), "a property's change");
        assertEquals(Kind.MODIFIED, c.get("bin.dat").unstaged());
        assertEquals(Kind.MODIFIED, c.get("repl.txt").unstaged(), "replaced");
        assertEquals(Kind.ADDED, c.get("new.txt").unstaged());
        assertEquals(Kind.UNTRACKED, c.get("unv.txt").unstaged());
        assertEquals(Kind.UNTRACKED, c.get("unvdir/").unstaged(), "a directory as git names one");
        assertFalse(c.containsKey("ign.o"), "ignored, left out");
    }

    @Test
    public void aConflictIsUnmerged() throws Exception {
        committed("a.txt", "one\n");
        final SvnFixture other = f.second();
        other.write("a.txt", "theirs\n");
        other.commit("theirs");
        f.write("a.txt", "ours\n");
        f.run("update");
        final FileChange c = changes().get("a.txt");
        assertTrue(c.isConflicted());
        assertEquals(FileChange.Conflict.BOTH_MODIFIED, c.conflict());
        final StatusData d = repository.backend().readStatus(repository, (s, p) -> false, Whitespace.NONE);
        assertEquals(Role.UNMERGED, d.sections().get(0).role());
        assertNull(repository.backend().readBases(repository, "a.txt"), "no bases for a conflict");
    }

    @Test
    public void theStatusHasSectionsByRoleAndAHeader() throws Exception {
        committed("a.txt", "a\n", "b.txt", "b\n");
        f.write("a.txt", "A\n");
        f.write("u.txt", "u\n");
        final Set<String> asked = ConcurrentHashMap.newKeySet();
        final StatusData d = repository.backend().readStatus(repository, (section, path) -> {
            asked.add(section + ":" + path);
            return true;
        }, Whitespace.NONE);
        assertNull(d.error());
        assertEquals(List.of("untracked", "working"), d.sections().stream().map(Section::id).toList());
        assertEquals(Role.UNTRACKED, d.section(Role.UNTRACKED).role());
        final FileItem a = (FileItem) d.section(Role.WORKING).items().get(0);
        assertEquals('M', a.letter());
        assertFalse(a.staged());
        assertEquals(1, a.diff().hunks().size());
        assertEquals(Set.of("working:a.txt"), asked);
        assertEquals(f.url, d.header().get(0).value());
        assertEquals("1", d.header().get(1).value());
    }

    @Test
    public void diffsOfEachKind() throws Exception {
        committed("mod.txt", "a\nb\n", "prop.txt", "p\n", "bin.dat", "\0\1", "miss.txt", "m1\nm2");
        f.write("mod.txt", "a\nB\n");
        f.propset("color", "blue", "prop.txt");
        f.write("bin.dat", "\0\2");
        Files.delete(f.root.resolve("miss.txt"));
        f.write("new.txt", "n\n");
        f.add("new.txt");
        assertEquals(1, diff("mod.txt", Whitespace.NONE).hunks().size());
        assertNull(diff("prop.txt", Whitespace.NONE), "a property's change has no text diff");
        assertTrue(diff("bin.dat", Whitespace.NONE).isBinary());
        assertTrue(diff("new.txt", Whitespace.NONE).isNew());
        final FileDiff missing = diff("miss.txt", Whitespace.NONE);
        assertTrue(missing.isDeleted());
        assertEquals(2, missing.hunks().get(0).lines().size());
        final StatusData d = repository.backend().readStatus(repository, (s, p) -> true, Whitespace.NONE);
        for (var i : d.section(Role.WORKING).items())
            if (i instanceof FileItem fi && fi.path().equals("prop.txt"))
                assertEquals(FileDiff.EMPTY, fi.diff(), "read, with no hunks");
        assertEquals(List.of(), repository.backend().readDiff(repository, true, List.of("mod.txt"), Whitespace.NONE));
    }

    @Test
    public void copiesReplacedFilesAndMissingDirectories() throws Exception {
        committed("a.txt", "1\n2\n3\n", "dir/f.txt", "f\n", "b.txt", "b\n");
        f.svn("copy", "-q", "--", "a.txt", "copy.txt");
        f.svn("rm", "-q", "--", "b.txt");
        f.write("b.txt", "B\n");
        f.add("b.txt");
        Files.delete(f.root.resolve("dir/f.txt"));
        Files.delete(f.root.resolve("dir"));
        final Map<String, FileChange> c = changes();
        assertEquals("replaced", c.get("b.txt").code());
        assertEquals("missing", c.get("dir").code());
        repository.snapshotNow();
        final List<FileDiff> diffs = repository.backend()
                .readDiff(repository, false, List.of("copy.txt", "b.txt", "dir", "dir/f.txt"), Whitespace.NONE);
        assertNotNull(diffs, "a copy or a missing directory doesn't fail the rest");
        final Map<String, FileDiff> byPath = new HashMap<>();
        for (FileDiff d : diffs)
            byPath.put(d.path(), d);
        assertTrue(byPath.get("copy.txt").isNew(), "a copy as an add");
        final FileDiff replaced = byPath.get("b.txt");
        assertFalse(replaced.isNew(), "a replaced file as a change, not an add");
        assertEquals(1, replaced.hunks().size());
        assertTrue(byPath.get("dir/f.txt").isDeleted());
        assertNull(byPath.get("dir"));
    }

    @Test
    public void theConfigsDiffOptionsDontApply() throws Exception {
        committed("a.txt", "a b\n");
        Files.writeString(SvnCli.testConfigDir.resolve("config"), "[helpers]\ndiff-extensions = -w\n");
        f.write("a.txt", "a  b\n");
        assertEquals(1, diff("a.txt", Whitespace.NONE).hunks().size());
    }

    @Test
    public void whitespaceModes() throws Exception {
        committed("a.txt", "a b\nc\n");
        f.write("a.txt", "a   b\nc\n");
        assertEquals(1, diff("a.txt", Whitespace.NONE).hunks().size());
        assertNull(diff("a.txt", Whitespace.AMOUNT));
        assertNull(diff("a.txt", Whitespace.ALL));
        assertEquals(1, diff("a.txt", Whitespace.TRAILING).hunks().size(), "svn has no option for it");
        f.write("a.txt", "a b  \nc\n");
        assertNull(diff("a.txt", Whitespace.ALL));
        f.write("a.txt", "ab\nc\n");
        assertNull(diff("a.txt", Whitespace.ALL));
        assertEquals(1, diff("a.txt", Whitespace.AMOUNT).hunks().size());
        final StatusData d = repository.backend().readStatus(repository, (s, p) -> true, Whitespace.ALL);
        assertEquals(FileDiff.ONLY_WHITESPACE, ((FileItem) d.section(Role.WORKING).items().get(0)).diff());
    }

    @Test
    public void pathsWithSpacesAndAnAt() throws Exception {
        committed("with space.txt", "s\n", "icon@2x.txt", "i\n", "déjà.txt", "x\n");
        f.write("with space.txt", "S\n");
        f.write("icon@2x.txt", "I\n");
        f.write("déjà.txt", "X\n");
        final Map<String, FileChange> c = changes();
        assertEquals(Set.of("with space.txt", "icon@2x.txt", "déjà.txt"), c.keySet());
        for (String p : c.keySet())
            assertEquals(1, diff(p, Whitespace.NONE).hunks().size(), p);
        final StatusData d = repository.backend().readStatus(repository, (s, p) -> true, Whitespace.NONE);
        for (var i : d.section(Role.WORKING).items())
            assertEquals(1, ((FileItem) i).diff().hunks().size(), ((FileItem) i).path());
        final Bases b = repository.backend().readBases(repository, "icon@2x.txt");
        assertEquals("i\n", new String(b.head(), StandardCharsets.UTF_8));
    }

    @Test
    public void basesOfModifiedAddedAndUnversionedFiles() throws Exception {
        committed("a.txt", "a\n");
        f.write("a.txt", "A\n");
        f.write("new.txt", "n\n");
        f.add("new.txt");
        f.write("unv.txt", "u\n");
        repository.snapshotNow();
        final Bases modified = repository.backend().readBases(repository, "a.txt");
        assertEquals("a\n", new String(modified.head(), StandardCharsets.UTF_8));
        assertEquals("a\n", new String(modified.index(), StandardCharsets.UTF_8), "no index: the same text");
        final Bases added = repository.backend().readBases(repository, "new.txt");
        assertNull(added.head());
        assertEquals(0, added.index().length);
        final Bases unversioned = repository.backend().readBases(repository, "unv.txt");
        assertNull(unversioned.head());
        assertNull(unversioned.index());
    }

    @Test
    public void watchesTheWorkingCopysDatabaseNotItsTree() throws Exception {
        committed();
        assertEquals(
            List.of(new org.armedbear.j.vcs.RepositoryWatcher.Watch(f.root.resolve(".svn"), false)),
            repository.backend().watches(repository));
    }
}
