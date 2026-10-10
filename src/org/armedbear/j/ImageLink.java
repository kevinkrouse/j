/*
 * ImageLink.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

public final class ImageLink extends Link {
    private String text;
    private int width;
    private int height;
    private Link anchor;

    public ImageLink(String target) {
        super(target);
    }

    public final String getText() {
        return text;
    }

    public final void setText(String text) {
        this.text = text;
    }

    // The size the page gives the image, in CSS pixels.
    public final int getWidth() {
        return width;
    }

    public final int getHeight() {
        return height;
    }

    public final void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    // The <a> the image is in, or null.
    public final Link getAnchor() {
        return anchor;
    }

    public final void setAnchor(Link anchor) {
        this.anchor = anchor;
    }
}
