/*
 * LogGraphTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class LogGraphTest {
    // A commit with id and parents, listed newest first.
    private static LogCommit c(String id, String... parents) {
        return new LogCommit(id, List.of(parents), "a", "a@x", 0, List.of(), id);
    }

    // "column: top edges | bottom edges", each "from>to".
    private static List<String> shapes(List<LogCommit> commits) {
        final List<String> out = new ArrayList<>();
        for (LogGraph.Row r : new LogGraph().add(commits))
            out.add(r.column() + ": " + edges(r.top()) + " | " + edges(r.bottom()));
        return out;
    }

    private static String edges(List<LogGraph.Edge> edges) {
        final List<String> out = new ArrayList<>();
        for (LogGraph.Edge e : edges)
            out.add(e.from() + ">" + e.to());
        return String.join(" ", out);
    }

    @Test
    public void aLineOfCommitsIsOneLane() {
        assertEquals(
            List.of("0:  | 0>0", "0: 0>0 | 0>0", "0: 0>0 | "),
            shapes(List.of(c("c3", "c2"), c("c2", "c1"), c("c1"))));
    }

    @Test
    public void aMergeForksALaneThatJoinsAtTheForkPoint() {
        final List<LogCommit> commits = List.of(c("m", "a", "b"), c("a", "r"), c("b", "r"), c("r"));
        assertEquals(
            List.of("0:  | 0>0 0>1", "0: 0>0 1>1 | 0>0 1>1", "1: 0>0 1>1 | 1>1 0>0", "0: 0>0 1>0 | "),
            shapes(commits));
        final List<LogGraph.Row> rows = new LogGraph().add(commits);
        assertTrue(rows.get(0).merge());
        assertFalse(rows.get(1).merge());
        assertEquals(2, rows.get(0).width());
    }

    @Test
    public void aSecondHeadStartsALaneAtTheRight() {
        assertEquals(
            List.of("0:  | 0>0", "1: 0>0 | 1>1 0>0", "0: 0>0 1>0 | "),
            shapes(List.of(c("x", "r"), c("y", "r"), c("r"))));
    }

    @Test
    public void lanesRightOfOneThatEndsMoveLeft() {
        assertEquals(
            List.of("0:  | 0>0", "1: 0>0 | 1>1 0>0", "0: 0>0 1>1 | 1>0", "0: 0>0 | "),
            shapes(List.of(c("p", "q"), c("s", "t"), c("q"), c("t"))));
    }

    @Test
    public void anOctopusMergeHasALanePerParent() {
        final List<LogGraph.Row> rows = new LogGraph().add(List.of(c("o", "a", "b", "c"), c("a"), c("b"), c("c")));
        assertEquals("0>0 0>1 0>2", edges(rows.get(0).bottom()));
        assertEquals(3, rows.get(0).width());
    }

    @Test
    public void pagesAddedApartGiveTheRowsAddedTogether() {
        final List<LogCommit> commits = List.of(c("m", "a", "b"), c("a", "r"), c("b", "r"), c("r"));
        final LogGraph paged = new LogGraph();
        final List<LogGraph.Row> rows = new ArrayList<>(paged.add(commits.subList(0, 2)));
        rows.addAll(paged.add(commits.subList(2, 4)));
        assertEquals(drawn(new LogGraph().add(commits)), drawn(rows));
    }

    // Each row's shape, with its lanes' names.
    private static List<String> drawn(List<LogGraph.Row> rows) {
        final List<String> out = new ArrayList<>();
        for (LogGraph.Row r : rows) {
            final StringBuilder sb = new StringBuilder(r.column() + " " + r.lane().name() + ":");
            for (LogGraph.Edge e : r.top())
                sb.append(' ').append(e.from()).append('>').append(e.to()).append(' ').append(e.lane().name());
            sb.append(" |");
            for (LogGraph.Edge e : r.bottom())
                sb.append(' ').append(e.from()).append('>').append(e.to()).append(' ').append(e.lane().name());
            out.add(sb.toString());
        }
        return out;
    }

    private static LogCommit c(String id, String subject, List<LogRef> refs, String... parents) {
        return new LogCommit(id, List.of(parents), "a", "a@x", 0, refs, subject);
    }

    private static LogRef branch(String name, boolean head) {
        return new LogRef(name, LogRef.Kind.BRANCH, head);
    }

    @Test
    public void aLaneIsNamedForItsBranchOrWhatItsMergeSaysOrItsFirstCommit() {
        final List<LogGraph.Row> rows = new LogGraph().add(
            List.of(
                c("m", "Merge branch 'feature'", List.of(branch("main", true)), "a", "b"),
                c("x", "Merge pull request #7 from someone/fix-it", List.of(), "a", "f"),
                c("a", "on main", List.of(), "r"),
                c("b", "on feature", List.of(), "r"),
                c("f", "the fix", List.of(), "r"),
                c("t", "a tip", List.of(new LogRef("v1", LogRef.Kind.TAG, false), branch("topic", false)), "r"),
                c("u", "unnamed", List.of(), "r"),
                c("r", "root", List.of())));
        assertEquals("main", rows.get(0).lane().name());
        assertEquals(
            "x Merge pull request #7 from someone/fix-it",
            rows.get(1).lane().name(),
            "no ref: its first commit");
        assertEquals("main", rows.get(2).lane().name());
        assertEquals("feature", rows.get(3).lane().name(), "the merge's message");
        assertEquals("fix-it", rows.get(4).lane().name(), "the pull request's branch");
        assertEquals("topic", rows.get(5).lane().name(), "a branch before a tag");
        assertEquals("u unnamed", rows.get(6).lane().name(), "its first commit");
        assertEquals("main", rows.get(7).lane().name());
    }

    @Test
    public void aBranchAtALanesFirstCommitNamesItOverItsMerge() {
        final List<LogGraph.Row> rows = new LogGraph().add(
            List.of(
                c("m", "Merge branch 'old-name'", List.of(), "a", "b"),
                c("b", "b", List.of(branch("new-name", false)), "a"),
                c("a", "a", List.of())));
        assertEquals("new-name", rows.get(0).bottom().get(1).lane().name(), "the edge above it too");
    }

    @Test
    public void aNewLaneTakesAColorTheLanesBesideItHaveNot() {
        // Two heads whose ids give the same color.
        final int want = Math.floorMod("a".hashCode(), LogGraph.COLORS);
        String b = null;
        for (int i = 0; b == null; i++)
            if (Math.floorMod(("b" + i).hashCode(), LogGraph.COLORS) == want)
                b = "b" + i;
        final List<LogGraph.Row> rows = new LogGraph().add(List.of(c("a", "r"), c(b, "r"), c("r")));
        assertTrue(rows.get(0).color() != rows.get(1).color());
    }

    @Test
    public void readsTheBranchesAMergesMessageNames() {
        assertEquals(List.of("x"), LogGraph.mergedBranches("Merge branch 'x'"));
        assertEquals(List.of("x"), LogGraph.mergedBranches("Merge branch 'x' into main"));
        assertEquals(List.of("x"), LogGraph.mergedBranches("Merge branch 'x' of github.com:a/b"));
        assertEquals(List.of("origin/x"), LogGraph.mergedBranches("Merge remote-tracking branch 'origin/x'"));
        assertEquals(List.of("a", "b", "c"), LogGraph.mergedBranches("Merge branches 'a', 'b' and 'c'"));
        assertEquals(List.of("a/b"), LogGraph.mergedBranches("Merge pull request #12 from user/a/b"));
        assertEquals(List.of(), LogGraph.mergedBranches("Merged things"));
    }

    // Random histories, children before parents: each row's lanes out are
    // the next row's lanes in, every lane drawn once, and nothing outside
    // the row's width.
    @Test
    public void lanesRunUnbrokenFromRowToRow() {
        final Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            final int n = 1 + random.nextInt(60);
            final List<LogCommit> commits = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int count = i == n - 1 ? 0
                        : random.nextInt(10) == 0 ? 0
                        : 1 + (random.nextInt(4) == 0 ? 1 + random.nextInt(2) : 0);
                final Set<String> parents = new java.util.LinkedHashSet<>();
                for (int p = 0; p < count; p++)
                    parents.add("c" + (i + 1 + random.nextInt(Math.min(5, n - 1 - i))));
                commits.add(c("c" + i, parents.toArray(new String[0])));
            }
            final List<LogGraph.Row> rows = new LogGraph().add(commits);
            int lanesIn = 0;
            for (int r = 0; r < rows.size(); r++) {
                final LogGraph.Row row = rows.get(r);
                final Set<Integer> from = new HashSet<>();
                for (LogGraph.Edge e : row.top()) {
                    assertTrue(from.add(e.from()), "round " + round + " row " + r + ": a lane in twice");
                    assertTrue(e.to() < row.width());
                }
                assertEquals(lanesIn, from.size(), "round " + round + " row " + r + ": every lane in");
                final Set<Integer> to = new HashSet<>();
                for (LogGraph.Edge e : row.bottom()) {
                    to.add(e.to());
                    assertTrue(e.from() < row.width() && e.to() < row.width());
                }
                for (int i = 0; i < to.size(); i++)
                    assertTrue(to.contains(i), "round " + round + " row " + r + ": lanes out without a gap");
                lanesIn = to.size();
            }
        }
    }
}
