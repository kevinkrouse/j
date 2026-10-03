/*
 * AboutDialogTest.java
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

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The About dialog reports these. An editor that has been up for days is
 * exactly the case nobody sees while developing.
 */
public class AboutDialogTest {
    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Test
    public void saysNothingForTheFirstMinute() {
        assertEquals("", AboutDialog.formatUptime(0));
        assertEquals("", AboutDialog.formatUptime(59 * 1000));
    }

    @Test
    public void countsMinutesUnderAnHour() {
        assertEquals("up 1 minute", AboutDialog.formatUptime(MINUTE));
        assertEquals("up 2 minutes", AboutDialog.formatUptime(2 * MINUTE));
        assertEquals("up 59 minutes", AboutDialog.formatUptime(59 * MINUTE));
    }

    /** From an hour on it is a clock reading, with a padded minute. */
    @Test
    public void readsAsAClockFromAnHourOn() {
        assertEquals("up 1:00", AboutDialog.formatUptime(HOUR));
        assertEquals("up 1:05", AboutDialog.formatUptime(HOUR + 5 * MINUTE));
        assertEquals("up 1:30", AboutDialog.formatUptime(HOUR + 30 * MINUTE));
        assertEquals(
            "up 23:59",
            AboutDialog.formatUptime(23 * HOUR + 59 * MINUTE)
        );
    }

    @Test
    public void countsDaysSeparately() {
        assertEquals("up 1 day, 0:00", AboutDialog.formatUptime(DAY));
        assertEquals(
            "up 2 days, 3:04",
            AboutDialog.formatUptime(2 * DAY + 3 * HOUR + 4 * MINUTE)
        );
        assertEquals(
            "up 100 days, 12:30",
            AboutDialog.formatUptime(100 * DAY + 12 * HOUR + 30 * MINUTE)
        );
    }

    @Test
    public void formatsMemoryByMagnitude() {
        assertEquals("512 bytes", AboutDialog.formatMemory(512));
        assertEquals("1.0K", AboutDialog.formatMemory(1024));
        assertEquals("13.0M", AboutDialog.formatMemory(13 * 1024 * 1024));
        assertEquals("30.0G", AboutDialog.formatMemory(30L * 1024 * 1024 * 1024));
    }
}
