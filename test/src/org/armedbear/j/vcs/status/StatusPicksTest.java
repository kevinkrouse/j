/*
 * StatusPicksTest.java
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

package org.armedbear.j.vcs.status;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StatusPicksTest {
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

    private static StatusData.FileItem file(String path, Hunk... hunks) {
        var diff = new FileDiff(List.of(), path, path, false, false, false, false, List.of(hunks), false);
        return new StatusData.FileItem(new FileChange(path, null, null, FileChange.Kind.MODIFIED, " M"),
                'M',
                false,
                diff);
    }

    private Target.Part part(String section, StatusData.FileItem file, int hunk) {
        return Target.Part.of(repository, section, file, file.diff().hunks().get(hunk), null);
    }

    private Selections.Resolution resolve(StatusPicks picks, String section, StatusData.FileItem file) {
        return Selections.resolve(
            picks.captured(),
            Map.of(
                repository,
                new StatusData(List.of(),
                        null,
                        List.of(new StatusData.Section(section, section, List.of(file))),
                        null)),
            key -> false);
    }

    @Test
    public void consumptionUsesTypedLiteralFileAndSectionKeys() {
        var file = file("a|@node#1\u0001.txt", hunk(1, "new"));
        var picks = new StatusPicks();
        picks.set(List.of(part("opaque|section", file, 0), part("opaque", file, 0)), true);
        var targets = picks.targets();
        var used = picks.used(targets);
        picks.remove(used.get(targets.get(0)));
        assertFalse(picks.has(new FileKey(repository, "opaque|section", file.path())));
        assertTrue(picks.has(new FileKey(repository, "opaque", file.path())));
        assertEquals(PickState.ALL, picks.state(targets.get(1)));
    }

    @Test
    public void partialSuccessConsumesOnlyTheCompletedTarget() {
        var file = file("a.txt", hunk(1, "first"), hunk(30, "second"));
        var picks = new StatusPicks();
        picks.set(List.of(part("section", file, 0), part("section", file, 1)), true);
        var targets = picks.targets();
        var used = picks.used(targets);
        picks.remove(used.get(targets.get(0)));
        assertEquals(PickState.NONE, picks.state(targets.get(0)));
        assertEquals(PickState.ALL, picks.state(targets.get(1)));
        assertEquals(1, picks.targets().size());
    }

    @Test
    public void wholeFileSubsumesPartsAndUnpickingAHunkKeepsTheOther() {
        var file = file("a.txt", hunk(1, "first"), hunk(30, "second"));
        var picks = new StatusPicks();
        var whole = new Target.Files(repository, "section", List.of(file));
        picks.set(List.of(whole), true);
        picks.set(List.of(part("section", file, 0)), false);
        assertEquals(PickState.NONE, picks.state(part("section", file, 0)));
        assertEquals(PickState.ALL, picks.state(part("section", file, 1)));
        picks.set(List.of(whole), true);
        assertEquals(1, picks.targets().size());
        assertInstanceOf(Target.Files.class, picks.targets().get(0));
    }

    @Test
    public void retentionUsesTheResolverForAmbiguityAndKeepsValidPicks() {
        var before = file("a.txt", hunk(1, "repeated"), hunk(30, "unique"));
        var picks = new StatusPicks();
        picks.set(List.of(part("section", before, 0), part("section", before, 1)), true);
        var after = file("a.txt", hunk(1, "repeated"), hunk(20, "repeated"), hunk(50, "unique"));
        var resolved = resolve(picks, "section", after);
        assertEquals(Selections.Reason.AMBIGUOUS, resolved.problems().get(0).reason());
        picks.newRead();
        assertEquals(1, picks.retain(resolved));
        assertEquals(Selections.Reason.AMBIGUOUS, picks.droppedProblems().get(0).reason());
        assertEquals(1, picks.targets().size());
        assertSame(after, ((Target.Part) picks.targets().get(0)).file());
        assertEquals("unique", ((Target.Part) picks.targets().get(0)).hunk().lines().get(1).text());
    }

    @Test
    public void unreadPicksRemainPendingAndFailedReadsDropThemWithAReason() {
        var before = file("a.txt", hunk(1, "new"));
        var picks = new StatusPicks();
        picks.set(List.of(part("section", before, 0)), true);
        var unread = new StatusData.FileItem(before.change(), 'M', false, null);
        var pending = resolve(picks, "section", unread);
        assertFalse(pending.unread().isEmpty());
        assertEquals(0, picks.retain(pending));
        assertFalse(picks.isEmpty());
        assertEquals(1, picks.retain(pending.readsFailed()));
        assertTrue(picks.isEmpty());
        assertEquals(Selections.Reason.READ_FAILED, picks.droppedProblems().get(0).reason());
    }

    @Test
    public void partialPickRetainsItsMeaningAcrossContextChanges() {
        var before = file(
            "a.txt",
            new Hunk(1,
                    1,
                    1,
                    2,
                    "",
                    List.of(
                        new DiffLine('-', "old", false),
                        new DiffLine('+', "new", false),
                        new DiffLine('+', "extra", false))));
        var picks = new StatusPicks();
        var selected =
                new Selections.Builder().line(repository, "section", before, before.diff().hunks().get(0), 2).build();
        picks.set(selected, true);
        var moved = new Hunk(30,
                2,
                30,
                3,
                "",
                List.of(
                    new DiffLine(' ', "context", false),
                    new DiffLine('-', "old", false),
                    new DiffLine('+', "new", false),
                    new DiffLine('+', "extra", false)));
        var after = file("a.txt", moved);
        assertEquals(0, picks.retain(resolve(picks, "section", after)));
        assertEquals(PickState.SOME, picks.state(part("section", after, 0)));
        assertSame(after, ((Target.Part) picks.targets().get(0)).file());
        assertEquals(BitSet.valueOf(new long[] { 8 }), ((Target.Part) picks.targets().get(0)).lines());
    }

    @Test
    public void reverseLookupKeepsTheSecondIdenticalHunkSeparate() {
        for (int row : List.of(30, 1)) {
            final Hunk first = hunk(1, "same");
            final Hunk second = hunk(row, "same");
            assertNotSame(first, second);
            assertEquals(Selections.content(first), Selections.content(second));
            // Equal record values must also retain occurrence identity.
            if (row == 1)
                assertEquals(first, second);
            final var file = file("a.txt", first, second);
            final var picks = new StatusPicks();
            final String prefix = Selections.content(first).fingerprint();
            assertEquals(prefix + "#1", picks.hunkId(file, first));
            assertEquals(prefix + "#2", picks.hunkId(file, second));
            picks.set(List.of(part("section", file, 1)), true);
            assertEquals(PickState.NONE, picks.state(part("section", file, 0)));
            assertEquals(PickState.ALL, picks.state(part("section", file, 1)));
            assertSame(second, ((Target.Part) picks.targets().get(0)).hunk());
        }
    }
}
