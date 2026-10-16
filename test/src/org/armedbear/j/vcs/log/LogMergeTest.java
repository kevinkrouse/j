/*
 * LogMergeTest.java
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class LogMergeTest {
    // A commit "name" committed at time.
    private static LogCommit c(String name, long time) {
        return new LogCommit(name, List.of(), "a", "a@x", time, List.of(), name, List.of(), time, null);
    }

    private static List<LogCommit> log(String prefix, long... times) {
        final List<LogCommit> out = new ArrayList<>();
        for (int i = 0; i < times.length; i++)
            out.add(c(prefix + i, times[i]));
        return out;
    }

    private static List<String> ids(List<LogCommit> commits) {
        final List<String> out = new ArrayList<>();
        for (LogCommit c : commits)
            out.add(c.id());
        return out;
    }

    // Each log read whole, at once: what the merge lists.
    private static List<String> merged(int pageSize, List<List<LogCommit>> logs) {
        final LogMerge m = new LogMerge(logs, pageSize);
        final List<LogCommit> out = new ArrayList<>();
        while (m.hasMore()) {
            for (LogMerge.Source s : m.wanted()) {
                m.loading(s);
                final List<LogCommit> all = logs.get(logs.indexOf(s.key));
                m.add(s, all.subList(s.skip(), Math.min(all.size(), s.skip() + pageSize)));
            }
            out.addAll(m.take());
        }
        return ids(out);
    }

    @Test
    public void theNewestOfEachNextComesFirst() {
        assertEquals(
            List.of("a0", "b0", "a1", "b1", "a2", "b2"),
            merged(10, List.of(log("a", 10, 8, 6), log("b", 9, 7, 5))));
    }

    @Test
    public void tiesGoByTheLogsOrder() {
        assertEquals(List.of("a0", "a1", "b0", "b1"), merged(10, List.of(log("a", 5, 5), log("b", 5, 5))));
    }

    @Test
    public void eachKeepsItsOwnOrderThoughItsTimesAreNot() {
        assertEquals(List.of("b0", "a0", "a1"), merged(10, List.of(log("a", 5, 9), log("b", 7))));
    }

    @Test
    public void anEmptyLogOrOneEndingFirstDropsOut() {
        assertEquals(List.of("a0", "a1"), merged(10, List.of(log("a", 2, 1), log("b"))));
        assertEquals(List.of("b0", "a0", "a1", "a2"), merged(2, List.of(log("a", 3, 2, 1), log("b", 4))));
    }

    @Test
    public void nothingIsListedBeforeAnotherLogsNextIsRead() {
        final List<LogCommit> a = log("a", 100, 99, 98, 97);
        final List<LogCommit> b = log("b", 5, 4);
        final LogMerge m = new LogMerge(List.of("a", "b"), 2);
        final List<LogMerge.Source> both = m.wanted();
        assertEquals(2, both.size());
        both.forEach(m::loading);
        assertTrue(m.wanted().isEmpty(), "being read");
        m.add(both.get(0), a.subList(0, 2));
        assertEquals(List.of(), m.take(), "b's first unknown");
        m.add(both.get(1), b);
        assertEquals(List.of("a0", "a1"), ids(m.take()), "then a's next unknown");
        assertEquals(List.of(both.get(0)), m.wanted(), "b's left to list, so a alone");
        m.add(both.get(0), a.subList(2, 4));
        assertEquals(List.of("a2", "a3"), ids(m.take()));
        m.add(both.get(0), List.of());
        assertEquals(List.of("b0", "b1"), ids(m.take()));
        assertTrue(m.hasMore(), "b's page was full");
        m.add(m.wanted().get(0), List.of());
        assertFalse(m.hasMore());
    }

    // Random logs, read a page at a time in random order: the same rows as
    // all read at once, each log's in its order, and a row once listed
    // never has one newer listed after it from a log that had more.
    @Test
    public void pagesReadInAnyOrderListTheSame() {
        final Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            final int pageSize = 1 + random.nextInt(4);
            final List<List<LogCommit>> logs = new ArrayList<>();
            final int n = 1 + random.nextInt(4);
            for (int l = 0; l < n; l++) {
                final long[] times = new long[random.nextInt(12)];
                long t = 1000;
                for (int i = 0; i < times.length; i++)
                    times[i] = t -= random.nextInt(5) - (random.nextInt(6) == 0 ? 8 : 0);
                logs.add(log("r" + l + "-", times));
            }
            final List<String> whole = merged(1000, logs);
            final LogMerge m = new LogMerge(logs, pageSize);
            final List<LogMerge.Source> pending = new ArrayList<>();
            final Map<LogMerge.Source, List<LogCommit>> logOf = new HashMap<>();
            final List<LogCommit> out = new ArrayList<>();
            while (m.hasMore()) {
                for (LogMerge.Source s : m.wanted()) {
                    m.loading(s);
                    pending.add(s);
                    logOf.put(s, logs.get(logs.indexOf(s.key)));
                }
                final LogMerge.Source s = pending.remove(random.nextInt(pending.size()));
                final List<LogCommit> all = logOf.get(s);
                m.add(s, all.subList(Math.min(all.size(), s.skip()), Math.min(all.size(), s.skip() + pageSize)));
                out.addAll(m.take());
            }
            assertEquals(whole, ids(out), "round " + round);
        }
    }
}
