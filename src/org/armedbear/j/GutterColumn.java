/*
 * GutterColumn.java
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

import java.awt.Graphics2D;

/**
 * A column a buffer paints in the gutter, after the line numbers and before
 * the text, as the log's graph: a decoration with a width, not text, so
 * columns and selections stay the text's.
 */
public interface GutterColumn {
    /** Its width in pixels now. */
    int width();

    /** Paints line's part of it, its left at x and the row's top at y, height tall. */
    void paint(Graphics2D g, Line line, int x, int y, int height);

    /** The tool tip at x and y in line's part of it, from its left and the row's top, or null. */
    default String getToolTipText(Line line, int x, int y) {
        return null;
    }
}
