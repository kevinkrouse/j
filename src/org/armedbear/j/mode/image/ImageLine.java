/*
 * ImageLine.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

package org.armedbear.j.mode.image;

import java.awt.Image;
import java.awt.Rectangle;
import java.util.List;
import org.armedbear.j.AbstractLine;
import org.armedbear.j.Display;
import org.armedbear.j.Line;
import org.armedbear.j.Link;

/**
 * One line-high strip of one or more images. A tall image is drawn as a run of
 * these, each showing the rows from {@code top} down.
 */
public final class ImageLine extends AbstractLine implements Line {
    /**
     * An image drawn x pixels in, scaled to width by height, and where a click
     * on it goes (or null).
     */
    public record Placement(Image image, int x, int width, int height, Link link) {}

    private final List<Placement> placements;
    private final int top;
    private final int stripHeight;
    private final int height;

    public ImageLine(Image image, Rectangle r) {
        this(List.of(new Placement(image, r.x, r.width, image.getHeight(null), null)), r.y, r.height);
    }

    public ImageLine(List<Placement> placements, int top, int stripHeight) {
        this.placements = List.copyOf(placements);
        this.top = top;
        this.stripHeight = stripHeight;
        height = Math.max(stripHeight, Display.getCharHeight());
    }

    public final List<Placement> getPlacements() {
        return placements;
    }

    // How far into the images this strip starts.
    public final int getTop() {
        return top;
    }

    public final int getStripHeight() {
        return stripHeight;
    }

    // The placement drawn at x, or null.
    public final Placement placementAt(int x) {
        for (Placement p : placements) {
            if (x >= p.x() && x < p.x() + p.width() && top < p.height())
                return p;
        }
        return null;
    }

    // Frees the images. The strips of one picture share it, so this frees it
    // for all of them.
    public final void flushImage() {
        for (Placement p : placements)
            p.image().flush();
    }

    @Override
    public final int getHeight() {
        return height;
    }

    @Override
    public final int getWidth() {
        int width = 0;
        for (Placement p : placements)
            width = Math.max(width, p.x() + p.width());
        return width;
    }

    @Override
    public final int flags() {
        return 0;
    }

    @Override
    public final void setFlags(int flags) {}

    @Override
    public String getText() {
        return null;
    }

    @Override
    public final void setText(String s) {}

    @Override
    public final char charAt(int i) {
        return '\0';
    }

    @Override
    public final String substring(int beginIndex) {
        return null;
    }

    @Override
    public final String substring(int beginIndex, int endIndex) {
        return null;
    }

    @Override
    public final String trim() {
        return null;
    }

    @Override
    public final int length() {
        return 0;
    }

    @Override
    public final byte[] getBytes(String encoding) {
        return null;
    }

    @Override
    public final boolean isBlank() {
        return false;
    }
}
