/*
 * SelectionsSameChangesTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.selection;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsBackend.ChangeVersion;
import org.armedbear.j.vcs.VcsBackend.FileVersion;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SelectionsSameChangesTest {
    @TempDir
    Path tmp;
    private VcsRepository repository;
    private static final FileVersion BASE = new FileVersion("base", "properties");
    private static final ChangeVersion VERSION = new ChangeVersion(BASE, new FileVersion("working", "properties"));
    private static final String SECTION = "opaque|@section";

    @BeforeEach
    public void repository() throws Exception {
        GitFixture.metadata(tmp);
        repository = Repositories.forDirectory(tmp.toRealPath());
    }

    private static Hunk hunk(int row, String text) {
        return new Hunk(row,
                0,
                row,
                2,
                "",
                List.of(new DiffLine('+', text, false), new DiffLine('+', "second", false)));
    }

    private static FileItem file(String path, Hunk... hunks) {
        final FileDiff diff = new FileDiff(List.of(), path, path, false, false, false, false, List.of(hunks), false);
        return new FileItem(new FileChange(path, null, null, FileChange.Kind.MODIFIED, " M"), 'M', false, diff);
    }

    private Target.Part part(FileItem file, int hunk, Integer line) {
        final BitSet bits = line == null ? null : BitSet.valueOf(new long[] { 1L << line });
        return Target.Part.of(repository, SECTION, file, file.diff().hunks().get(hunk), bits);
    }

    private Target.Files whole(FileItem... files) {
        return new Target.Files(repository, SECTION, List.of(files));
    }

    private Map<String, ChangeVersion> versions(ChangeVersion version, String... paths) {
        final var result = new java.util.HashMap<String, ChangeVersion>();
        for (String path : paths)
            result.put(path, version);
        return result;
    }

    private boolean same(List<Target> a, List<Target> b) {
        return Selections.sameChanges(a, versions(VERSION, "a"), b, versions(VERSION, "a"));
    }

    @Test
    public void anotherHunkInTheSamePathAndAnotherLineInTheSameHunkNeedReview() {
        final FileItem file = file("a", hunk(1, "first"), hunk(50, "last"));
        final var one = List.<Target>of(part(file, 0, null));
        final var two = List.<Target>of(part(file, 0, null), part(file, 1, null));
        assertFalse(same(one, two));
        assertFalse(same(two, one));
        assertFalse(same(List.of(part(file, 0, 0)), List.of(part(file, 0, 1))));
        assertFalse(same(List.of(part(file, 0, 0)), one));
    }

    @Test
    public void groupingOrderAndDuplicateCapturesDoNotChangeMeaning() {
        final FileItem file = file("a", hunk(1, "first"), hunk(50, "last"));
        assertTrue(
            same(
                List.of(part(file, 0, null), part(file, 1, null)),
                List.of(part(file, 1, null), part(file, 0, 1), part(file, 0, 0), part(file, 1, null))));
        assertTrue(same(List.of(whole(file)), List.of(part(file, 0, null), whole(file))));
        final FileItem other = file("b");
        final var versions = versions(VERSION, "a", "b");
        assertTrue(
            Selections
                    .sameChanges(List.of(whole(file, other)), versions, List.of(whole(other), whole(file)), versions));
    }

    @Test
    public void contextAndDisplayCoordinatesCanMoveWithoutChangingTheSelectedLines() {
        final FileItem before = file("a", hunk(1, "first"));
        final Hunk moved = new Hunk(1,
                1,
                50,
                3,
                "different context",
                List.of(
                    new DiffLine(' ', "context", false),
                    new DiffLine('+', "first", false),
                    new DiffLine('+', "second", false)));
        final FileItem after = file("a", moved);
        assertTrue(same(List.of(part(before, 0, 0)), List.of(part(after, 0, 1))));
        assertTrue(same(List.of(part(before, 0, null)), List.of(part(after, 0, null))));
    }

    @Test
    public void unselectedHunksDoNotInvalidateAPartialReviewWithTheSameStagedVersion() {
        final FileItem before = file("a", hunk(1, "selected"), hunk(50, "unselected"));
        final FileItem after = file("a", hunk(1, "selected"), hunk(50, "changed elsewhere"));
        assertTrue(same(List.of(part(before, 0, null)), List.of(part(after, 0, null))));
    }

    @Test
    public void baseAndVersionedPropertiesMatterEvenForPartialChanges() {
        final FileItem file = file("a", hunk(1, "selected"));
        for (ChangeVersion changed : List.of(
            new ChangeVersion(new FileVersion("other base", "properties"), VERSION.staged()),
            new ChangeVersion(BASE, new FileVersion("working", "other properties")),
            new ChangeVersion(new FileVersion("base", "other base properties"), VERSION.staged()))) {
            assertFalse(
                Selections.sameChanges(
                    List.of(part(file, 0, null)),
                    versions(VERSION, "a"),
                    List.of(part(file, 0, null)),
                    versions(changed, "a")));
        }
    }

    @Test
    public void unreadFailedAndUnsupportedPartialTargetsNeverCompareEqual() {
        final FileItem file = file("a", hunk(1, "selected"));
        assertFalse(Selections.sameChanges(List.of(whole(file)), Map.of(), List.of(whole(file)), Map.of()));
        final FileItem failed = new FileItem(file.change(), 'M', false, null, "read failed");
        assertFalse(same(List.of(whole(failed)), List.of(whole(failed))));
        final FileItem unread = new FileItem(file.change(), 'M', false, null);
        final var target = Target.Part.of(repository, SECTION, unread, file.diff().hunks().get(0), null);
        assertFalse(same(List.of(target), List.of(target)));
    }

    @Test
    public void wholeBinaryEmptyNewAndDeletedFilesCompareCompleteVersions() {
        final FileItem file =
                new FileItem(new FileChange("a", null, null, FileChange.Kind.UNTRACKED, "??"), '?', false, null);
        for (ChangeVersion version : List.of(
            new ChangeVersion(null, new FileVersion("empty bytes", "properties")),
            new ChangeVersion(null, new FileVersion("binary bytes", "properties")),
            new ChangeVersion(BASE, null))) {
            assertTrue(
                Selections.sameChanges(
                    List.of(whole(file)),
                    versions(version, "a"),
                    List.of(whole(file)),
                    versions(version, "a")));
            assertFalse(
                Selections.sameChanges(
                    List.of(whole(file)),
                    versions(version, "a"),
                    List.of(whole(file)),
                    versions(VERSION, "a")));
        }
    }

    @Test
    public void literalPathsSectionsRenameSourcesAndDuplicateOccurrencesStayDistinct() {
        final FileItem file = file("a", hunk(1, "same"), hunk(50, "same"));
        assertFalse(same(List.of(part(file, 0, null)), List.of(part(file, 1, null))));
        final FileItem one = file("a", hunk(1, "same"));
        assertFalse(same(List.of(part(file, 0, null)), List.of(part(one, 0, null))));
        final Target.Files otherSection = new Target.Files(repository, "opaque", List.of(file));
        final var both = versions(VERSION, "a");
        assertFalse(Selections.sameChanges(List.of(whole(file)), both, List.of(otherSection), both));
        final FileItem renamed = new FileItem(new FileChange("a", "old|@name", null, FileChange.Kind.MODIFIED, " M"),
                'M',
                false,
                file.diff());
        assertFalse(same(List.of(whole(file)), List.of(whole(renamed))));
        final FileItem other = file("a|@#");
        assertFalse(
            Selections.sameChanges(
                List.of(whole(file)),
                versions(VERSION, "a"),
                List.of(whole(other)),
                versions(VERSION, other.path())));
    }

    @Test
    public void identicalInsertionsAtDifferentBaseGapsNeedReview() {
        final var before = List.<Target>of(part(file("a", hunk(1, "selected")), 0, null));
        final var after = List.<Target>of(part(file("a", hunk(50, "selected")), 0, null));
        assertFalse(same(before, after));
        assertFalse(Selections.sameBaseLocations(before, after));
    }

    @Test
    public void changedBytesAndMissingNewlinesNeedReview() {
        final FileItem before = file("a", hunk(1, "selected"));
        assertFalse(same(List.of(part(before, 0, null)), List.of(part(file("a", hunk(1, "edited")), 0, null))));
        final FileItem after = file(
            "a",
            new Hunk(1, 0, 1, 2, "", List.of(new DiffLine('+', "selected", false), new DiffLine('+', "second", true))));
        assertFalse(same(List.of(part(before, 0, null)), List.of(part(after, 0, null))));
    }
}
