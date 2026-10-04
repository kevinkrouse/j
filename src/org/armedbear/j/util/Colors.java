/*
 * Colors.java
 *
 * Copyright (C) 1998-2005 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.awt.Color;
import java.util.StringTokenizer;

/** Colors as j's preferences and themes write them: names, "r g b" and hex. */
public final class Colors {
    private Colors() {}

    /**
     * A color as preferences write one: a name (black, white, yellow, blue,
     * red, gray, green), "#rgb", "#rrggbb", or three numbers 0-255 for red,
     * green and blue. Returns null for anything else, and says nothing about
     * it, so it can be asked of any text.
     */
    public static Color parseColor(String s) {
        if (s == null)
            return null;
        s = s.trim();
        switch (s) {
            case "black":
                return Color.black;
            case "white":
                return Color.white;
            case "yellow":
                return Color.yellow;
            case "blue":
                return Color.blue;
            case "red":
                return Color.red;
            case "gray":
                return Color.gray;
            case "green":
                return Color.green;
        }
        if (s.startsWith("#"))
            return parseHexColor(s);
        final StringTokenizer st = new StringTokenizer(s);
        if (st.countTokens() != 3)
            return null;
        final int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                rgb[i] = Integer.parseInt(st.nextToken());
            }
            catch (NumberFormatException e) {
                return null;
            }
            if (rgb[i] < 0 || rgb[i] > 255)
                return null;
        }
        return new Color(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * "#rgb" or "#rrggbb", as CSS writes them; null for anything else. In
     * "#rgb" each digit is doubled, so "#f80" is "#ff8800".
     */
    public static Color parseHexColor(String s) {
        final int length = s.length();
        if (length != 4 && length != 7 || s.charAt(0) != '#')
            return null;
        int rgb = 0;
        for (int i = 1; i < length; i++) {
            final int digit = Character.digit(s.charAt(i), 16);
            if (digit < 0)
                return null;
            rgb = rgb << 4 | digit;
            if (length == 4)
                rgb = rgb << 4 | digit;
        }
        return new Color(rgb);
    }
}
