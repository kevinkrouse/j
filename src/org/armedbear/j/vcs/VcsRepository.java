/*
 * VcsRepository.java
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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;

/**
 * A repository's root, its backend, and its latest {@link Snapshot}.
 * {@link #invalidate} marks the snapshot stale; the next
 * {@link #snapshotNow} or {@link #refresh} reads it again.
 */
public final class VcsRepository {
    private final Path root;
    private final VcsBackend backend;
    private final List<Consumer<VcsRepository>> listeners = new CopyOnWriteArrayList<>();
    private final Object reading = new Object();

    private volatile Snapshot snapshot;
    // Bumped by invalidate; a read that started before a bump is stale when done.
    private final AtomicLong generation = new AtomicLong(1);
    // Bumped by invalidate, not invalidateFiles.
    private final AtomicLong refsGeneration = new AtomicLong(1);
    private volatile long readGeneration;
    private volatile StashConflict stashConflict;
    // The generation when it was noted: a read begun before can't tell it is over.
    private volatile long stashConflictAt;

    /** A stash j applied or popped that conflicted. */
    public record StashConflict(String name, String hash, boolean pop) {}

    private boolean refreshing;
    private boolean refreshAgain;

    VcsRepository(Path root, VcsBackend backend) {
        this.root = root;
        this.backend = backend;
    }

    /** Its directory's name, as a status or log of several shows it. */
    public String name() {
        return root().getFileName() != null ? root().getFileName().toString() : root().toString();
    }

    public Path root() {
        return root;
    }

    public VcsBackend backend() {
        return backend;
    }

    /** file's path from the root, separated by '/'; its whole path when outside. */
    public String relativePath(File file) {
        final Path path = Path.of(file.canonicalPath());
        if (!path.startsWith(root))
            return path.toString();
        return root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }

    /** The latest snapshot, stale or not; null if none has been read. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** The stash whose apply or pop left this repository's conflicts; null if none. */
    public StashConflict stashConflict() {
        return stashConflict;
    }

    /** Notes that applying or popping a stash conflicted, until no conflict is left. */
    public void stashConflicted(StashConflict conflict) {
        stashConflictAt = generation.get();
        stashConflict = conflict;
    }

    /**
     * Whether something conflicts come from is going on, as the latest
     * snapshot has it: the system's merge or rebase, say, or a stash's
     * apply or pop that conflicted.
     */
    public boolean inProgress() {
        final Snapshot s = snapshot;
        return stashConflict != null || s != null && s.inProgress();
    }

    public boolean isStale() {
        return snapshot == null || readGeneration != generation.get();
    }

    /** A fresh snapshot, read on this thread if the last one is stale. */
    public Snapshot snapshotNow() {
        synchronized (reading) {
            if (isStale())
                read();
            return snapshot;
        }
    }

    /**
     * Marks the snapshot stale, for a change made outside a read, and what
     * a backend keeps of HEAD, the refs and the config.
     */
    public void invalidate() {
        refsGeneration.incrementAndGet();
        generation.incrementAndGet();
    }

    /** Marks the snapshot stale for a change to files alone, as a save: HEAD, the refs and the config are kept. */
    public void invalidateFiles() {
        generation.incrementAndGet();
    }

    /** The same while HEAD, the refs and the config are, so far as j knows: what a backend keeps of them is good. */
    public long refsGeneration() {
        return refsGeneration.get();
    }

    /** Reads the snapshot again in the background, then tells the listeners. */
    public void refresh() {
        synchronized (this) {
            if (refreshing) {
                refreshAgain = true;
                return;
            }
            refreshing = true;
        }
        CompletableFuture.runAsync(this::refreshLoop, r -> Background.start("vcs refresh", r));
    }

    private void refreshLoop() {
        try {
            while (true) {
                synchronized (reading) {
                    read();
                }
                for (Consumer<VcsRepository> listener : listeners) {
                    try {
                        listener.accept(this);
                    }
                    catch (Throwable e) {
                        Log.error(e);
                    }
                }
                // Checked and cleared under one lock, so a refresh() asked
                // for meanwhile is never dropped.
                synchronized (this) {
                    if (!refreshAgain) {
                        refreshing = false;
                        return;
                    }
                    refreshAgain = false;
                }
            }
        }
        catch (Throwable e) {
            synchronized (this) {
                refreshing = false;
                refreshAgain = false;
            }
            throw e;
        }
    }

    private void read() {
        final long started = generation.get();
        Snapshot s;
        try {
            s = backend.readSnapshot(this);
        }
        catch (RuntimeException e) {
            Log.error(e);
            s = Snapshot.failed(e.toString());
        }
        snapshot = s != null ? s : Snapshot.failed(backend.name() + " has no repository model");
        readGeneration = started;
        // The note ends with the conflicts, but only a read begun after it
        // can tell: one begun before git wrote the conflicts would drop it
        // too early.
        if (stashConflict != null
                && started > stashConflictAt
                && s != null
                && s.error() == null
                && s.changes().stream().noneMatch(FileChange::isConflicted))
            stashConflict = null;
    }

    /**
     * Called after each refresh, on the thread that read it; a listener that
     * touches Swing goes to the event thread itself.
     */
    public void addListener(Consumer<VcsRepository> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<VcsRepository> listener) {
        listeners.remove(listener);
    }

    boolean hasListeners() {
        return !listeners.isEmpty();
    }

    @Override
    public String toString() {
        return backend.name() + " " + root;
    }
}
