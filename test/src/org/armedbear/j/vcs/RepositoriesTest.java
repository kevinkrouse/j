/*
 * RepositoriesTest.java
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

package org.armedbear.j.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding repositories needs only their metadata directories, not git. */
public class RepositoriesTest {
    @TempDir
    Path tmp;

    private Path dir(String relative) throws IOException {
        return Files.createDirectories(tmp.toRealPath().resolve(relative));
    }

    private static File file(Path p) {
        return File.getInstance(p.toString());
    }

    @Test
    public void findsTheNearestRoot() throws IOException {
        dir("outer/.git");
        dir("outer/inner/.git");
        Path src = dir("outer/inner/src");
        VcsRepository inner = Repositories.forFile(file(src));
        assertNotNull(inner);
        assertEquals(tmp.toRealPath().resolve("outer/inner"), inner.root());
        assertSame(inner, Repositories.forDirectory(tmp.toRealPath().resolve("outer/inner")));
        assertEquals(tmp.toRealPath().resolve("outer"), Repositories.forFile(file(dir("outer/x"))).root());
    }

    @Test
    public void findsNothingOutsideARepository() throws IOException {
        Path d = dir("plain/sub");
        VcsRepository r = Repositories.forFile(file(d));
        // Unless the temporary directory is itself in a repository.
        if (r != null)
            assertEquals(false, r.root().startsWith(tmp.toRealPath()));
        assertNull(Repositories.forFile(null));
    }

    @Test
    public void findsAProjectsRepositoriesBesideItsMarker() throws IOException {
        dir("ws/.j-project");
        dir("ws/b-repo/.git");
        dir("ws/a-repo/.git");
        dir("ws/not-a-repo");
        // Two levels down is not looked at.
        dir("ws/group/deep/.git");
        List<VcsRepository> project = Repositories.forProject(file(dir("ws")));
        Path ws = tmp.toRealPath().resolve("ws");
        assertEquals(
            List.of(ws.resolve("a-repo"), ws.resolve("b-repo")),
            project.stream().map(VcsRepository::root).toList());
        assertEquals(0, Repositories.colorIndex(project.get(0), project));
        assertEquals(1, Repositories.colorIndex(project.get(1), project));
    }

    @Test
    public void aProjectThatIsARepositoryComesFirstAmongItsOwn() throws IOException {
        dir("mono/.git");
        dir("mono/.j-project");
        dir("mono/sub/.git");
        List<VcsRepository> project = Repositories.forProject(file(dir("mono")));
        Path mono = tmp.toRealPath().resolve("mono");
        assertEquals(List.of(mono, mono.resolve("sub")), project.stream().map(VcsRepository::root).toList());
    }

    @Test
    public void aLinkedRepositoryIsTheOneItLinksTo() throws IOException {
        dir("real/.git");
        Path ws = dir("lws");
        dir("lws/.j-project");
        Files.createSymbolicLink(ws.resolve("link"), ws.resolve("../real"));
        List<VcsRepository> project = Repositories.forProject(file(ws));
        assertEquals(1, project.size());
        VcsRepository viaFile = Repositories.forFile(file(dir("lws/link/src")));
        assertSame(project.get(0), viaFile);
        assertEquals(0, Repositories.colorIndex(viaFile, project));
    }

    @Test
    public void withoutAMarkerOnlyTheRootsOwn() throws IOException {
        dir("solo/.git");
        dir("solo/vendored/.git");
        List<VcsRepository> project = Repositories.forProject(file(dir("solo")));
        assertEquals(1, project.size());
    }
}
