/*
 * TimeStyleTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How long ago, in words: the spelled-out form beside the short {@link TimeStyle#age}. */
public class TimeStyleTest {
    private static final long NOW = 1_760_000_000L;

    private static String ago(long secondsBefore) {
        return TimeStyle.ago(NOW - secondsBefore, NOW);
    }

    @Test
    public void theUnitsAndTheirBoundaries() {
        assertEquals("just now", ago(0));
        assertEquals("just now", ago(59));
        assertEquals("1 minute ago", ago(60));
        assertEquals("59 minutes ago", ago(3600 - 1));
        assertEquals("1 hour ago", ago(3600));
        assertEquals("23 hours ago", ago(86400 - 1));
        assertEquals("1 day ago", ago(86400));
        assertEquals("13 days ago", ago(86400 * 14 - 1));
        // The thresholds age's: a fortnight reads 2 weeks, never "1 week",
        // and 60 days read 2 months, never "1 month"; rounded down.
        assertEquals("2 weeks ago", ago(86400 * 14));
        assertEquals("8 weeks ago", ago(86400 * 60 - 1));
        assertEquals("2 months ago", ago(86400 * 60));
        assertEquals("12 months ago", ago(86400 * 365 - 1));
        assertEquals("1 year ago", ago(86400 * 365));
        assertEquals("2 years ago", ago(86400 * 365 * 2));
    }

    @Test
    public void theFutureIsJustNow() {
        // A time ahead of now is clamped, never a negative count.
        assertEquals("just now", TimeStyle.ago(NOW + 1000, NOW));
    }

    @Test
    public void oneIsSingularTheRestPlural() {
        assertEquals("1 day ago", ago(86400));
        assertEquals("2 days ago", ago(86400 * 2));
        assertEquals("1 hour ago", ago(3600));
        assertEquals("2 hours ago", ago(3600 * 2));
    }
}
