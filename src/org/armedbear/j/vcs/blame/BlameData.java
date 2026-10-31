/*
 * BlameData.java
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

package org.armedbear.j.vcs.blame;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.Line;

/**
 * A buffer's blame, by its lines as they were when it was read: an edit
 * made since moves a line's commit with it, and a line changed or added is
 * not committed. Filled in as blame streams; safe to read meanwhile.
 */
public final class BlameData implements BlameCommit.Sink {
    private final List<Line> lines;
    // Each line's text as read, by identity: a line not among them was added since.
    private final Map<Line, String> asRead = new IdentityHashMap<>();
    // The texts the file had, which an undo or a reload puts back in new lines.
    private final Set<String> texts = new HashSet<>();
    private final Map<Line, BlameCommit> commits = new IdentityHashMap<>();
    private final Map<Line, Integer> sources = new IdentityHashMap<>();
    private volatile boolean done;
    private volatile String error;
    // The file has no history: none of it is in HEAD, as one never committed.
    private volatile boolean noHistory;

    /** For lines, the buffer's when it was read. */
    BlameData(List<Line> lines) {
        this.lines = List.copyOf(lines);
        for (Line l : lines) {
            asRead.put(l, l.getText());
            texts.add(l.getText());
        }
    }

    @Override
    public synchronized void lines(int first, int count, BlameCommit commit, int source) {
        final long end = Math.min((long) first + count, lines.size());
        for (int i = first; i < end; i++) {
            commits.put(lines.get(i), commit);
            sources.put(lines.get(i), source + (i - first));
        }
    }

    @Override
    public void noHistory() {
        noHistory = true;
    }

    /** Whether the file has no history to blame: none of it is in HEAD. */
    public boolean hasNoHistory() {
        return noHistory;
    }

    /**
     * The commit line was last changed in, NOT_COMMITTED if changed or
     * added since; null if not read yet, or added since with text the file
     * had, as an undo or a reload puts back. A paint's worth of lines
     * compares their text.
     */
    public synchronized BlameCommit commit(Line line) {
        final BlameCommit c = commits.get(line);
        if (c != null)
            return line.getText().equals(asRead.get(line)) ? c : NOT_COMMITTED;
        if (asRead.containsKey(line))
            return null;
        return texts.contains(line.getText()) ? null : NOT_COMMITTED;
    }

    /** A line's commit when it has changed since it was blamed. */
    public static final BlameCommit NOT_COMMITTED = BlameCommit.uncommitted(null);

    /**
     * The line's number in its commit's own version of the file, counted
     * from 0; -1 when the line is unknown or has changed.
     */
    public synchronized int sourceLine(Line line) {
        final BlameCommit c = commit(line);
        return c == null || c.isUncommitted() ? -1 : sources.getOrDefault(line, -1);
    }

    /** The commits read so far, each once, in the order first read. */
    public synchronized Set<BlameCommit> commits() {
        return new LinkedHashSet<>(commits.values());
    }

    /** Whether blame has finished, or failed. */
    public boolean isDone() {
        return done;
    }

    /** Why blame failed, or null. */
    public String error() {
        return error;
    }

    void finished(String error) {
        this.error = error;
        done = true;
    }
}
