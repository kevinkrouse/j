/*
 * DiffWhitespace.java
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

package org.armedbear.j.mode.compare;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Property;
import org.armedbear.j.util.Whitespace;

/**
 * The whitespace diffs ignore: ignoring on or off, and which, as the
 * diffIgnoreWhitespace preference sets them to begin with, until j exits.
 * Read from any thread; changed on the event thread, each open
 * {@link Listener} told.
 */
public final class DiffWhitespace {
    /** A buffer that shows diffs. */
    public interface Listener {
        /** Diffs ignore other whitespace now: what it shows is shown again so. */
        void diffWhitespaceChanged();
    }

    // Null: diffIgnoreWhitespace's.
    private static volatile Boolean on;
    private static volatile Whitespace chosen;

    private DiffWhitespace() {}

    private static Whitespace preferred() {
        final Whitespace w = Whitespace.named(Editor.preferences().getStringProperty(Property.DIFF_IGNORE_WHITESPACE));
        return w != null ? w : Whitespace.NONE;
    }

    /** What diffs ignore now. */
    public static Whitespace ignored() {
        return isIgnoring() ? kind() : Whitespace.NONE;
    }

    /** Whether diffs ignore whitespace now. */
    public static boolean isIgnoring() {
        final Boolean b = on;
        return b != null ? b : preferred() != Whitespace.NONE;
    }

    /** What ignoring ignores, on or off: the last chosen, or diffIgnoreWhitespace's, or all. */
    public static Whitespace kind() {
        final Whitespace c = chosen;
        if (c != null)
            return c;
        final Whitespace p = preferred();
        return p != Whitespace.NONE ? p : Whitespace.ALL;
    }

    /** Ignoring on, or off. */
    public static void toggle() {
        on = !isIgnoring();
        changed();
    }

    /** k ignored, or none if it is now: a menu's check box. */
    public static void choose(Whitespace k) {
        if (k == Whitespace.NONE || ignored() == k)
            on = false;
        else {
            chosen = k;
            on = true;
        }
        changed();
    }

    // Each buffer showing a diff shows it again.
    private static void changed() {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof Listener l)
                l.diffWhitespaceChanged();
    }

    /** Toggled and chosen forgotten: diffIgnoreWhitespace's again. For tests. */
    public static void forget() {
        on = null;
        chosen = null;
    }
}
