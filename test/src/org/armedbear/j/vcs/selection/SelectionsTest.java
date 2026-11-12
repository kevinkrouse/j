/*
 * SelectionsTest.java
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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SelectionsTest {
    @TempDir
    Path tmp;

    private static Hunk hunk(int row, String added, boolean noNewline, String... context) {
        final List<DiffLine> lines = new ArrayList<>();
        for (String text : context)
            lines.add(new DiffLine(' ', text, false));
        lines.add(new DiffLine('-', "old", false));
        lines.add(new DiffLine('+', added, noNewline));
        return new Hunk(row, context.length + 1, row, context.length + 1, "", lines);
    }

    // A hunk at old row and new row, both from 1.
    private static Hunk at(int old, int now, String added, String... context) {
        final List<DiffLine> lines = new ArrayList<>();
        for (String text : context)
            lines.add(new DiffLine(' ', text, false));
        lines.add(new DiffLine('-', "old", false));
        lines.add(new DiffLine('+', added, false));
        return new Hunk(old, context.length + 1, now, context.length + 1, "", lines);
    }

    private static FileDiff diff(Hunk... hunks) {
        return new FileDiff(List.of(), "a.txt", "a.txt", false, false, false, false, List.of(hunks), false);
    }

    private static String firstId(Selections.Hunks index) {
        return index.hunks().keySet().iterator().next();
    }

    @Test
    public void contextRowsDoNotBlockChangedRowsInAnotherHunk() throws Exception {
        GitFixture.metadata(tmp);
        final var repository = Repositories.forDirectory(tmp.toRealPath());
        final Hunk context = hunk(1, "first", false, "context");
        final Hunk changed = hunk(50, "second", false);
        final FileItem file = new FileItem(new org.armedbear.j.vcs.FileChange("a.txt",
                null,
                null,
                org.armedbear.j.vcs.FileChange.Kind.MODIFIED,
                " M"), 'M', false, diff(context, changed));
        final List<Target> targets = new Selections.Builder().line(repository, "working", file, context, 0)
                .line(repository, "working", file, changed, 1)
                .build();
        assertEquals(1, targets.size());
        assertSame(changed, ((Target.Part) targets.getFirst()).hunk());
    }

    @Test
    public void hashCollisionsAndNewlineChangesHaveDifferentIdentities() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var aa = Selections.fingerprint(hunk(1, "Aa", false));
        assertNotEquals(aa, Selections.fingerprint(hunk(1, "BB", false)));
        assertNotEquals(aa, Selections.fingerprint(hunk(1, "Aa", true)));
        assertNotEquals(aa, Selections.fingerprint(hunk(1, "\u0080\u00ff", false)));
    }

    @Test
    public void contextAndPositionsDoNotContributeToIdentity() {
        Hunk original = at(10, 10, "new", "before");
        Hunk moved = at(8, 60, "new", "different", "more", "context");
        var before = Selections.index(diff(original));
        var after = Selections.index(diff(moved));
        String id = firstId(before);
        assertEquals(id, firstId(after));
        assertSame(moved, Selections.match(after, original, Selections.fingerprint(original)));
    }

    @Test
    public void aChangeIsFoundWhereEitherSideKeepsItsRows() {
        Hunk original = at(10, 10, "new");
        var content = Selections.fingerprint(original);
        var baseKept = at(10, 14, "new");
        var workingKept = at(13, 10, "new");
        var neither = at(13, 14, "new");
        assertSame(baseKept, Selections.match(Selections.index(diff(baseKept)), original, content));
        assertSame(workingKept, Selections.match(Selections.index(diff(workingKept)), original, content));
        assertNull(Selections.match(Selections.index(diff(neither)), original, content));
        // One keeping the base rows, another the working rows: ambiguous.
        assertNull(Selections.match(Selections.index(diff(baseKept, at(40, 10, "new"))), original, content));
    }

    @Test
    public void identicalOccurrencesAreFoundWhereTheyWere() {
        var original = diff(hunk(1, "new", false), hunk(20, "new", false));
        var before = Selections.index(original);
        var fewer = Selections.index(diff(hunk(20, "new", false)));
        var more = Selections.index(diff(hunk(1, "new", false), hunk(20, "new", false), hunk(40, "new", false)));
        var moved = Selections.index(diff(hunk(10, "new", false), hunk(30, "new", false)));
        final Hunk first = original.hunks().get(0);
        final Hunk second = original.hunks().get(1);
        final var content = Selections.fingerprint(first);
        assertNull(Selections.match(fewer, first, content));
        assertEquals(20, Selections.match(fewer, second, content).oldStart());
        assertEquals(1, Selections.match(more, first, content).oldStart());
        assertEquals(20, Selections.match(more, second, content).oldStart());
        assertNull(Selections.match(moved, first, content));
        assertNull(Selections.match(moved, second, content));
        assertThrows(UnsupportedOperationException.class, () -> before.hunks().clear());
    }

    @Test
    public void selectedChangedLinesAreRemappedAcrossContextChanges() {
        Hunk before = hunk(1, "new", false, "context");
        Hunk after = hunk(50, "new", false, "one", "two", "three");
        BitSet bits = new BitSet();
        bits.set(0); // A context line never becomes part of the selection.
        bits.set(2); // The added line.
        BitSet selection = Selections.changedLines(before, bits);
        assertEquals(BitSet.valueOf(new long[] { 2 }), selection);
        assertEquals(BitSet.valueOf(new long[] { 16 }), Selections.hunkLines(after, selection));
        assertEquals("new", after.lines().get(Selections.hunkLines(after, selection).nextSetBit(0)).text());
    }

    @Test
    public void hunkSplitsAndJoinsDoNotGuessWhichChangesWereSelected() {
        Hunk one = hunk(1, "one", false), two = hunk(20, "two", false);
        var lines = new ArrayList<>(one.lines());
        lines.add(new DiffLine(' ', "between", false));
        lines.addAll(two.lines());
        Hunk joined = new Hunk(1, 3, 1, 3, "", lines);
        var separate = Selections.index(diff(one, two));
        var together = Selections.index(diff(joined));
        for (String id : separate.hunks().keySet())
            assertNull(
                Selections.match(together, separate.hunks().get(id), Selections.fingerprint(separate.hunks().get(id))));
        assertNull(Selections.match(separate, joined, Selections.fingerprint(joined)));
    }

    @Test
    public void fileKeysKeepOpaqueSectionsAndLiteralPathsSeparate() throws Exception {
        GitFixture.metadata(tmp);
        var repository = Repositories.forDirectory(tmp.toRealPath());
        FileKey a = new FileKey(repository, "section|part", "file|@change#1\u0001.txt");
        FileKey b = new FileKey(repository, "section", "part|file|@change#1\u0001.txt");
        Map<FileKey, String> files = new HashMap<>();
        files.put(a, "a");
        files.put(b, "b");
        assertEquals(2, files.size());
        assertEquals("a", files.get(new FileKey(repository, a.section(), a.path())));
        assertNotEquals(a.scope(), b.scope());
        assertEquals("file|@change#1\u0001.txt", a.path());
    }

    @Test
    public void aCapturePairsLinesAndFreezesItsSelectionBeforeLaterEdits() throws Exception {
        GitFixture.metadata(tmp);
        var repository = Repositories.forDirectory(tmp.toRealPath());
        Hunk hunk = new Hunk(1,
                2,
                1,
                2,
                "",
                List.of(
                    new DiffLine('-', "old1", false),
                    new DiffLine('-', "old2", false),
                    new DiffLine('+', "new1", false),
                    new DiffLine('+', "new2", false)));
        var file = new FileItem(new FileChange("a.txt", null, null, FileChange.Kind.MODIFIED, " M"),
                'M',
                false,
                diff(hunk));
        var capture = new Selections.Builder().line(repository, "section", file, hunk, 2);
        var first = capture.build();
        assertEquals(BitSet.valueOf(new long[] { 5 }), ((Target.Part) first.get(0)).selection());
        capture.line(repository, "section", file, hunk, 3);
        assertEquals(BitSet.valueOf(new long[] { 5 }), ((Target.Part) first.get(0)).selection());
        assertEquals(BitSet.valueOf(new long[] { 15 }), ((Target.Part) capture.build().get(0)).selection());
        capture.file(repository, "section", file);
        assertEquals(1, capture.build().size());
        assertInstanceOf(Target.Files.class, capture.build().get(0));
    }

    @Test
    public void aSharedHunkObjectNeverMergesDifferentPathsOrSections() throws Exception {
        GitFixture.metadata(tmp);
        var repository = Repositories.forDirectory(tmp.toRealPath());
        var hunk = hunk(1, "new", false);
        var a = new FileItem(new FileChange("a.txt", null, null, FileChange.Kind.MODIFIED, " M"),
                'M',
                false,
                diff(hunk));
        var b = new FileItem(new FileChange("b.txt", null, null, FileChange.Kind.MODIFIED, " M"), 'M', false, a.diff());
        var capture = new Selections.Builder().lines(repository, "staged", a, hunk, null)
                .lines(repository, "unstaged", a, hunk, null)
                .lines(repository, "unstaged", b, hunk, null);
        assertEquals(3, capture.build().size());
    }

    @Test
    public void splitMembersShareOneIndexPerCapture() throws Exception {
        GitFixture.metadata(tmp);
        var repository = Repositories.forDirectory(tmp.toRealPath());
        var diff = diff(hunk(1, "one", false), hunk(30, "two", false));
        var file = new FileItem(new FileChange("a.txt", null, null, FileChange.Kind.MODIFIED, " M"), 'M', false, diff);
        var ids = Selections.index(diff).hunks().keySet().stream().toList();
        int[] indexes = { 0 };
        var capture = new Selections.Builder(d -> {
            indexes[0]++;
            return Selections.index(d).hunks();
        });
        for (String id : ids)
            capture.member(
                repository,
                "section",
                new Target.Member(file, Map.of(id, BitSet.valueOf(new long[] { 2 }))));
        capture.member(
            repository,
            "section",
            new Target.Member(file, Map.of(ids.get(0), BitSet.valueOf(new long[] { 1 }))));
        assertEquals(2, capture.build().size());
        assertEquals(1, indexes[0]);
    }

    @Test
    public void captureDoesNotRetainACallersMutableLineSet() throws Exception {
        GitFixture.metadata(tmp);
        final var repository = Repositories.forDirectory(tmp.toRealPath());
        final Hunk hunk = new Hunk(0,
                0,
                1,
                3,
                "",
                List.of(
                    new DiffLine('+', "one", false),
                    new DiffLine('+', "two", false),
                    new DiffLine('+', "three", false)));
        final var file = new FileItem(new FileChange("a.txt", null, null, FileChange.Kind.MODIFIED, " M"),
                'M',
                false,
                diff(hunk));
        final BitSet supplied = new BitSet();
        supplied.set(1);
        final var capture = new Selections.Builder().lines(repository, "section", file, hunk, supplied);
        // Capture must copy in, before build is called.
        supplied.clear();
        supplied.set(0);
        final var selected = (Target.Part) capture.build().get(0);
        assertEquals(BitSet.valueOf(new long[] { 2 }), selected.selection());
        // Neither later caller edits nor a writable accessor may change it.
        supplied.set(2);
        selected.selection().clear();
        assertEquals(BitSet.valueOf(new long[] { 2 }), selected.selection());
        assertEquals(BitSet.valueOf(new long[] { 2 }), ((Target.Part) capture.build().get(0)).selection());
    }
}
