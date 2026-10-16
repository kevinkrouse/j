/*
 * LogNames.java
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.VcsRepository;

/**
 * What a repository's log filters offer to pick from: its branches and
 * tags, its tracked files and their directories, and authors, read in the
 * background. One per repository, so the status buffer's log popup has them
 * before any log is open.
 */
final class LogNames {
    // How many of the newest commits a read takes the authors of.
    private static final int AUTHORS_FROM = 500;

    private static final Map<VcsRepository, LogNames> all = new ConcurrentHashMap<>();

    private final VcsRepository repository;
    // How long names read stay fresh enough not to read again.
    private static final long FRESH = 30_000;

    private final AtomicBoolean reading = new AtomicBoolean();
    private volatile long readAt;
    private volatile List<LogRef> refs = List.of();
    private volatile List<String> paths = List.of();
    private final Set<String> authors = new LinkedHashSet<>();

    private LogNames(VcsRepository repository) {
        this.repository = repository;
    }

    static LogNames of(VcsRepository repository) {
        return all.computeIfAbsent(repository, LogNames::new);
    }

    /**
     * Reads them again in the background, unless a read is running or,
     * not forced, one finished in the last FRESH ms: opening the popup and
     * then its log read them once.
     */
    void read(boolean force) {
        if (!force && System.currentTimeMillis() - readAt < FRESH)
            return;
        if (!reading.compareAndSet(false, true))
            return;
        Background.start("vcs log names", () -> {
            try {
                refs = List.copyOf(repository.backend().readRefs(repository));
                paths = withDirectories(repository.backend().readPaths(repository));
                final List<LogCommit> recent = repository.backend().readLog(repository, LogQuery.ALL, 0, AUTHORS_FROM);
                if (recent != null)
                    addAuthors(recent);
            }
            catch (RuntimeException e) {
                Log.error(e);
            }
            finally {
                readAt = System.currentTimeMillis();
                reading.set(false);
            }
        });
    }

    /** Whether a read is running. */
    boolean isReading() {
        return reading.get();
    }

    /** The branches, remotes and tags; empty until read. */
    List<LogRef> refs() {
        return refs;
    }

    /** The tracked files and their directories ("src/"); empty until read. */
    List<String> paths() {
        return paths;
    }

    /** The authors seen, newest first. */
    synchronized List<String> authors() {
        return List.copyOf(authors);
    }

    /** Adds the authors of commits a log has read. */
    synchronized void addAuthors(List<LogCommit> commits) {
        for (LogCommit c : commits)
            authors.add(c.author());
    }

    /** Adds the authors of commits a log has read to their repositories'. */
    static void addAuthorsOf(List<LogCommit> commits) {
        final Map<VcsRepository, List<LogCommit>> by = new LinkedHashMap<>();
        for (LogCommit c : commits)
            if (c.repository() != null)
                by.computeIfAbsent(c.repository(), r -> new ArrayList<>()).add(c);
        by.forEach((r, cs) -> of(r).addAuthors(cs));
    }

    /** Reads each of scope's repositories' names, as read does. */
    static void read(LogScope scope, boolean force) {
        for (VcsRepository r : scope.repositories())
            of(r).read(force);
    }

    /** scope's refs: each name once, in its repositories' order. */
    static List<LogRef> refs(LogScope scope) {
        final Map<String, LogRef> out = new LinkedHashMap<>();
        for (VcsRepository r : scope.repositories())
            for (LogRef ref : of(r).refs())
                out.putIfAbsent(ref.kind() + ref.name(), ref);
        return List.copyOf(out.values());
    }

    /** scope's paths, from its base: each repository's under its prefix. */
    static List<String> paths(LogScope scope) {
        if (!scope.isSeveral())
            return of(scope.first()).paths();
        final List<String> out = new ArrayList<>();
        for (VcsRepository r : scope.repositories()) {
            final String prefix = scope.prefix(r);
            if (!prefix.isEmpty())
                out.add(prefix);
            for (String p : of(r).paths())
                out.add(prefix + p);
        }
        return out;
    }

    /** scope's authors, each once. */
    static List<String> authors(LogScope scope) {
        final Set<String> out = new LinkedHashSet<>();
        for (VcsRepository r : scope.repositories())
            out.addAll(of(r).authors());
        return List.copyOf(out);
    }

    // Each directory the files are in, before its files, as "src/".
    static List<String> withDirectories(List<String> files) {
        final Set<String> out = new LinkedHashSet<>();
        for (String f : files) {
            for (int slash = f.indexOf('/'); slash >= 0; slash = f.indexOf('/', slash + 1))
                out.add(f.substring(0, slash + 1));
            out.add(f);
        }
        return List.copyOf(out);
    }
}
