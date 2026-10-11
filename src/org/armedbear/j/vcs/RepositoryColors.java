/*
 * RepositoryColors.java
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

package org.armedbear.j.vcs;

import java.awt.Color;
import org.armedbear.j.Editor;
import org.armedbear.j.Preferences;

/**
 * The colors that tell a project's repositories apart, as IntelliJ's log
 * does: color.vcs.repo1 to color.vcs.repo8, or these defaults.
 */
public final class RepositoryColors {
    public static final int COUNT = 8;

    // Distinct hues at a lightness each background can carry.
    private static final int[] LIGHT =
            { 0x3574f0, 0xd96b00, 0x2a9d4b, 0xc2185b, 0x7b4fd6, 0x00838f, 0xa1887f, 0x9e9d24 };
    private static final int[] DARK =
            { 0x6ea2ff, 0xf0a04b, 0x5fc27e, 0xf06292, 0xb39dfa, 0x4dd0e1, 0xc9a99a, 0xd4d45a };

    private RepositoryColors() {}

    /** The color of index (0 to COUNT - 1), for a dark background or a light one. */
    public static Color color(int index, boolean dark) {
        int i = Math.floorMod(index, COUNT);
        Preferences preferences = Editor.preferences();
        if (preferences != null) {
            Color c = preferences.getColorProperty("color.vcs.repo" + (i + 1));
            if (c != null)
                return c;
        }
        return new Color((dark ? DARK : LIGHT)[i]);
    }
}
