/*
 * BasesCache.java
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

import java.util.concurrent.CompletableFuture;
import org.armedbear.j.Log;

/**
 * A file's bases, read once and kept until what is staged or committed may
 * have changed, for the change marks and a comparison of the file.
 */
public final class BasesCache {
    private final VcsRepository repository;
    private final String path;
    private Bases bases;
    // Bumped when the bases change: a read begun before cannot keep its bases.
    private long generation;
    // The read under way and the generation it is for, which another asking
    // for that generation waits on rather than reading too.
    private CompletableFuture<Bases> reading;
    private long readingFor = -1;

    public BasesCache(VcsRepository repository, String path, Bases bases) {
        this.repository = repository;
        this.path = path;
        this.bases = bases;
    }

    public VcsRepository getRepository() {
        return repository;
    }

    /** The path in the repository, '/'-separated. */
    public String getPath() {
        return path;
    }

    /** Reads the bases again next time: what is staged or committed changed. */
    public synchronized void invalidate() {
        generation++;
        bases = null;
    }

    /**
     * The bases, read if they are not kept, once for all who ask meanwhile;
     * null if git could not say, kept not. Off the event thread.
     */
    public Bases get() {
        final long asked;
        final CompletableFuture<Bases> mine;
        final CompletableFuture<Bases> theirs;
        synchronized (this) {
            if (bases != null)
                return bases;
            asked = generation;
            theirs = reading != null && readingFor == asked ? reading : null;
            if (theirs == null) {
                reading = new CompletableFuture<>();
                readingFor = asked;
            }
            mine = theirs == null ? reading : null;
        }
        if (theirs != null)
            return theirs.join();
        Bases read = null;
        try {
            read = repository.backend().readBases(repository, path);
        }
        catch (RuntimeException e) {
            Log.error(e);
        }
        finally {
            synchronized (this) {
                if (read != null && generation == asked)
                    bases = read;
                if (reading == mine)
                    reading = null;
            }
            mine.complete(read);
        }
        return read;
    }

}
