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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 *
 * A lane longer than LONG rows is drawn a row at each end, with an arrow,
 * and takes no place between: as a page is added, a parent its rows hold
 * is known to be near or far, and a lane whose commit isn't read leaves
 * once it has passed LONG rows. Rows laid out never change.
 */
final class LogGraph {
    /** Where an edge ends in an arrow, the stub of a lane too long to draw whole. */
    enum Arrow {
        NONE,
        /** At its lower end: the lane goes on down, out of sight. */
        DOWN,
        /** At its upper end: the lane comes from up above. */
        UP
    }

    /**
     * A line of lane's from its place at one edge of a half row, from, to its
     * place at the other, to; with an arrow, jump is the key of the commit at
     * the lane's other end.
     */
    record Edge(int from, int to, Lane lane, Arrow arrow, String jump) {
        Edge(int from, int to, Lane lane) {
            this(from, to, lane, Arrow.NONE, null);
        }

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
        // The commit it comes down from, the row of the one it waits for (-1
        // until read), the row it leaves the graph at (-1 for none), and how
        // many rows it has passed.
        private String from;
        private int target = -1;
        private int leaves = -1;
        private int passed;

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

    /**
     * Rows past which a lane is a stub at each end, with an arrow, as
     * IntelliJ's: between, it takes no place, so a lane from a tag's commit
     * far above its parent doesn't widen every row it passes.
     */
    static final int LONG = 30;

    // The lanes between the last row and the next, left to right; those
    // out of sight, waiting for their commits; the next row's place in the
    // list; and the rows of the page being added, by commit, and its end.
    private List<Lane> lanes = new ArrayList<>();
    private final List<Lane> away = new ArrayList<>();
    private int row;
    private Map<String, Integer> ahead = Map.of();
    private int end;

    /** The rows of commits, listed after those added before. */
    List<Row> add(List<LogCommit> commits) {
        ahead = new HashMap<>();
        for (int i = 0; i < commits.size(); i++)
            ahead.putIfAbsent(key(commits.get(i), commits.get(i).id()), row + i);
        end = row + commits.size();
        // A commit waited for and now read: its row known.
        for (List<Lane> ls : List.of(lanes, away))
            for (Lane l : ls)
                if (l.target < 0)
                    l.target = ahead.getOrDefault(l.expects, -1);
        final List<Row> rows = new ArrayList<>(commits.size());
        for (LogCommit c : commits)
            rows.add(add(c));
        return rows;
    }

    // lane, from the commit child at row r, waits for parent: a stub if
    // parent is LONG rows on, or not read in the LONG rows to come.
    private void waits(Lane lane, String child, String parent, int r) {
        lane.expects = parent;
        lane.from = child;
        lane.passed = 0;
        lane.target = ahead.getOrDefault(parent, -1);
        final boolean far = lane.target >= 0 ? lane.target - r > LONG : end - r > LONG;
        lane.leaves = far ? r + 1 : -1;
    }

    // Whether lane, passing row r, leaves the graph there: a stub's, or one
    // that has passed LONG rows with its commit not read or far on.
    private static boolean leaves(Lane lane, int r) {
        return lane.leaves == r || lane.passed >= LONG && (lane.target < 0 || lane.target - r > LONG);
    }

    private Row add(LogCommit c) {
        final int r = row++;
        final String id = key(c, c.id());
        final List<Lane> in = lanes;
        // Those away waiting for it come back: a row before it, or, read
        // too late for that, at it.
        final List<Lane> back = new ArrayList<>();
        final List<Lane> arriving = new ArrayList<>();
        for (Iterator<Lane> it = away.iterator(); it.hasNext();) {
            final Lane l = it.next();
            if (l.expects.equals(id)) {
                arriving.add(l);
                it.remove();
            } else if (l.target == r + 1) {
                back.add(l);
                it.remove();
            }
        }
        // Its lane: the first waiting for it, or one coming back to it, or a
        // new one at the right.
        int column = -1;
        for (int i = 0; i < in.size() && column < 0; i++)
            if (id.equals(in.get(i).expects))
                column = i;
        final Lane own = column >= 0 ? in.get(column)
                : !arriving.isEmpty() ? arriving.get(0)
                : new Lane(id, color(c.id(), in), null);
        if (column < 0)
            column = in.size();
        own.reached(c);

        // The lanes out: those passing, then its own going on to its first
        // parent, and a lane for each other parent no lane waits for; those
        // leaving go away. Top half: each lane in to the commit if it
        // waited for it, else to its own place, or to an arrow there.
        final List<Lane> out = new ArrayList<>();
        final List<Edge> top = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            final Lane l = in.get(i);
            if (l == own || id.equals(l.expects)) {
                top.add(new Edge(i, column, l));
                if (l == own)
                    out.add(own);
            } else if (leaves(l, r)) {
                top.add(new Edge(i, i, l, Arrow.DOWN, l.expects));
                away.add(l);
            } else {
                top.add(new Edge(i, i, l));
                out.add(l);
                l.passed++;
            }
        }
        if (column == in.size())
            out.add(own);
        for (Lane l : arriving)
            top.add(new Edge(column, column, l, Arrow.UP, l.from));

        final List<String> parents = c.graphParents();
        final List<String> merged = parents.size() > 1 ? mergedBranches(c.subject()) : List.of();
        final List<Lane> parentLanes = new ArrayList<>();
        if (parents.isEmpty())
            out.remove(own);
        else {
            waits(own, id, key(c, parents.get(0)), r);
            parentLanes.add(own);
        }
        for (int p = 1; p < parents.size(); p++) {
            Lane waiting = null;
            final String parent = key(c, parents.get(p));
            for (Lane l : out)
                if (parent.equals(l.expects))
                    waiting = l;
            if (waiting == null) {
                waiting =
                        new Lane(parent, color(parents.get(p), out), p - 1 < merged.size() ? merged.get(p - 1) : null);
                waits(waiting, id, parent, r);
                out.add(waiting);
            }
            parentLanes.add(waiting);
        }
        out.addAll(back);

        // Bottom half: from the commit to each parent's lane, each lane
        // passing from its place in the middle to its place below, and each
        // coming back from an arrow.
        final List<Edge> bottom = new ArrayList<>();
        for (Lane l : parentLanes)
            bottom.add(new Edge(column, out.indexOf(l), l));
        for (int i = 0; i < out.size(); i++) {
            final Lane l = out.get(i);
            if (back.contains(l))
                bottom.add(new Edge(i, i, l, Arrow.UP, l.from));
            else if (l != own && !(parentLanes.contains(l) && in.indexOf(l) < 0))
                bottom.add(new Edge(in.indexOf(l), i, l));
        }
        lanes = out;
        final int width = Math.max(Math.max(in.size(), out.size()), column + 1);
        return new Row(column, own, c.isMerge(), top, bottom, width);
    }

    // A commit's id as lanes wait for it: in its repository, as two can
    // hold the same commit.
    static String key(LogCommit c, String id) {
        return c.repository() == null ? id : c.repository().root() + "\0" + id;
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
