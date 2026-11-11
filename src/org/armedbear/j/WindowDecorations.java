/*
 * WindowDecorations.java
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
 * Decides whether j draws its own window decorations (title bar with the menu
 * embedded, custom resize border) instead of letting the operating system draw
 * them, FlatLAF-style.
 *
 * <p>Custom decorations are wanted on Linux under X11 and on Windows, but not
 * on macOS (which has a native title bar and a screen menu bar) and not on
 * Wayland (where windows cannot place themselves).
 *
 * <p>They are also skipped when the user picked a stock look and feel rather than
 * j's custom theme: a user who chose {@code "Metal"}, {@code "System"} or
 * {@code "Aqua"} keeps the operating system's decorations.
 */
public final class WindowDecorations {
    private WindowDecorations() {}

    /** Whether j should draw its own title bar and resize border. */
    public static boolean enabled() {
        if (Platform.isPlatformMacOSX())
            return false;
        if (isWayland())
            return false;
        // Only on j's custom theme path; a chosen stock LAF keeps OS decorations.
        if (Editor.lookAndFeel != null)
            return false;
        return Platform.isPlatformLinux() || Platform.isPlatformWindows();
    }

    /**
     * Whether we run on the native Wayland toolkit rather than X11 or XWayland.
     * The JetBrains Runtime selects it with {@code -Dawt.toolkit.name=WLToolkit};
     * the property or the toolkit's class name both identify it.
     */
    public static boolean isWayland() {
        final String name = System.getProperty("awt.toolkit.name", "");
        if (name.contains("WL"))
            return true;
        try {
            return java.awt.Toolkit.getDefaultToolkit().getClass().getName().contains("WL");
        }
        catch (Throwable t) {
            return false;
        }
    }
}
