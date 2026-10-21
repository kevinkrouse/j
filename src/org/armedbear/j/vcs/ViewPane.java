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

package org.armedbear.j.vcs;

import java.util.List;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.FrameView;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;

/**
 * A view that is part of another buffer, its owner, as the log's files and
 * diff are the log's: listed under it, shown in its frame view, skipped
 * going through the buffers.
 */
public abstract class ViewPane<B extends Buffer> extends ViewBuffer {
    protected final B owner;
    // Each read's number, so that only the last one asked for is shown.
    protected int generation;

    protected ViewPane(Mode mode, B owner, String title, String placeholder) {
        super(mode, title, placeholder);
        this.owner = owner;
    }

    public B getOwner() {
        return owner;
    }

    @Override
    public boolean isPrimary() {
        return false;
    }

    @Override
    public Buffer getPrimary() {
        return owner;
    }

    @Override
    public FrameView getFrameView() {
        return owner.getFrameView();
    }

    // Killed on its own, its owner may close it, with more.
    @Override
    public synchronized void kill() {
        if (!(owner instanceof FrameView v) || !v.partKilled(this))
            super.kill();
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
