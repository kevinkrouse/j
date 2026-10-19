/*
 * ListingStyle.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.dir;

import java.text.DateFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.armedbear.j.util.TimeStyle;

/**
 * How a listing writes sizes and dates, as GNU ls does: sizes in bytes or,
 * human, as 4.0K or 12M; dates in a time style, locale's "Oct 16 09:02"
 * ("Oct 16  2025" over six months old), iso's "10-16 09:02" ("2025-10-16 "),
 * long-iso's "2026-10-16 09:02", or age's how long ago, "5m" to "2y", right
 * aligned in four columns.
 */
record ListingStyle(boolean human, String timeStyle) {
    static final List<String> TIME_STYLES = List.of(TimeStyle.LOCALE, TimeStyle.ISO, TimeStyle.LONG_ISO, TimeStyle.AGE);
    // As wide as the widest age, "4mo".
    private static final int AGE_WIDTH = 4;
    static final ListingStyle DEFAULT = new ListingStyle(false, TimeStyle.LOCALE);

    ListingStyle {
        if (!TIME_STYLES.contains(timeStyle))
            timeStyle = TimeStyle.LOCALE;
    }

    String size(long bytes) {
        return human ? humanSize(bytes) : String.valueOf(bytes);
    }

    /** As ls -h: under 1024 as is, else the unit's value rounded up, to a tenth under 10. */
    static String humanSize(long bytes) {
        if (bytes < 1024)
            return String.valueOf(bytes);
        final String units = "KMGTPE";
        double v = bytes;
        int unit = -1;
        while (v >= 1024 && unit < units.length() - 1) {
            v /= 1024;
            unit++;
        }
        if (v < 10) {
            final double tenths = Math.ceil(v * 10) / 10;
            if (tenths < 10)
                return String.format(Locale.ROOT, "%.1f%c", tenths, units.charAt(unit));
            v = tenths;
        }
        final long whole = (long) Math.ceil(v);
        if (whole >= 1024 && unit < units.length() - 1)
            return "1.0" + units.charAt(unit + 1);
        return whole + String.valueOf(units.charAt(unit));
    }

    /** A size in any style back in bytes, as near as it says; -1 if it isn't one. */
    static long parseSize(String s) {
        if (s.isEmpty())
            return -1;
        final char last = s.charAt(s.length() - 1);
        final int unit = "KMGTPE".indexOf(Character.toUpperCase(last));
        final String number = unit >= 0 || last == 'B' ? s.substring(0, s.length() - 1) : s;
        try {
            if (unit < 0)
                return Long.parseLong(number);
            return (long) (Double.parseDouble(number.replace(',', '.')) * Math.pow(1024, unit + 1));
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    String date(long millis, long now) {
        final String date = TimeStyle.format(timeStyle, millis, now);
        return timeStyle.equals(TimeStyle.AGE) ? padAge(date) : date;
    }

    static String padAge(String age) {
        return " ".repeat(Math.max(0, AGE_WIDTH - age.length())) + age;
    }

    /**
     * A date as ls writes it, in any of its time styles or the BSDs' day
     * first, in milliseconds; -1 if it isn't one. A date with no year is in
     * the last year, as ls writes those; one with no time is at midnight.
     */
    static long parseDate(String text, long now) {
        final String[] t = text.strip().split("\\s+");
        final ZoneId zone = ZoneId.systemDefault();
        final LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        try {
            if (t.length == 2 && t[0].length() == 10 && t[0].charAt(4) == '-')
                return at(LocalDate.parse(t[0]), t[1], zone);
            if (t.length == 1 && t[0].length() == 10 && t[0].charAt(4) == '-')
                return at(LocalDate.parse(t[0]), null, zone);
            if (t.length == 2 && t[0].length() == 5 && t[0].charAt(2) == '-')
                return recent(
                    today,
                    Integer.parseInt(t[0].substring(0, 2)),
                    Integer.parseInt(t[0].substring(3)),
                    t[1],
                    zone,
                    now);
            if (t.length == 3) {
                int month = month(t[0]);
                String day = t[1];
                if (month < 0) {
                    month = month(t[1]);
                    day = t[0];
                }
                if (month < 0)
                    return -1;
                final int d = Integer.parseInt(day.endsWith(".") ? day.substring(0, day.length() - 1) : day);
                if (t[2].indexOf(':') > 0)
                    return recent(today, month, d, t[2], zone, now);
                return at(LocalDate.of(Integer.parseInt(t[2]), month, d), null, zone);
            }
        }
        catch (RuntimeException e) {
            return -1;
        }
        return -1;
    }

    private static long at(LocalDate day, String time, ZoneId zone) {
        final LocalTime t = time == null ? LocalTime.MIDNIGHT : LocalTime.parse(time.length() == 4 ? "0" + time : time);
        return day.atTime(t).atZone(zone).toInstant().toEpochMilli();
    }

    // This year's, or last year's if that would be after now.
    private static long recent(LocalDate today, int month, int day, String time, ZoneId zone, long now) {
        final long millis = at(LocalDate.of(today.getYear(), month, day), time, zone);
        return millis > now + 24L * 60 * 60 * 1000
                ? at(LocalDate.of(today.getYear() - 1, month, day), time, zone)
                : millis;
    }

    // 1 to 12 for a month's name in English or the locale's, as "Oct" or "févr."; -1 if it isn't one.
    private static int month(String s) {
        final String m = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        if (m.length() < 3 || !Character.isLetter(m.charAt(0)))
            return -1;
        for (Locale locale : new Locale[] { Locale.ENGLISH, Locale.getDefault() }) {
            final String[] months = DateFormatSymbols.getInstance(locale).getShortMonths();
            for (int i = 0; i < 12; i++) {
                final String name =
                        months[i].endsWith(".") ? months[i].substring(0, months[i].length() - 1) : months[i];
                if (!name.isEmpty() && name.equalsIgnoreCase(m))
                    return i + 1;
            }
        }
        return -1;
    }
}
