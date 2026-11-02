/*
 * GitWritesTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitWritesTest {
    @TempDir
    Path tmp;

    private GitFixture git;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    // A real UU conflict on a.txt: ours and theirs both changed it.
    private VcsRepository conflict() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "base\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "theirs\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
        return Repositories.forDirectory(git.root);
    }

    @Test
    public void checkoutSideOursGivesOurTextAndStaysUnmerged() throws Exception {
        final VcsRepository r = conflict();
        assertTrue(r.backend().checkoutSide(r, List.of("a.txt"), true).succeeded());
        assertEquals("ours\n", git.read("a.txt"));
        assertTrue(statusHasUnmerged(), "still unmerged");
    }

    @Test
    public void checkoutSideTheirsGivesTheirTextAndStaysUnmerged() throws Exception {
        final VcsRepository r = conflict();
        assertTrue(r.backend().checkoutSide(r, List.of("a.txt"), false).succeeded());
        assertEquals("theirs\n", git.read("a.txt"));
        assertTrue(statusHasUnmerged(), "still unmerged");
    }

    @Test
    public void restoreConflictPutsMarkersBackWithABase() throws Exception {
        final VcsRepository r = conflict();
        r.backend().checkoutSide(r, List.of("a.txt"), true);
        assertTrue(r.backend().restoreConflict(r, List.of("a.txt")).succeeded());
        final String text = git.read("a.txt");
        assertTrue(text.contains("<<<<<<<"), text);
        assertTrue(text.contains("======="), text);
        assertTrue(text.contains(">>>>>>>"), text);
        assertTrue(text.contains("|||||||"), "the base between the sides: " + text);
        assertTrue(statusHasUnmerged());
    }

    private boolean statusHasUnmerged() {
        return git.git("status", "--porcelain").contains("UU a.txt");
    }
}
