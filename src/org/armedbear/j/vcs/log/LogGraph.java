/*
 * LogGraph.java
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The log's graph: a lane per line of history, assigned to the commits in
 * the order they are listed, a page at a time. A commit takes the lane of
 * the first child that named it as first parent, or a new one at the
 * right; its other parents join lanes that wait for them or start new
 * ones. A lane ends at the commit it waits for, and the lanes to its right
 * move left. Each row records only what is drawn on it, so painting a row
 * needs nothing else.
 *
 * A lane is named for the branch it is: the ref at its first commit, or
 * the branch its merge's message names, or else its first commit.
 */
final class LogGraph {
    /** A line of lane's from its place at one edge of a half row, from, to its place at the other, to. */
    record Edge(int from, int to, Lane lane) {
        int color() {
            return lane.color;
        }
    }

    /**
     * What a commit's row draws.
     *
     * @param column the place of the lane the commit is in
     * @param lane that lane
     * @param merge whether it has more than one parent
     * @param top lines from the row's top to its middle: each lane in, to where it goes
     * @param bottom lines from the middle to the bottom: each lane out, from where it was
     * @param width how many lanes the row spans
     */
    record Row(int column, Lane lane, boolean merge, List<Edge> top, List<Edge> bottom, int width) {
        Row {
            top = List.copyOf(top);
            bottom = List.copyOf(bottom);
        }

        int color() {
            return lane.color;
        }
    }

    /** A line of history going down the list, waiting for a commit. */
    static final class Lane {
        private String expects;
        private final int color;
        // Its branch's, once known; until its first commit, what a merge says.
        private volatile String name;
        private volatile String first;
        private boolean started;

        private Lane(String expects, int color, String name) {
            this.expects = expects;
            this.color = color;
            this.name = name;
        }

        /** Its branch's name, or its first commit's id and subject. */
        String name() {
            final String n = name;
            return n != null ? n : first != null ? first : "";
        }

        // Its commit c: the first names it, by a branch at it if any.
        private void reached(LogCommit c) {
            if (started)
                return;
            started = true;
            final String ref = refName(c);
            if (ref != null)
                name = ref;
            first = c.id().substring(0, Math.min(8, c.id().length())) + " " + c.subject();
        }
    }

    // How many colors lanes take turns with.
    static final int COLORS = 8;

    // The lanes between the last row and the next, left to right.
    private List<Lane> lanes = new ArrayList<>();

    /** The rows of commits, listed after those added before. */
    List<Row> add(List<LogCommit> commits) {
        final List<Row> rows = new ArrayList<>(commits.size());
        for (LogCommit c : commits)
            rows.add(add(c));
        return rows;
    }

    private Row add(LogCommit c) {
        final List<Lane> in = lanes;
        // Its lane: the first waiting for it, or a new one at the right.
        int column = -1;
        for (int i = 0; i < in.size() && column < 0; i++)
            if (c.id().equals(in.get(i).expects))
                column = i;
        final Lane own = column >= 0 ? in.get(column) : new Lane(c.id(), color(c.id(), in), null);
        if (column < 0)
            column = in.size();
        own.reached(c);

        // The lanes out: those passing, then its own going on to its first
        // parent, and a lane for each other parent no lane waits for.
        final List<Lane> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            final Lane l = in.get(i);
            if (i == column)
                out.add(own);
            else if (!c.id().equals(l.expects))
                out.add(l);
        }
        if (column == in.size())
            out.add(own);
        final List<String> parents = c.graphParents();
        final List<String> merged = parents.size() > 1 ? mergedBranches(c.subject()) : List.of();
        final List<Lane> parentLanes = new ArrayList<>();
        if (parents.isEmpty())
            out.remove(own);
        else {
            own.expects = parents.get(0);
            parentLanes.add(own);
        }
        for (int p = 1; p < parents.size(); p++) {
            Lane waiting = null;
            for (Lane l : out)
                if (parents.get(p).equals(l.expects))
                    waiting = l;
            if (waiting == null) {
                waiting = new Lane(parents.get(p),
                        color(parents.get(p), out),
                        p - 1 < merged.size() ? merged.get(p - 1) : null);
                out.add(waiting);
            }
            parentLanes.add(waiting);
        }

        // Top half: each lane in to the commit if it waited for it, else to
        // its own place; the middle keeps the lanes in their places.
        final List<Edge> top = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            final Lane l = in.get(i);
            top.add(new Edge(i, c.id().equals(l == own ? c.id() : l.expects) ? column : i, l));
        }
        // Bottom half: from the commit to each parent's lane, and each lane
        // passing from its place in the middle to its place below.
        final List<Edge> bottom = new ArrayList<>();
        for (Lane l : parentLanes)
            bottom.add(new Edge(column, out.indexOf(l), l));
        for (int i = 0; i < out.size(); i++) {
            final Lane l = out.get(i);
            if (l == own || parentLanes.contains(l) && in.indexOf(l) < 0)
                continue;
            bottom.add(new Edge(in.indexOf(l), i, l));
        }
        lanes = out;
        final int width = Math.max(Math.max(in.size(), out.size()), column + 1);
        return new Row(column, own, c.isMerge(), top, bottom, width);
    }

    // The branch a commit is the tip of: the one checked out, a local one,
    // a remote one, then a tag.
    private static String refName(LogCommit c) {
        LogRef best = null;
        for (LogRef r : c.refs())
            if (r.kind() != LogRef.Kind.HEAD && (best == null || rank(r) < rank(best)))
                best = r;
        return best == null ? null : best.name();
    }

    private static int rank(LogRef r) {
        return r.head() ? 0 : r.kind().ordinal();
    }

    private static final Pattern MERGE = Pattern.compile("Merge (?:remote-tracking )?branch(?:es)? (.+?)(?: into .*)?");
    private static final Pattern PULL = Pattern.compile("Merge pull request #\\d+ from [^/\\s]+/(\\S+).*");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    /** The branches a merge's message names, in its parents' order after the first. */
    static List<String> mergedBranches(String subject) {
        final Matcher pull = PULL.matcher(subject);
        if (pull.matches())
            return List.of(pull.group(1));
        final Matcher m = MERGE.matcher(subject);
        if (!m.matches())
            return List.of();
        final List<String> names = new ArrayList<>();
        final Matcher q = QUOTED.matcher(m.group(1));
        while (q.find())
            names.add(q.group(1));
        return names;
    }

    // A new lane's color: from the commit it starts at, or the next none of
    // the lanes beside it has. From the lanes alone, so however many pages
    // are read before it, a lane's color is the same.
    private static int color(String id, List<Lane> beside) {
        final int start = Math.floorMod(id.hashCode(), COLORS);
        for (int i = 0; i < COLORS; i++) {
            final int color = (start + i) % COLORS;
            boolean taken = false;
            for (Lane l : beside)
                taken |= l.color == color;
            if (!taken)
                return color;
        }
        return start;
    }
}
