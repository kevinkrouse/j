/*
 * TimeStyle.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * How a date is written in a listing or a log, as GNU ls's --time-style:
 * locale's "Oct 16 09:02" ("Oct 16  2025" over six months old), iso's
 * "10-16 09:02" ("2025-10-16 "), long-iso's "2026-10-16 09:02"; or age's
 * how long ago, shortest: "now", "5m", "3h", "2d", "6w", "4mo", "2y".
 */
public final class TimeStyle {
    public static final String AGE = "age";
    public static final String LOCALE = "locale";
    public static final String ISO = "iso";
    public static final String LONG_ISO = "long-iso";

    /** Every style, age first. */
    public static final List<String> ALL = List.of(AGE, LOCALE, ISO, LONG_ISO);

    private static final DateTimeFormatter LOCALE_RECENT =
            DateTimeFormatter.ofPattern("MMM ppd HH:mm", Locale.getDefault());
    private static final DateTimeFormatter LOCALE_OLD =
            DateTimeFormatter.ofPattern("MMM ppd  yyyy", Locale.getDefault());
    private static final DateTimeFormatter ISO_RECENT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter ISO_OLD = DateTimeFormatter.ofPattern("yyyy-MM-dd ");
    private static final DateTimeFormatter LONG_ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // ls's "recent": in the last six months, and not in the future.
    private static final long SIX_MONTHS_MILLIS = 182L * 24 * 60 * 60 * 1000;

    private TimeStyle() {}

    /** style if it is one, else fallback. */
    public static String valid(String style, String fallback) {
        return ALL.contains(style) ? style : fallback;
    }

    /** The date millis, as style writes it now. */
    public static String format(String style, long millis, long nowMillis) {
        final ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        final boolean recent = millis <= nowMillis && nowMillis - millis < SIX_MONTHS_MILLIS;
        return switch (style) {
            case AGE -> age(millis / 1000, nowMillis / 1000);
            case ISO -> (recent ? ISO_RECENT : ISO_OLD).format(t);
            case LONG_ISO -> LONG_ISO_FORMAT.format(t);
            default -> (recent ? LOCALE_RECENT : LOCALE_OLD).format(t);
        };
    }

    /** How long ago, in seconds, shortest: "now", "5m", "3h", "2d", "6w", "4mo", "2y". */
    public static String age(long time, long now) {
        final long s = Math.max(0, now - time);
        if (s < 60)
            return "now";
        if (s < 3600)
            return s / 60 + "m";
        if (s < 86400)
            return s / 3600 + "h";
        if (s < 86400 * 14)
            return s / 86400 + "d";
        if (s < 86400 * 60)
            return s / (86400 * 7) + "w";
        if (s < 86400 * 365)
            return s / (86400 * 30) + "mo";
        return s / (86400 * 365) + "y";
    }
}
