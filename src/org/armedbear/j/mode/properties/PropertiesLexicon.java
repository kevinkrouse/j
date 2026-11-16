/*
 * PropertiesLexicon.java
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

package org.armedbear.j.mode.properties;

/**
 * The comment and continuation rules of a properties file, shared by the
 * formatter and the lexical queries so the two cannot drift. A marker at
 * the start of a line is a comment only when the line isn't a continuation
 * of the previous one; ordinary text, including literal quotes, opens
 * nothing — properties has no string syntax.
 */
final class PropertiesLexicon {
    private PropertiesLexicon() {
    }

    /**
     * Whether {@code prevText} ends with an unescaped trailing backslash —
     * an odd count; an even count is escaped backslashes, not a continuation.
     */
    static boolean continuesFrom(String prevText) {
        if (prevText == null)
            return false;
        int count = 0;
        for (int i = prevText.length() - 1; i >= 0 && prevText.charAt(i) == '\\'; i--)
            count++;
        return (count & 1) == 1;
    }

    /**
     * Whether {@code text} is a comment line: first non-whitespace
     * character is a marker (leading whitespace still counts) and the line
     * is not a continuation.
     */
    static boolean isComment(String text, boolean continuation) {
        if (continuation || text == null)
            return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c))
                continue;
            return c == '#' || c == '!' || c == ';';
        }
        return false;
    }
}
