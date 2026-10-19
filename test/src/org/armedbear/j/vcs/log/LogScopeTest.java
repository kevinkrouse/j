/*
 * LogScopeTest.java
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
package org.armedbear.j.vcs.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LogScopeTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void tearDown() {
        GitFixture.reset();
    }

    private VcsRepository repo(Path dir) throws Exception {
        Files.createDirectories(dir);
        return Repositories.forDirectory(GitFixture.init(dir).root);
    }

    @Test
    public void oneRepositorysPathsAreItsOwn() throws Exception {
        final VcsRepository a = repo(tmp.resolve("a"));
        final LogScope scope = LogScope.of(a);
        assertFalse(scope.isSeveral());
        assertEquals(a.root(), scope.base());
        final LogQuery q = LogQuery.ALL.withPath("src/x.txt", false);
        assertEquals(q, scope.queryFor(a, q));
        assertEquals("git log: a src/x.txt", scope.title(q));
    }

    @Test
    public void severalSharePathsFromTheirBaseAndAPathGoesToItsRepository() throws Exception {
        final VcsRepository top = repo(tmp.resolve("p"));
        final VcsRepository lib = repo(tmp.resolve("p/lib"));
        final VcsRepository app = repo(tmp.resolve("p/app"));
        final LogScope scope = LogScope.of(List.of(app, lib, top));
        assertEquals(top.root(), scope.base());
        assertEquals("lib/", scope.prefix(lib));
        assertEquals("", scope.prefix(top));
        assertEquals("git log: 3 repositories", scope.title(LogQuery.ALL));

        final LogQuery inLib = LogQuery.ALL.withPath("lib/src/x.txt", true);
        assertEquals(LogQuery.ALL.withPath("src/x.txt", true), scope.queryFor(lib, inLib));
        assertNull(scope.queryFor(top, inLib), "the nearer root holds it");
        assertNull(scope.queryFor(app, inLib));
        assertEquals(LogQuery.ALL, scope.queryFor(lib, LogQuery.ALL.withPath("lib/", false)), "its root: all of it");
        assertEquals(LogQuery.ALL.withPath("x.txt", false), scope.queryFor(top, LogQuery.ALL.withPath("x.txt", false)));

        final LogQuery onlyApp = LogQuery.ALL.withRepositories(List.of("app")).withAuthor("Ada");
        assertEquals(LogQuery.ALL.withAuthor("Ada"), scope.queryFor(app, onlyApp));
        assertNull(scope.queryFor(lib, onlyApp));
        assertEquals(lib, scope.named("lib"));
    }

    @Test
    public void theBaseOfSiblingsIsTheirParent() throws Exception {
        final VcsRepository a = repo(tmp.resolve("w/a"));
        final VcsRepository b = repo(tmp.resolve("w/b"));
        final LogScope scope = LogScope.of(List.of(a, b));
        assertEquals(a.root().getParent(), scope.base());
        assertEquals("b/", scope.prefix(b));
    }

    @Test
    public void twoOfTheSameNameAreNamedByTheirPaths() throws Exception {
        final VcsRepository a = repo(tmp.resolve("p/a/common"));
        final VcsRepository b = repo(tmp.resolve("p/b/common"));
        final VcsRepository c = repo(tmp.resolve("p/c"));
        final LogScope scope = LogScope.of(List.of(a, b, c), tmp.resolve("p").toRealPath());
        assertEquals("a/common", scope.name(a));
        assertEquals("b/common", scope.name(b));
        assertEquals("c", scope.name(c));
        assertEquals(b, scope.named("b/common"));
        assertEquals(LogQuery.ALL, scope.queryFor(b, LogQuery.ALL.withRepositories(List.of("b/common"))));
        assertNull(scope.queryFor(a, LogQuery.ALL.withRepositories(List.of("b/common"))));
    }

    @Test
    public void oneOutsideTheProjectIsUnderItsName() throws Exception {
        final VcsRepository in = repo(tmp.resolve("p/in"));
        final VcsRepository out = repo(tmp.resolve("elsewhere/out"));
        final LogScope scope = LogScope.of(List.of(in, out), tmp.resolve("p").toRealPath());
        assertEquals("in/", scope.prefix(in));
        assertEquals("out/", scope.prefix(out));
        assertEquals(
            LogQuery.ALL.withPath("x.txt", false),
            scope.queryFor(out, LogQuery.ALL.withPath("out/x.txt", false)));
        assertEquals(out.root().resolve("x.txt"), scope.resolve("out/x.txt"));
        assertNull(scope.resolve("nowhere/x.txt"), "in none of them");
        assertNull(scope.queryFor(in, LogQuery.ALL.withPath("nowhere/x.txt", false)));
        assertNull(scope.queryFor(out, LogQuery.ALL.withPath("nowhere/x.txt", false)));
    }

    @Test
    public void severalHaveAKeyApartFromTheirRootsOwn() throws Exception {
        final VcsRepository top = repo(tmp.resolve("k"));
        final VcsRepository sub = repo(tmp.resolve("k/sub"));
        final LogScope several = LogScope.of(List.of(top, sub));
        assertFalse(several.key().equals(LogScope.of(top).key()));
        assertEquals(top.root(), several.base());
    }
}
