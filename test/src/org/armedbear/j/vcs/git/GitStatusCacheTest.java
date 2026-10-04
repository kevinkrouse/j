/*
 * GitStatusCacheTest.java
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

package org.armedbear.j.vcs.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the porcelain parser, which is the part of the batched lookup that can
 * go wrong quietly: a misread record doesn't throw, it just attributes one
 * file's status to another.
 */
public class GitStatusCacheTest {
    private static Map<String, String> parse(String output) {
        Map<String, String> status = new HashMap<>();
        GitStatusCache.parse(output, status);
        return status;
    }

    @Test
    public void readsOrdinaryRecords() {
        Map<String, String> s = parse("M  build.xml\0 M src/Foo.java\0?? TODO.md\0");
        assertEquals(3, s.size());
        assertEquals("M ", s.get("build.xml"));
        assertEquals(" M", s.get("src/Foo.java"));
        assertEquals("??", s.get("TODO.md"));
    }

    /**
     * A rename carries its source path in a second record. Stepping over it is
     * what keeps the entries after it lined up with the right files.
     */
    @Test
    public void stepsOverTheSourcePathOfARename() {
        Map<String, String> s =
            parse("R  new/Name.java\0old/Name.java\0 M after.java\0");
        assertEquals("R ", s.get("new/Name.java"));
        assertEquals(" M", s.get("after.java"));
        // The source path is not a status of its own.
        assertNull(s.get("old/Name.java"));
        assertEquals(2, s.size());
    }

    @Test
    public void stepsOverTheSourcePathOfACopy() {
        Map<String, String> s = parse("C  copy.java\0origin.java\0A  added.java\0");
        assertEquals("C ", s.get("copy.java"));
        assertEquals("A ", s.get("added.java"));
        assertNull(s.get("origin.java"));
    }

    @Test
    public void keepsPathsWithSpacesIntact() {
        Map<String, String> s = parse(" M some dir/a file.txt\0");
        assertEquals(" M", s.get("some dir/a file.txt"));
    }

    @Test
    public void toleratesMissingAndMalformedInput() {
        assertEquals(0, parse(null).size());
        assertEquals(0, parse("").size());
        assertEquals(0, parse("xy\0").size()); // too short to be a record
        // A final record without its NUL terminator is still usable.
        assertEquals("M ", parse("M  trailing.java").get("trailing.java"));
    }

    @Test
    public void readsConflictAndIgnoredCodes() {
        Map<String, String> s = parse("UU both.java\0!! build/\0DD gone.java\0");
        assertEquals("UU", s.get("both.java"));
        assertEquals("!!", s.get("build/"));
        assertEquals("DD", s.get("gone.java"));
    }
}
