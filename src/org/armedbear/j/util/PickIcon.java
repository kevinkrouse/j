/*
 * PickIcon.java
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

import java.awt.Color;
import java.awt.Graphics2D;
import javax.swing.ImageIcon;

/**
 * The box a picked thing is drawn with in a gutter column, the status's and
 * the directory buffer's alike: empty, filled with a check, or filled with
 * a dash for one some of whose parts are picked; j's own icons, so any size
 * and the theme's color.
 */
public final class PickIcon {
    private PickIcon() {}

    private static final int SIZE = 14;
    private static final int PAD = 1;
    // The thickness of a box's line down through what it picks.
    private static final int TRAIL = 2;

    /** A column's width that draws them. */
    public static final int WIDTH = SIZE + 2 * PAD;

    /**
     * Paints state's box in a row at x, y, height tall: an empty one in the
     * icons' color, one picked or partly in picked, to stand out.
     */
    public static void paint(Graphics2D g, PickState state, Color picked, int x, int y, int height) {
        final String icon = switch (state) {
            case ALL -> "pick-on";
            case SOME -> "pick-some";
            case NONE -> "pick-off";
        };
        final ImageIcon i =
                state == PickState.NONE ? Icons.getIconFromFile(icon, SIZE) : Icons.getIconFromFile(icon, SIZE, picked);
        i.paintIcon(null, g, x + PAD, y + Math.max(0, (height - SIZE) / 2));
    }

    /**
     * Paints in picked a row's part of the thin line from a box down
     * through the rows it picks: from under the box, through, or into a
     * tick toward the text, at the left if textAtLeft.
     */
    public static void paintTrail(
            Graphics2D g,
            PickColumn.Trail trail,
            boolean textAtLeft,
            Color picked,
            int x,
            int y,
            int height) {
        final int mid = x + WIDTH / 2 - TRAIL / 2;
        g.setColor(picked);
        switch (trail) {
            case FROM -> {
                final int below = y + Math.max(0, (height - SIZE) / 2) + SIZE;
                g.fillRect(mid, below, TRAIL, y + height - below);
            }
            case THROUGH -> g.fillRect(mid, y, TRAIL, height);
            case TO -> {
                final int center = y + height / 2;
                g.fillRect(mid, y, TRAIL, center - y + TRAIL);
                if (textAtLeft)
                    g.fillRect(x, center, mid - x + TRAIL, TRAIL);
                else
                    g.fillRect(mid, center, x + WIDTH - mid, TRAIL);
            }
        }
    }
}
