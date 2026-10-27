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

import java.awt.event.ActionEvent;

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

    /** The toolbar of a frame showing it, whichever of its buffers is current: its own buffer's mode's. */
    default ToolBar getToolBar(Frame frame) {
        return this instanceof Buffer b ? b.getMode().getToolBar(frame) : null;
    }

    /** The Mode menu's items, whichever of its buffers is current: its own buffer's mode's. */
    default void populateModeMenu(Editor editor, Menu menu) {
        if (this instanceof Buffer b)
            b.getMode().populateModeMenu(editor, menu);
    }

    /** The toolbar a frame shows with buffer current: its view's, or its mode's. */
    static ToolBar toolBarFor(Buffer buffer, Frame frame) {
        final FrameView view = buffer.getFrameView();
        return view != null ? view.getToolBar(frame) : buffer.getMode().getToolBar(frame);
    }

    /**
     * The window in from's frame where command, from the toolbar or a menu,
     * runs, from from, one of its buffers' windows: from itself. A command
     * not the view's own, as Copy, runs in from.
     */
    default Editor windowFor(Editor from, String command) {
        return from;
    }

    /** Where command from the toolbar or a menu runs, from from: as its view says, or in from. */
    static Editor target(Editor from, String command) {
        final FrameView view = from == null ? null : from.getBuffer().getFrameView();
        final Editor to = view != null ? view.windowFor(from, command) : from;
        return to != null ? to : from;
    }

    /** e's command, from the toolbar or a menu, run where its view says, from from, that window made current. */
    static void run(Editor from, ActionEvent e) {
        final String command = e.getActionCommand() == null ? "" : e.getActionCommand().split(" ", 2)[0];
        final Editor to = target(from, command);
        if (to != from)
            WindowCommands.switchWindow(from, to);
        to.getDispatcher().actionPerformed(e);
    }
}
