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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // The rule is already there only as a whole line: /c.txt in /foo/c.txt
    // is not /c.txt, and a commented-out rule is not a rule.
    @Test
    public void ignoreAppendsWhereTheRuleIsOnlyPartOfAnotherLine() throws Exception {
        git = GitFixture.init(tmp);
        git.write(".gitignore", "/foo/c.txt\n# /d.txt\n");
        assertNull(GitWrites.ignore(git.root, "c.txt"));
        final String text = git.read(".gitignore");
        assertTrue(text.contains("/foo/c.txt"), text);
        assertTrue(text.contains("\n/c.txt\n"), "the top-level rule appended whole: " + text);
        assertNull(GitWrites.ignore(git.root, "d.txt"));
        assertTrue(
            git.read(".gitignore").contains("\n/d.txt\n"),
            "the commented rule didn't count: " + git.read(".gitignore"));
    }

    // A rule already on its own line is not written twice.
    @Test
    public void ignoreLeavesAnAlreadyIgnoredFileAlone() throws Exception {
        git = GitFixture.init(tmp);
        git.write(".gitignore", "/c.txt\n");
        assertNull(GitWrites.ignore(git.root, "c.txt"));
        assertEquals("/c.txt\n", git.read(".gitignore"));
    }

    // A CRLF file: the rule read back with its carriage return still matches.
    @Test
    public void ignoreSeesARuleInACRLFFile() throws Exception {
        git = GitFixture.init(tmp);
        git.write(".gitignore", "/c.txt\r\n");
        assertNull(GitWrites.ignore(git.root, "c.txt"));
        assertEquals("/c.txt\r\n", git.read(".gitignore"));
    }

    // A name with a glob character or a space is escaped so it means itself.
    @Test
    public void ignoreEscapesGlobCharacters() throws Exception {
        git = GitFixture.init(tmp);
        assertNull(GitWrites.ignore(git.root, "a*b [c] d.txt"));
        assertEquals("/a\\*b\\ \\[c\\]\\ d.txt\n", git.read(".gitignore"));
    }
}
