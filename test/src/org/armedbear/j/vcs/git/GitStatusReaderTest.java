/*
 * GitStatusReaderTest.java
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

package org.armedbear.j.vcs.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusData.StashItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitStatusReaderTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    // The newest stash's files, read with its diffs, ignoring whitespace so.
    private static List<FileItem> stashFiles(GitFixture git, Whitespace ignored) {
        final StatusData d = GitStatusReader.read(Repositories.forDirectory(git.root), (section, key) -> true, ignored);
        for (Section s : d.sections())
            if (s.id().equals(StatusData.STASHES))
                return ((StashItem) s.items().get(0)).files();
        throw new AssertionError("no stash");
    }

    @Test
    public void ignoringWhitespaceAStashKeepsAFileChangedOnlyInThat() throws Exception {
        final GitFixture git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", "a changed\n");
        git.write("b.txt", "b  \n");
        git.git("stash");
        assertEquals(List.of("a.txt", "b.txt"), stashFiles(git, Whitespace.NONE).stream().map(FileItem::path).toList());
        final List<FileItem> files = stashFiles(git, Whitespace.ALL);
        assertEquals(List.of("a.txt", "b.txt"), files.stream().map(FileItem::path).toList());
        assertTrue(!files.get(0).diff().hunks().isEmpty());
        assertSame(FileDiff.ONLY_WHITESPACE, files.get(1).diff());
    }
}
