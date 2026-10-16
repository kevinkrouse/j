/*
 * LogScope.java
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

import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;

/**
 * The repositories a log shows: one, or a project's several, listed
 * together. A path in its query is from the one's root, or for several
 * from the project, each repository's under its prefix ("lib/"), so a path
 * filter goes to the repository holding it.
 *
 * @param repositories sorted by path, as the project lists them
 * @param base the one's root, or the project's directory; for several
 *     with none given, the directory above them all, or null if none is
 */
public record LogScope(List<VcsRepository> repositories, Path base) {
    public LogScope {
        repositories = List.copyOf(repositories);
    }

    public static LogScope of(VcsRepository repository) {
        return new LogScope(List.of(repository), repository.root());
    }

    public static LogScope of(List<VcsRepository> repositories) {
        return of(repositories, null);
    }

    /** Several from base, the project's directory, or above them all for null. */
    public static LogScope of(List<VcsRepository> repositories, Path base) {
        if (repositories.size() == 1)
            return of(repositories.get(0));
        Path b = base;
        if (b == null) {
            b = repositories.get(0).root();
            for (VcsRepository r : repositories)
                while (b != null && !r.root().startsWith(b))
                    b = b.getParent();
        }
        return new LogScope(repositories, b);
    }

    public boolean isSeveral() {
        return repositories.size() > 1;
    }

    /** The first repository, the one of a log of one. */
    public VcsRepository first() {
        return repositories.get(0);
    }

    /**
     * A repository's name, as the repository filter, the strip and the
     * details name it: its directory's, or, if another's is the same, its
     * path from base.
     */
    public String name(VcsRepository repository) {
        final String own = dirName(repository);
        for (VcsRepository r : repositories)
            if (r != repository && dirName(r).equals(own)) {
                if (!under(repository))
                    return repository.root().toString();
                final String path = base.relativize(repository.root()).toString().replace('\\', '/');
                return path.isEmpty() ? own : path;
            }
        return own;
    }

    private static String dirName(VcsRepository repository) {
        final Path name = repository.root().getFileName();
        return name != null ? name.toString() : repository.root().toString();
    }

    private boolean under(VcsRepository repository) {
        return base != null && repository.root().startsWith(base);
    }

    /** The repository named, or null. */
    public VcsRepository named(String name) {
        for (VcsRepository r : repositories)
            if (name(r).equals(name))
                return r;
        return null;
    }

    /**
     * Its paths' prefix in a log of several: its path from base ("lib/"),
     * "" for base's own, or its name for one not under base.
     */
    public String prefix(VcsRepository repository) {
        if (!isSeveral())
            return "";
        if (!under(repository))
            return name(repository) + "/";
        final String p = base.relativize(repository.root()).toString().replace('\\', '/');
        return p.isEmpty() ? "" : p + "/";
    }

    /** "git log: j", or "git log: 3 repositories", and a path's. */
    String title(LogQuery query) {
        final String what = isSeveral() ? repositories.size() + " repositories" : name(first());
        return first().backend().name() + " log: " + what + (query.path() != null ? " " + query.path() : "");
    }

    /**
     * What repository reads of query, or null when it is left out: by the
     * repository filter, or a path in another. The path is made its own;
     * a path that is its root is none.
     */
    LogQuery queryFor(VcsRepository repository, LogQuery query) {
        if (query.repository() != null && !query.repository().equals(name(repository)))
            return null;
        if (query.path() == null || !isSeveral())
            return query.withRepository(null);
        if (holder(query.path()) != repository)
            return null;
        final String own = own(repository, query.path());
        return query.withRepository(null).withPath(own.isEmpty() ? null : own, query.follow());
    }

    /** The file or directory a path in a query names, or null if no repository holds it. */
    Path resolve(String path) {
        if (!isSeveral())
            return first().root().resolve(path);
        final VcsRepository r = holder(path);
        return r == null ? null : r.root().resolve(own(r, path));
    }

    // The repository whose prefix path is under, the longest; null for none.
    private VcsRepository holder(String path) {
        VcsRepository best = null;
        for (VcsRepository r : repositories) {
            final String prefix = prefix(r);
            if ((path.startsWith(prefix) || (path + "/").equals(prefix))
                    && (best == null || prefix.length() > prefix(best).length()))
                best = r;
        }
        return best;
    }

    // path, under repository's prefix, from its root.
    private String own(VcsRepository repository, String path) {
        return path.substring(Math.min(path.length(), prefix(repository).length()));
    }

    /** Its color's index among the project's, from its place in the list. */
    int colorIndex(VcsRepository repository) {
        return Repositories.colorIndex(repository, repositories);
    }

    /**
     * The key the log popup of a status buffer keeps its arguments under:
     * one per repository, and one per project of several, apart from its
     * root's repository's own.
     */
    String key() {
        if (!isSeveral())
            return first().root().toString();
        final StringBuilder sb = new StringBuilder("several:");
        for (VcsRepository r : repositories)
            sb.append(r.root()).append(';');
        return sb.toString();
    }
}
