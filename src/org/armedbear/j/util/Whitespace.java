/*
 * Whitespace.java
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

package org.armedbear.j.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What whitespace a diff ignores, as git diff's options do: none, at line
 * ends (--ignore-space-at-eol), changes in its amount (-b) or all of it
 * (-w). Each but none also ignores a missing last newline, as git does.
 */
public enum Whitespace {
    NONE, TRAILING, AMOUNT, ALL;

    /** Its name as the diffIgnoreWhitespace preference and command have it. */
    public String preferenceValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The one named so, as diffIgnoreWhitespace has it, or null. */
    public static Whitespace named(String s) {
        for (Whitespace w : values())
            if (w.preferenceValue().equals(s == null ? null : s.trim().toLowerCase(Locale.ROOT)))
                return w;
        return null;
    }

    private static final Pattern RUNS = Pattern.compile("\\s+");
    private static final Pattern TRAILING_RUN = Pattern.compile("\\s+$");

    /** line as it is compared. */
    public String key(String line) {
        return switch (this) {
            case NONE -> line;
            case TRAILING -> TRAILING_RUN.matcher(line).replaceFirst("");
            case AMOUNT -> RUNS.matcher(TRAILING_RUN.matcher(line).replaceFirst("")).replaceAll(" ");
            case ALL -> RUNS.matcher(line).replaceAll("");
        };
    }
}
