/*
 * StatusSelectionTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StatusSelectionTest {
    @TempDir
    Path tmp;

    private VcsRepository repository() throws Exception {
        GitFixture.metadata(tmp);
        return Repositories.forDirectory(tmp.toRealPath());
    }

    private static FileItem item(FileDiff diff) {
        return new FileItem(new FileChange("a.txt", null, null, FileChange.Kind.MODIFIED, " M"), 'M', false, diff);
    }

    private static FileDiff diff(Hunk hunk) {
        return new FileDiff(List.of(), "a.txt", "a.txt", false, false, false, false, List.of(hunk), false);
    }

    private static StatusLine file(VcsRepository repository, String section, FileItem item) {
        return new StatusLine(Kind.FILE, "a.txt", "file", "file", repository, section).file(item);
    }

    private static StatusLine hunk(VcsRepository repository, String section, FileItem item, Hunk hunk) {
        return new StatusLine(Kind.HUNK, "@@", "hunk", "hunk", repository, section).file(item).hunk(hunk);
    }

    @Test
    public void aWholeFileCoversItsLargeExpandedDiffWithoutQuadraticWork() throws Exception {
        final int count = 64_000;
        final var changed = new ArrayList<DiffLine>();
        for (int i = 0; i < count; i++)
            changed.add(new DiffLine('+', "line " + i, false));
        final Hunk hunk = new Hunk(0, 0, 1, count, "", changed);
        final FileItem item = item(diff(hunk));
        final VcsRepository repository = repository();
        final var selection = new ArrayList<StatusLine>();
        selection.add(file(repository, "unstaged", item));
        selection.add(hunk(repository, "unstaged", item, hunk));
        for (int i = 0; i < count; i++)
            selection.add(
                new StatusLine(Kind.DIFF, "+line", "line" + i, "hunk", repository, "unstaged").file(item)
                        .hunk(hunk)
                        .diff(changed.get(i), i, 0, i + 1));
        // Only selection construction is timed. The generous bound catches
        // hashing all 64,000 diff lines for each row (seconds of UI freeze),
        // while allowing ample time for the linear walk on slower machines.
        final List<Target> targets =
                assertTimeout(Duration.ofSeconds(5), () -> StatusActions.targetsOf(null, selection));
        assertEquals(1, targets.size());
        assertEquals(List.of(item), ((Target.Files) targets.get(0)).files());
    }

    @Test
    public void aWholeFileCoversAnEarlierPartByPathWithoutReplacingTheFirstFile() throws Exception {
        final VcsRepository repository = repository();
        final Hunk hunk = new Hunk(0, 0, 1, 1, "", List.of(new DiffLine('+', "new", false)));
        final FileItem partial = item(diff(hunk));
        final FileItem whole = item(null);
        final List<Target> targets = StatusActions.targetsOf(
            null,
            List.of(
                hunk(repository, "unstaged", partial, hunk),
                file(repository, "unstaged", whole),
                file(repository, "unstaged", partial)));
        assertEquals(1, targets.size());
        final List<FileItem> files = ((Target.Files) targets.get(0)).files();
        assertEquals(1, files.size());
        assertSame(whole, files.get(0));
    }

    @Test
    public void coverageKeepsRepositoriesAndSectionsSeparateAndFilesInOrder() throws Exception {
        final VcsRepository repository = repository();
        final Path other = tmp.resolve("other");
        GitFixture.metadata(other);
        final VcsRepository another = Repositories.forDirectory(other.toRealPath());
        final FileItem a = item(null);
        final FileItem b =
                new FileItem(new FileChange("b.txt", null, null, FileChange.Kind.MODIFIED, " M"), 'M', false, null);
        final List<Target> targets = StatusActions.targetsOf(
            null,
            List.of(
                file(repository, "unstaged", b),
                file(repository, "unstaged", a),
                file(repository, "staged", a),
                file(another, "unstaged", a)));
        assertEquals(3, targets.size());
        assertEquals(List.of(b, a), ((Target.Files) targets.get(0)).files());
        assertEquals("staged", targets.get(1).section());
        assertSame(another, targets.get(2).repository());
    }
}
