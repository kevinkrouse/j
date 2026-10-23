/*
 * ViewPane.java
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

import java.util.List;

/** A view that is always a part of another buffer, its owner. */
public abstract class ViewPane<B extends Buffer> extends ViewBuffer {
    // Each read's number, so that only the last one asked for is shown.
    protected int generation;

    protected ViewPane(Mode mode, B owner, String title, String placeholder) {
        super(mode, title, placeholder);
        setOwner(owner);
    }

    // Only ever given a B.
    @SuppressWarnings("unchecked")
    @Override
    public B getOwner() {
        return (B) super.getOwner();
    }

    /**
     * Puts lines in place of what it had, each window showing it with its
     * caret on dot (null for the first line) and the first line at the top.
     */
    protected void replace(List<? extends Line> lines, Line dot) {
        fill(lines);
        for (Editor ed : windows())
            place(ed, dot != null ? dot : getFirstLine(), getFirstLine());
    }
}
