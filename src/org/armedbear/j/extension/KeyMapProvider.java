/*
 * KeyMapProvider.java
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.extension;

import org.armedbear.j.KeyMap;

/**
 * Lets an extension supply key maps, the way j's init.lisp does today with
 * (j:current-global-map) and (j::<mode>-mode-map).
 *
 * <p>Returning null means "nothing to say" -- core then falls back to a key map
 * file named in the preferences, and finally to its own defaults.
 */
public interface KeyMapProvider
{
    KeyMapProvider NONE = new KeyMapProvider()
    {
        public KeyMap getGlobalKeyMap() { return null; }
        public KeyMap getKeyMapForMode(String modeDisplayName) { return null; }
    };

    KeyMap getGlobalKeyMap();

    KeyMap getKeyMapForMode(String modeDisplayName);
}
