/*
 * ChangeColors.java
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

import java.awt.Color;

/**
 * The colors of a change wherever it is shown, for a light background or a
 * dark one: the status buffer, the change marks, a commit's diff, a
 * comparison and the buffer lists.
 */
public final class ChangeColors {
    private ChangeColors() {}

    public static Color added(boolean dark) {
        return new Color(dark ? 0x7ccf86 : 0x1a7f37);
    }

    public static Color modified(boolean dark) {
        return new Color(dark ? 0x6ea2ff : 0x3574f0);
    }

    public static Color removed(boolean dark) {
        return new Color(dark ? 0xf08080 : 0xc0262d);
    }
}
