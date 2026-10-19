/*
 * LogRowTest.java
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
import java.util.List;
import org.junit.jupiter.api.Test;

public class LogRowTest {
    private static final long NOW = 1_000_000_000L;

    private static LogCommit commit(String subject, LogRef... refs) {
        return new LogCommit("abc", List.of(), "Ada Lovelace", "ada@example.com", NOW - 7200, List.of(refs), subject);
    }

    private static final LogRef MAIN = new LogRef("main", LogRef.Kind.BRANCH, true);
    private static final LogRef ORIGIN = new LogRef("origin/main", LogRef.Kind.REMOTE, false);
    private static final LogRef TAG = new LogRef("v1.0", LogRef.Kind.TAG, false);

    private static List<String> parts(LogRow row) {
        final List<String> out = new ArrayList<>();
        for (LogRow.Span s : row.spans())
            out.add(s.part() + ":" + row.text().substring(s.start(), s.end()).strip());
        return out;
    }

    @Test
    public void putsTheAuthorAndDateAtTheRight() {
        final LogRow row = LogRow.layout(commit("Fix it", MAIN), 40, NOW);
        assertEquals(40, row.text().length());
        assertTrue(row.text().endsWith("Ada Lovelace  2h"), row.text());
        assertEquals(List.of("SUBJECT:Fix it", "HEAD:main", "AUTHOR:Ada Lovelace", "DATE:2h"), parts(row));
        assertFalse(row.cut());
    }

    @Test
    public void narrowingDropsTagsThenRemotesThenShortensTheAuthorThenTheSubject() {
        final LogCommit c = commit("Fix the parser", TAG, ORIGIN, MAIN);
        assertEquals(
            List.of(
                "SUBJECT:Fix the parser",
                "HEAD:main",
                "REMOTE:origin/main",
                "TAG:v1.0",
                "AUTHOR:Ada Lovelace",
                "DATE:2h"),
            parts(LogRow.layout(c, 80, NOW)),
            "HEAD's branch first");
        assertEquals(
            List.of("SUBJECT:Fix the parser", "HEAD:main", "REMOTE:origin/main", "AUTHOR:Ada Lovelace", "DATE:2h"),
            parts(LogRow.layout(c, 54, NOW)));
        assertEquals(
            List.of("SUBJECT:Fix the parser", "HEAD:main", "AUTHOR:Ada Lovelace", "DATE:2h"),
            parts(LogRow.layout(c, 40, NOW)));
        assertEquals(
            List.of("SUBJECT:Fix the parser", "HEAD:main", "AUTHOR:AL", "DATE:2h"),
            parts(LogRow.layout(c, 30, NOW)));
        final LogRow narrow = LogRow.layout(c, 20, NOW);
        assertEquals(List.of("SUBJECT:Fix…", "HEAD:main", "AUTHOR:AL", "DATE:2h"), parts(narrow));
        assertTrue(narrow.cut());
        assertTrue(LogRow.layout(c, 54, NOW).cut(), "a tag left out");
    }

    @Test
    public void theDateNeverGoes() {
        final LogRow row = LogRow.layout(commit("A long subject indeed", MAIN), 5, NOW);
        assertTrue(row.text().endsWith("2h"), row.text());
    }

    @Test
    public void agesAreShort() {
        assertEquals("now", LogRow.age(NOW, NOW));
        assertEquals("5m", LogRow.age(NOW - 300, NOW));
        assertEquals("3d", LogRow.age(NOW - 3 * 86400, NOW));
        assertEquals("6w", LogRow.age(NOW - 45 * 86400, NOW));
        assertEquals("4mo", LogRow.age(NOW - 125 * 86400, NOW));
        assertEquals("2y", LogRow.age(NOW - 800 * 86400L, NOW));
        assertEquals("now", LogRow.age(NOW + 50, NOW), "a clock ahead");
    }

    @Test
    public void initialsOfEachName() {
        assertEquals("AL", LogRow.initials("Ada Lovelace"));
        assertEquals("G", LogRow.initials("grace"));
        assertEquals("?", LogRow.initials(" "));
    }

    @Test
    public void anOldDateInIsoEndsAtTheRightEdgeAsARecentOneDoes() {
        final LogCommit old = new LogCommit("abc",
                List.of(),
                "Ada Lovelace",
                "ada@example.com",
                NOW - 400L * 86400,
                List.of(),
                "Old");
        final LogRow row = LogRow.layout(old, 60, NOW, "iso");
        assertEquals(60, row.text().length());
        assertTrue(row.text().matches(".*\\d{4}-\\d{2}-\\d{2}"), row.text());
        assertEquals(60, LogRow.layout(commit("New"), 60, NOW, "iso").text().length());
    }
}
