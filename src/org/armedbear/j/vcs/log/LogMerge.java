/*
 * LogMerge.java
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Several logs read a page at a time each, listed as one: the next is the
 * newest by commit time of each one's next, ties going by their order, so
 * each keeps its own order. A commit is listed only once every log with
 * more to read has one read no newer, so a page read later never goes
 * above a row listed.
 */
final class LogMerge {
    /** One log: what is read and not yet listed, and where its next page starts. */
    static final class Source {
        final Object key;
        private final Deque<LogCommit> read = new ArrayDeque<>();
        private int skip;
        private boolean more = true;
        private boolean loading;
        // The commits its first page started from, which its later pages
        // start from too, so that a commit or fetch between them shifts no
        // page; null where they can't be told.
        List<String> starts;

        Source(Object key) {
            this.key = key;
        }

        /** Where its next page starts. */
        int skip() {
            return skip;
        }
    }

    private final List<Source> sources;
    private final int pageSize;

    LogMerge(List<?> keys, int pageSize) {
        this.pageSize = pageSize;
        sources = new ArrayList<>();
        for (Object k : keys)
            sources.add(new Source(k));
    }

    /** Those to read a page of now: with more, none being read, nothing left to list. */
    List<Source> wanted() {
        final List<Source> out = new ArrayList<>();
        for (Source s : sources)
            if (s.more && !s.loading && s.read.isEmpty())
                out.add(s);
        return out;
    }

    /** source's page is being read. */
    void loading(Source source) {
        source.loading = true;
    }

    /** source's page, read: fewer than a page is its last. */
    void add(Source source, List<LogCommit> page) {
        source.loading = false;
        source.read.addAll(page);
        source.skip += page.size();
        source.more = page.size() == pageSize;
    }

    /** The commits that can be listed now, in order, taken. */
    List<LogCommit> take() {
        final List<LogCommit> out = new ArrayList<>();
        while (true) {
            Source next = null;
            for (Source s : sources) {
                if (s.read.isEmpty()) {
                    // Its next unknown: nothing can be listed before it is read.
                    if (s.more)
                        return out;
                    continue;
                }
                if (next == null || s.read.peekFirst().committed() > next.read.peekFirst().committed())
                    next = s;
            }
            if (next == null)
                return out;
            out.add(next.read.removeFirst());
        }
    }

    /** Whether any has more to list. */
    boolean hasMore() {
        for (Source s : sources)
            if (s.more || !s.read.isEmpty())
                return true;
        return false;
    }

    /** Whether a page is being read. */
    boolean isLoading() {
        for (Source s : sources)
            if (s.loading)
                return true;
        return false;
    }
}
