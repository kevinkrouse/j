/*
 * ListingStyleTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.dir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

public class ListingStyleTest {
    private static long millis(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    public void humanSizesAreAsLsWritesThem() {
        assertEquals("0", ListingStyle.humanSize(0));
        assertEquals("1023", ListingStyle.humanSize(1023));
        assertEquals("1.0K", ListingStyle.humanSize(1024));
        assertEquals("1.1K", ListingStyle.humanSize(1025), "rounded up");
        assertEquals("1.5K", ListingStyle.humanSize(1536));
        assertEquals("10K", ListingStyle.humanSize(10 * 1024));
        assertEquals("1.0M", ListingStyle.humanSize(1024 * 1024));
        assertEquals("1.0M", ListingStyle.humanSize(1024 * 1024 - 1), "1024K is 1.0M");
    }

    @Test
    public void sizesInAnyStyleReadBack() {
        assertEquals(4096, ListingStyle.parseSize("4.0K"));
        assertEquals(123, ListingStyle.parseSize("123B"), "BSD's bytes");
        assertEquals(12, ListingStyle.parseSize("12"));
        assertEquals(-1, ListingStyle.parseSize("x"));
    }

    @Test
    public void eachTimeStyleWritesRecentAndOldDatesAsLsDoes() {
        final long now = millis(2026, 10, 16, 12, 0);
        final long recent = millis(2026, 10, 6, 9, 2);
        final long old = millis(2025, 1, 6, 9, 2);
        assertEquals("10-06 09:02", new ListingStyle(false, "iso").date(recent, now));
        assertEquals("2025-01-06 ", new ListingStyle(false, "iso").date(old, now));
        assertEquals("2026-10-06 09:02", new ListingStyle(false, "long-iso").date(recent, now));
        assertEquals("2025-01-06 09:02", new ListingStyle(false, "long-iso").date(old, now));
        final String locale = new ListingStyle(false, "locale").date(recent, now);
        assertTrue(locale.endsWith(" 6 09:02"), locale);
        assertTrue(new ListingStyle(false, "locale").date(old, now).endsWith(" 6  2025"));
        assertEquals("locale", new ListingStyle(false, "bogus").timeStyle());
    }

    @Test
    public void theListingsPatternFindsTheNameAfterEveryStyle() {
        for (String line : new String[] {
            "-rw-r--r-- 1 u g 4.0K 2026-10-16 09:02 a b.txt",
            "-rw-r--r-- 1 u g 123B Oct  6 09:02 a b.txt",
            "-rw-r--r-- 1 u g 1234 10-06 09:02 a b.txt",
            "-rw-r--r-- 1 u g 1234 2025-01-06  a b.txt",
            "-rw-r--r-- 1 u g   12M Jan  6  2025 a b.txt",
            "  -rw-r--r--       12 Oct  6 09:02 a b.txt",
            "  -rw-r--r--  1.5K 2026-10-06 09:02 a b.txt", })
            assertEquals("a b.txt", DirectoryEntry.getName(line), line);
    }

    @Test
    public void lsDatesInEveryStyleReadBack() {
        final long now = millis(2026, 10, 16, 12, 0);
        assertEquals(millis(2026, 10, 6, 9, 2), ListingStyle.parseDate("2026-10-06 09:02", now));
        assertEquals(millis(2025, 1, 6, 0, 0), ListingStyle.parseDate("2025-01-06", now));
        assertEquals(millis(2026, 10, 6, 9, 2), ListingStyle.parseDate("10-06 09:02", now));
        assertEquals(millis(2026, 10, 6, 9, 2), ListingStyle.parseDate("Oct  6 09:02", now));
        assertEquals(millis(2025, 12, 6, 9, 2), ListingStyle.parseDate("Dec 6 09:02", now), "no year, last year's");
        assertEquals(millis(2025, 1, 6, 0, 0), ListingStyle.parseDate("Jan  6  2025", now));
        assertEquals(millis(2026, 10, 6, 9, 2), ListingStyle.parseDate("6 Oct 09:02", now), "day first, as the BSDs'");
        assertEquals(-1, ListingStyle.parseDate("whenever", now));
    }

    @Test
    public void anAgeIsRightAlignedInFourColumns() {
        final long now = millis(2026, 10, 16, 12, 0);
        assertEquals("  4h", new ListingStyle(false, "age").date(millis(2026, 10, 16, 8, 0), now));
        assertEquals(" 4mo", new ListingStyle(false, "age").date(millis(2026, 6, 6, 8, 0), now));
    }
}
