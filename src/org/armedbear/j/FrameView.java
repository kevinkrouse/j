/*
 * FrameView.java
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

/**
 * A buffer shown across the whole frame with buffers of its own, as the
 * log with its files and diff: switched to and away from as one, as
 * Neogit's tab or IntelliJ's tool window is. Switching to it, or to one of
 * its buffers, lays the frame out as it shows; switching from it to any
 * other buffer puts back the windows from before, and it stays, to come
 * back to. Unlike a pair, which binds one window under another, it takes
 * the frame.
 */
public interface FrameView {
    /** Its windows, focus's focused. */
    WindowLayout.Node layout(Frame frame, Buffer focus);

    /** The windows from before it took the frame, put back when it is left; null when it isn't shown. */
    WindowLayout.Node before();

    void setBefore(WindowLayout.Node before);

    /**
     * One of its buffers is killed on its own, from the buffer list or :bd:
     * true if the view closes it itself, as the log closes whole; false to
     * let it go alone.
     */
    default boolean partKilled(Buffer part) {
        return false;
    }
}
