/*
 * GitCommitPreviewTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsBackend.ChangeVersion;
import org.armedbear.j.vcs.VcsBackend.CommitPreview;
import org.armedbear.j.vcs.VcsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitCommitPreviewTest {
    @TempDir
    Path tmp;
    private GitFixture git;
    private VcsRepository repository;

    @BeforeEach
    public void repository() throws Exception {
        git = GitFixture.init(tmp);
        repository = Repositories.forDirectory(git.root);
    }

    @AfterEach
    public void reset() {
        GitFixture.reset();
    }

    private CommitPreview preview(List<String> paths) {
        repository.invalidateFiles();
        return repository.backend().previewCommit(repository, repository.snapshotNow().head(), paths, () -> {
            final var staged = repository.backend().stage(repository, paths);
            return staged.succeeded() ? null : staged.stderr();
        });
    }

    private ChangeVersion read(String path) {
        return preview(List.of(path)).versions().get(path);
    }

    @Test
    public void binaryContentUsesBytesEvenWhenLengthAndTimestampStayTheSame() throws Exception {
        git.write("binary", "old\0bytes");
        git.commitAll("base");
        final var before = read("binary");
        assertNotNull(before);
        assertEquals(before.base(), before.staged());
        final var time = Files.getLastModifiedTime(tmp.resolve("binary"));
        git.write("binary", "new\0bytes");
        Files.setLastModifiedTime(tmp.resolve("binary"), time);
        final var after = read("binary");
        assertEquals(before.base(), after.base());
        assertNotEquals(before.staged(), after.staged());
        assertEquals("old\0bytes", git.head("binary"));
        assertEquals("old\0bytes", git.index("binary"), "review leaves the real index alone");
    }

    @Test
    public void modeOnlyChangesAreVersionedAndCoreFilemodeFalseIsHonored() throws Exception {
        git.write("file", "same\n");
        git.commitAll("base");
        final var before = read("file");
        Files.setPosixFilePermissions(tmp.resolve("file"), PosixFilePermissions.fromString("rw-r-xr--"));
        assertEquals(before, read("file"), "Git uses owner execute");
        assertTrue(tmp.resolve("file").toFile().setExecutable(true));
        final var executable = read("file");
        assertEquals(before.staged().content(), executable.staged().content());
        assertEquals("100755", executable.staged().properties());
        assertNotEquals(before, executable);
        git.git("config", "core.filemode", "false");
        assertEquals(before, read("file"));
        git.write("new", "new\n");
        assertTrue(tmp.resolve("new").toFile().setExecutable(true));
        assertEquals("100644", read("new").staged().properties());
    }

    @Test
    public void emptyUntrackedAndDeletedFilesHaveKnownAbsentSides() throws Exception {
        git.write("empty", "");
        final var unborn = read("empty");
        assertNotNull(unborn);
        assertNull(unborn.base());
        assertNotNull(unborn.staged());
        git.commitAll("base");
        final var tracked = read("empty");
        assertEquals(tracked.base(), unborn.staged());
        Files.delete(tmp.resolve("empty"));
        final var deleted = read("empty");
        assertNotNull(deleted);
        assertEquals(tracked.base(), deleted.base());
        assertNull(deleted.staged());
    }

    @Test
    public void pathsAreLiteralAndRenameBaseUsesTheOriginalPath() throws Exception {
        final String old = "old|@#\n\t\".txt";
        final String path = ":(glob)*new.txt";
        git.write(old, "before\n");
        git.commitAll("base");
        final var before = read(old);
        Files.move(tmp.resolve(old), tmp.resolve(path));
        git.write(path, "after\n");
        final var renamed = preview(List.of(old, path));
        assertNull(renamed.failure());
        assertEquals(before.base(), renamed.versions().get(old).base());
        assertNull(renamed.versions().get(old).staged());
        assertNull(renamed.versions().get(path).base());
        assertNotEquals(before.staged(), renamed.versions().get(path).staged());
    }

    @Test
    public void symlinkContentIsItsTargetIncludingDanglingTargets() throws Exception {
        Files.createSymbolicLink(tmp.resolve("link"), Path.of("missing"));
        git.commitAll("base");
        final var before = read("link");
        assertEquals(before.base(), before.staged());
        assertEquals("120000", before.staged().properties());
        Files.delete(tmp.resolve("link"));
        Files.createSymbolicLink(tmp.resolve("link"), Path.of("different"));
        final var after = read("link");
        assertNotNull(after);
        assertNotEquals(before.staged(), after.staged());
    }

    @Test
    public void coreSymlinksFalsePreservesLinkModeAndUsesGitsCleanFilters() throws Exception {
        Files.createSymbolicLink(tmp.resolve("link"), Path.of("missing\r\ntarget"));
        git.commitAll("base");
        final var before = read("link");
        Files.delete(tmp.resolve("link"));
        git.git("config", "core.symlinks", "false");
        git.write("link", "missing\r\ntarget");
        git.write(".gitattributes", "link text eol=lf\n");
        final var placeholder = read("link");
        assertNotNull(placeholder);
        assertEquals(before.base(), placeholder.base());
        assertEquals("120000", placeholder.staged().properties());
        assertEquals(git.git("hash-object", "link").strip(), placeholder.staged().content());
        git.write("link", "missing\ntarget");
        final var changed = read("link");
        assertEquals(placeholder.staged(), changed.staged());
        git.git("add", "link");
        assertEquals(changed.staged().content(), git.git("rev-parse", ":link").strip());
        assertTrue(git.git("ls-files", "--stage", "link").startsWith("120000"));
    }

    @Test
    public void normalizationMatchesAddAndRequiredFilterFailureIsUnread() throws Exception {
        git.write(".gitattributes", "*.txt text eol=lf\n");
        git.write("a.txt", "one\ntwo\n");
        git.commitAll("base");
        final var before = read("a.txt");
        git.write("a.txt", "one\r\ntwo\r\n");
        assertEquals(before, read("a.txt"));
        git.write(".gitattributes", "*.txt filter=broken\n");
        git.git("config", "filter.broken.clean", "false");
        git.git("config", "filter.broken.required", "true");
        assertNull(read("a.txt"));
    }

    @Test
    public void batchesKeepLiteralPathsAndTheirContentVersionsAligned() throws Exception {
        final List<String> paths = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            final String path = "dir/file\n\t\"|@#" + i;
            git.write(path, "base " + i);
            paths.add(path);
        }
        git.commitAll("base");
        for (int i = 0; i < paths.size(); i++)
            git.write(paths.get(i), "working " + i);
        final var preview = preview(paths);
        assertNull(preview.failure());
        assertEquals(paths.size(), preview.versions().size());
        for (int i : List.of(0, 99, 100, 104)) {
            final String path = paths.get(i);
            final var version = preview.versions().get(path);
            assertEquals(git.git("hash-object", "--", path).strip(), version.staged().content());
            assertEquals(git.git("rev-parse", "HEAD:" + path).strip(), version.base().content());
            assertEquals("base " + i, git.index(path));
        }
    }

    @Test
    public void textAutoUsesTheExistingCrLfBlobWhenPreviewingGitAdd() throws Exception {
        git.write("a.txt", "old\r\n");
        git.commitAll("base");
        git.write(".gitattributes", "a.txt text=auto\n");
        git.commitAll("attributes");
        repository.invalidate();
        git.write("a.txt", "edited\r\n");
        final var crlf = read("a.txt");
        assertEquals(git.git("hash-object", "--no-filters", "a.txt").strip(), crlf.staged().content());
        git.write("a.txt", "edited\n");
        final var lf = read("a.txt");
        assertNotEquals(crlf.staged(), lf.staged());
        assertEquals("old\r\n", git.index("a.txt"));
    }

    @Test
    public void anUntrackedNestedRepositoryPreviewsAndCommitsAsAGitlink() throws Exception {
        git.commitAll("outer");
        final GitFixture nested = GitFixture.init(Files.createDirectory(tmp.resolve("nested")));
        nested.commitAll("inner");
        final var preview = preview(List.of("nested/"));
        assertNull(preview.failure());
        final var version = preview.versions().get("nested/");
        assertNull(version.base());
        assertEquals("160000", version.staged().properties());
        assertEquals(nested.git("rev-parse", "HEAD").strip(), version.staged().content());
        assertTrue(git.git("ls-files", "--", "nested").isEmpty());

        final var committed =
                repository.backend().commitPrivately(repository, List.of("nested/"), preview.versions(), () -> {
                    final var stage = repository.backend().stage(repository, List.of("nested/"));
                    return stage.succeeded() ? null : stage.stderr();
                }, "Add nested", java.util.Set.of());
        assertTrue(committed.result().succeeded(), committed.result().stderr());
        assertNull(committed.trouble());
        assertEquals(version.staged().content(), git.git("rev-parse", "HEAD:nested").strip());
        assertEquals(version.staged().content(), git.git("rev-parse", ":nested").strip());
        git.write("ordinary/file", "untracked");
        assertNotNull(preview(List.of("ordinary/")).failure());
        assertTrue(git.git("ls-files", "--", "ordinary").isEmpty());
    }

}
