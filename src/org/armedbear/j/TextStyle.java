/*
 * TextStyle.java
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

import java.awt.Font;
import java.util.Locale;

/**
 * The style a format is drawn in: bits that combine, so that BOLD | ITALIC is
 * bold italic. BOLD and ITALIC are Font's own, so a style a theme gave as the
 * old 0, 1 or 2 means what it always did.
 */
public final class TextStyle {
    public static final int PLAIN = Font.PLAIN;
    public static final int BOLD = Font.BOLD;
    public static final int ITALIC = Font.ITALIC;
    public static final int UNDERLINE = 4;
    public static final int STRIKETHROUGH = 8;

    private static final int ALL = BOLD | ITALIC | UNDERLINE | STRIKETHROUGH;

    private TextStyle() {}

    /** Just the bits that choose a font: BOLD and ITALIC. */
    public static int fontStyle(int style) {
        return style & (BOLD | ITALIC);
    }

    /** A style in the words parse takes: "bold italic", "plain". */
    public static String toString(int style) {
        if (style == PLAIN)
            return "plain";
        final StringBuilder sb = new StringBuilder();
        if ((style & BOLD) != 0)
            sb.append(" bold");
        if ((style & ITALIC) != 0)
            sb.append(" italic");
        if ((style & UNDERLINE) != 0)
            sb.append(" underline");
        if ((style & STRIKETHROUGH) != 0)
            sb.append(" strikethrough");
        return sb.substring(1);
    }

    /**
     * A style from a preference: a number, as themes have always written it,
     * or words, "bold italic", "underline", "strikethrough", "plain", apart by
     * spaces, commas or '|'. Returns -1 for null or anything else.
     */
    public static int parse(String value) {
        if (value == null)
            return -1;
        value = value.trim();
        if (value.isEmpty())
            return -1;
        try {
            int style = Integer.parseInt(value);
            return (style & ~ALL) == 0 ? style : -1;
        }
        catch (NumberFormatException ignored) {}
        int style = PLAIN;
        for (String word : value.toLowerCase(Locale.ROOT).split("[\\s,|]+")) {
            switch (word) {
                case "plain":
                case "normal":
                    break;
                case "bold":
                    style |= BOLD;
                    break;
                case "italic":
                    style |= ITALIC;
                    break;
                case "underline":
                    style |= UNDERLINE;
                    break;
                case "strikethrough":
                case "strike":
                    style |= STRIKETHROUGH;
                    break;
                default:
                    return -1;
            }
        }
        return style;
    }
}
