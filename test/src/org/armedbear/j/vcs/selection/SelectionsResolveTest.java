/*
 * SelectionsResolveTest.java
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

package org.armedbear.j.vcs.selection;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SelectionsResolveTest {
    @TempDir
    Path tmp;
    private VcsRepository repository;

    @BeforeEach
    public void repository() throws Exception {
        GitFixture.metadata(tmp);
        repository = Repositories.forDirectory(tmp.toRealPath());
    }

    private static Hunk hunk(int row, String added) {
        return new Hunk(row, 1, row, 1, "", List.of(new DiffLine('-', "old", false), new DiffLine('+', added, false)));
    }

    private static FileDiff diff(Hunk... hunks) {
        return new FileDiff(List.of(), "a.txt", "a.txt", false, false, false, false, List.of(hunks), false);
    }

    private static FileItem item(String path, FileDiff diff) {
        return new FileItem(new FileChange(path, null, null, FileChange.Kind.MODIFIED, " M"), 'M', false, diff);
    }

    private Map<VcsRepository, StatusData> status(FileItem... files) {
        return Map.of(
            repository,
            new StatusData(List.of(),
                    null,
                    List.of(new StatusData.Section("section|opaque", "Changes", List.of(files))),
                    null));
    }

    private Target.Part part(FileItem file) {
        return new Target.Part(repository, "section|opaque", file, file.diff().hunks().get(0), null);
    }

    private Selections.Resolution resolve(List<Target> targets, FileItem... files) {
        return Selections.resolve(targets, status(files), key -> false);
    }

    @Test
    public void wholeFilesTakeTheirCurrentChangesWithoutRequiringTextualDiffs() {
        FileItem before = item("file|@#1.txt", diff(hunk(1, "before")));
        var whole = new Target.Files(repository, "section|opaque", List.of(before));
        FileDiff binary = new FileDiff(List.of(), "a", "a", false, false, true, false, List.of(), false);
        for (FileItem now : List
                .of(item(before.path(), binary), item(before.path(), FileDiff.EMPTY), item(before.path(), null))) {
            var resolved = resolve(List.of(whole), now);
            assertTrue(resolved.ready());
            assertSame(now, ((Target.Files) resolved.targets().get(0)).files().get(0));
        }
    }

    @Test
    public void unreadDiffsCanBecomeAvailableAndAnUnrelatedRefreshDoesNotInvalidateAPick() {
        FileItem original = item("a.txt", diff(hunk(1, "new")));
        var selected = part(original);
        var pending = resolve(List.of(selected), item("a.txt", null));
        assertEquals(java.util.Set.of(new FileKey(repository, "section|opaque", "a.txt")), pending.unread());
        assertTrue(pending.problems().isEmpty());
        // Lines added above in the working file: the base rows are kept.
        FileItem now = item("a.txt", diff(new Hunk(1, 1, 50, 1, "", hunk(1, "new").lines())));
        var resolved = resolve(List.of(selected), now, item("unrelated.txt", null));
        assertTrue(resolved.ready());
        assertSame(now, ((Target.Part) resolved.targets().get(0)).file());
        assertSame(now.diff().hunks().get(0), ((Target.Part) resolved.targets().get(0)).hunk());
    }

    @Test
    public void partialLinesFollowChangedLineOffsetsInsteadOfContextIndexes() {
        FileItem before = item("a.txt", diff(hunk(32, "new")));
        BitSet bits = new BitSet();
        bits.set(1);
        var selected = new Target.Part(repository, "section|opaque", before, before.diff().hunks().get(0), bits);
        Hunk moved = new Hunk(30,
                3,
                40,
                3,
                "context",
                List.of(
                    new DiffLine(' ', "one", false),
                    new DiffLine(' ', "two", false),
                    new DiffLine('-', "old", false),
                    new DiffLine('+', "new", false)));
        var resolved = resolve(List.of(selected), item("a.txt", diff(moved)));
        assertTrue(resolved.ready());
        assertEquals(BitSet.valueOf(new long[] { 8 }), ((Target.Part) resolved.targets().get(0)).selection());
        assertEquals(BitSet.valueOf(new long[] { 2 }), selected.selection());
    }

    @Test
    public void failedUnreadAndSuccessfullyEmptyDiffsHaveDistinctOutcomes() {
        FileItem before = item("a.txt", diff(hunk(1, "new")));
        var selected = List.<Target>of(part(before));
        FileItem unread = item("a.txt", null);
        var pending = resolve(selected, unread);
        assertFalse(pending.ready());
        assertFalse(pending.unread().isEmpty());
        var omitted = Selections.resolve(selected, status(unread), key -> true);
        assertTrue(
            omitted.problems().get(0).message().matches("(?s).*(read|returned|failed|offline|unavailable).*"),
            omitted.problems().get(0).message());
        assertTrue(omitted.unread().isEmpty());
        var exhausted = pending.readsFailed();
        assertTrue(exhausted.unread().isEmpty());
        assertTrue(
            exhausted.problems().get(0).message().matches("(?s).*(read|returned|failed|offline|unavailable).*"),
            exhausted.problems().get(0).message());
        FileItem failed = new FileItem(unread.change(), 'M', false, null, "backend read failed");
        var failure = resolve(selected, failed);
        assertTrue(
            failure.problems().get(0).message().matches("(?s).*(read|returned|failed|offline|unavailable).*"),
            failure.problems().get(0).message());
        assertTrue(failure.unread().isEmpty());
        assertTrue(failure.failure().contains("backend read failed"));
        var empty = resolve(selected, item("a.txt", FileDiff.EMPTY));
        assertTrue(empty.problems().get(0).message().contains("changed"), empty.problems().get(0).message());
        assertTrue(empty.unread().isEmpty());
    }

    @Test
    public void binaryAndCombinedChangesCannotBeTakenInPart() {
        var selected = List.<Target>of(part(item("a.txt", diff(hunk(1, "new")))));
        for (FileDiff unsupported : List.of(
            new FileDiff(List.of(), "a", "a", false, false, true, false, List.of(), false),
            FileDiff.NO_COMBINED)) {
            var result = resolve(selected, item("a.txt", unsupported));
            assertTrue(
                result.problems().get(0).message().contains("cannot be selected in part"),
                result.problems().get(0).message());
            assertTrue(result.unread().isEmpty());
        }
    }

    @Test
    public void changedAndAmbiguousHunksCannotSilentlySelectAnotherOccurrence() {
        var selected = List.<Target>of(part(item("a.txt", diff(hunk(1, "Aa")))));
        var changed = resolve(selected, item("a.txt", diff(hunk(1, "BB"))));
        assertTrue(changed.problems().get(0).message().contains("changed"), changed.problems().get(0).message());
        var moved = resolve(selected, item("a.txt", diff(hunk(30, "Aa"))));
        assertTrue(moved.problems().get(0).message().contains("moved"), moved.problems().get(0).message());
        assertTrue(moved.targets().isEmpty());
    }

    @Test
    public void aChangeUndoneAndMadeAgainElsewhereDoesNotTakeThePick() {
        // One X picked at 10; then it undone and made at 80.
        final FileItem single = item("a.txt", diff(hunk(10, "X")));
        final var one = List.<Target>of(part(single));
        final var elsewhere = resolve(one, item("a.txt", diff(hunk(80, "X"))));
        assertTrue(elsewhere.problems().get(0).message().contains("moved"), elsewhere.problems().get(0).message());
        assertTrue(elsewhere.targets().isEmpty());
        // X at 10 and at 100, the one at 100 picked; then X at 10 gone and
        // one at 200 come: the pick stays at 100.
        final FileItem before = item("a.txt", diff(hunk(10, "X"), hunk(100, "X")));
        final var picked = List
                .<Target>of(new Target.Part(repository, "section|opaque", before, before.diff().hunks().get(1), null));
        final var moved = resolve(picked, item("a.txt", diff(hunk(100, "X"), hunk(200, "X"))));
        assertTrue(moved.problems().isEmpty());
        assertEquals(100, ((Target.Part) moved.targets().get(0)).hunk().oldStart());
        // The one at 10 picked instead: gone with it.
        final var first = List
                .<Target>of(new Target.Part(repository, "section|opaque", before, before.diff().hunks().get(0), null));
        assertFalse(resolve(first, item("a.txt", diff(hunk(100, "X"), hunk(200, "X")))).problems().isEmpty());
    }

    @Test
    public void twinsShiftedByAWriteAboveInTheBaseKeepThePick() {
        // Staging two lines above both: base rows move, working rows don't.
        final FileItem before = item("a.txt", diff(hunk(10, "X"), hunk(50, "X")));
        final var picked = List
                .<Target>of(new Target.Part(repository, "section|opaque", before, before.diff().hunks().get(1), null));
        final Hunk x = hunk(1, "X");
        final var shifted = resolve(
            picked,
            item("a.txt", diff(new Hunk(12, 1, 10, 1, "", x.lines()), new Hunk(52, 1, 50, 1, "", x.lines()))));
        assertTrue(shifted.problems().isEmpty());
        assertEquals(52, ((Target.Part) shifted.targets().get(0)).hunk().oldStart());
    }

    @Test
    public void oneGoneTargetPreventsACommandButValidTargetsRemainAvailableToPicks() {
        var first = part(item("a.txt", diff(hunk(1, "new"))));
        var missing = part(item("gone.txt", diff(hunk(1, "new"))));
        var resolved = resolve(List.of(first, missing), first.file());
        assertFalse(resolved.ready());
        assertEquals(1, resolved.targets().size());
        assertEquals("gone.txt", resolved.problems().get(0).file().path());
        assertTrue(
            resolved.problems().get(0).message().contains("no longer in this section"),
            resolved.problems().get(0).message());
        assertThrows(UnsupportedOperationException.class, () -> resolved.targets().clear());
    }

    @Test
    public void statusErrorsAreReadFailuresAndSectionsStaySeparate() {
        var selected = List.<Target>of(part(item("a.txt", diff(hunk(1, "new")))));
        var failure = Selections.resolve(selected, Map.of(repository, StatusData.failed("offline")), key -> false);
        assertTrue(
            failure.problems().get(0).message().matches("(?s).*(read|returned|failed|offline|unavailable).*"),
            failure.problems().get(0).message());
        var otherSection = new StatusData(List.of(),
                null,
                List.of(new StatusData.Section("other", "Other", List.of(((Target.Part) selected.get(0)).file()))),
                null);
        var gone = Selections.resolve(selected, Map.of(repository, otherSection), key -> false);
        assertTrue(
            gone.problems().get(0).message().contains("no longer in this section"),
            gone.problems().get(0).message());
    }

    @Test
    public void targetsFromDifferentReadsCanShareACurrentDiff() {
        FileItem before = item("a.txt", diff(hunk(1, "new")));
        FileItem now = item("a.txt", diff(new Hunk(1, 1, 30, 1, "", hunk(1, "new").lines())));
        var result = resolve(List.of(part(before), part(now)), now);
        assertTrue(result.ready());
        assertEquals(2, result.targets().size());
    }
}
