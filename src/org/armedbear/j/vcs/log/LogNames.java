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
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
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
    private volatile List<LogRefTip> tips = List.of();
    private volatile List<LogRef> refs = List.of();
    // What runs, on the event thread, when the read running is done.
    private final List<Runnable> after = new ArrayList<>();
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
        read(force, null);
    }

    /**
     * The same, then done on the event thread: when the read started, or
     * one running, is done, or at once when none needs to be.
     */
    void read(boolean force, Runnable done) {
        synchronized (after) {
            if (!reading.get() && !force && System.currentTimeMillis() - readAt < FRESH) {
                if (done != null)
                    SwingUtilities.invokeLater(done);
                return;
            }
            if (done != null)
                after.add(done);
            // Forced while one runs, which may have started before what
            // forces it: once more after it, done then.
            if (reading.get()) {
                again |= force;
                return;
            }
            reading.set(true);
        }
        Background.start("vcs log names", this::readNow);
    }

    // Whether to read again once the read running is done.
    private boolean again;

    private void readNow() {
        try {
            // One for-each-ref: the tree's tips, and the filters' refs from them.
            tips = List.copyOf(repository.backend().readRefTips(repository));
            final List<LogRef> named = new ArrayList<>();
            for (LogRefTip t : tips)
                if (t.ref().kind() != LogRef.Kind.HEAD)
                    named.add(t.ref());
            refs = List.copyOf(named);
            paths = withDirectories(repository.backend().readPaths(repository));
            final List<String> recent = repository.backend().readAuthors(repository, AUTHORS_FROM);
            synchronized (this) {
                authors.addAll(recent);
            }
        }
        catch (RuntimeException e) {
            Log.error(e);
        }
        finally {
            final boolean more;
            final List<Runnable> run;
            synchronized (after) {
                readAt = System.currentTimeMillis();
                more = again;
                again = false;
                if (more)
                    run = List.of();
                else {
                    reading.set(false);
                    run = List.copyOf(after);
                    after.clear();
                }
            }
            if (more)
                Background.start("vcs log names", this::readNow);
            for (Runnable r : run)
                SwingUtilities.invokeLater(r);
        }
    }

    /** HEAD when detached, the branches, remotes and tags, with their commits; empty until read. */
    List<LogRefTip> tips() {
        return tips;
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

    /** The same, then done on the event thread once each is read, or needed none. */
    static void read(LogScope scope, boolean force, Runnable done) {
        final List<VcsRepository> rs = scope.repositories();
        final AtomicInteger left = new AtomicInteger(rs.size());
        for (VcsRepository r : rs)
            of(r).read(force, () -> {
                if (left.decrementAndGet() == 0)
                    done.run();
            });
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
