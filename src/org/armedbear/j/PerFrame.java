/*
 * PerFrame.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One T in each frame that asks for it, as a buffer's sidebar tree, since a
 * component is in one frame at a time: kept by the frame, going with it,
 * and made when first asked for there. With no frame, as in tests, one of
 * its own.
 */
public final class PerFrame<T> {
    private final Function<Frame, T> make;
    private T frameless;

    public PerFrame(Function<Frame, T> make) {
        this.make = make;
    }

    /** frame's, or with null the frameless one. */
    public T in(Frame frame) {
        if (frame != null)
            return frame.keep(this, make);
        if (frameless == null)
            frameless = make.apply(null);
        return frameless;
    }

    /** Each one made so far. */
    @SuppressWarnings("unchecked")
    public List<T> all() {
        final List<T> out = new ArrayList<>();
        for (int i = 0; i < Editor.getFrameCount(); i++) {
            final Object t = Editor.getFrame(i).kept(this);
            if (t != null)
                out.add((T) t);
        }
        if (frameless != null)
            out.add(frameless);
        return out;
    }

    /** Every frame's forgotten, as its owner goes. */
    public void forget() {
        for (int i = 0; i < Editor.getFrameCount(); i++)
            Editor.getFrame(i).forget(this);
        frameless = null;
    }
}
