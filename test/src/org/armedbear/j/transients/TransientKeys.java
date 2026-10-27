/*
 * TransientKeys.java
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
package org.armedbear.j.transients;

import static org.armedbear.j.Constants.CTRL_MASK;

import java.awt.event.KeyEvent;
import org.armedbear.j.KeyMap;
import org.armedbear.j.KeyMapping;

/** A popup item's key as a key map has it, for tests that the two agree. */
public final class TransientKeys {
    private TransientKeys() {}

    /** km's mapping of a popup's key: a character, or Enter, Tab or Ctrl with a letter. */
    public static KeyMapping lookup(KeyMap km, String key) {
        if (key.equals(Transient.ENTER))
            return km.lookup('\0', KeyEvent.VK_ENTER, 0);
        if (key.equals(Transient.TAB))
            return km.lookup('\0', KeyEvent.VK_TAB, 0);
        if (key.startsWith("<C-") && key.length() == 5)
            return km.lookup('\0', KeyEvent.VK_A + key.charAt(3) - 'a', CTRL_MASK);
        if (key.length() != 1)
            throw new IllegalArgumentException("Not one key: " + key);
        return km.lookup(key.charAt(0), 0, 0);
    }
}
