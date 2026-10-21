/*
 * SideKeys.java
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

package org.armedbear.j.vcs.sidebyside;

import org.armedbear.j.KeyMap;

/**
 * A side's keys over its file's mode's: q closes the diff, and outside vim
 * n and p go from change to change; in vim they are its search's, and ]c
 * and [c go by change. Made again when the mode's map is, or changes;
 * one for both sides.
 */
final class SideKeys {
    private KeyMap base;
    private int baseChanges;
    private KeyMap made;
    private KeyMap modalBase;
    private int modalChanges;
    private KeyMap modalMade;

    KeyMap over(KeyMap map, boolean modal) {
        final int changes = map == null ? 0 : map.changes();
        if (modal) {
            if (modalMade == null || modalBase != map || modalChanges != changes) {
                modalBase = map;
                modalChanges = changes;
                modalMade = with(map, true);
            }
            return modalMade;
        }
        if (made == null || base != map || baseChanges != changes) {
            base = map;
            baseChanges = changes;
            made = with(map, false);
        }
        return made;
    }

    private static KeyMap with(KeyMap map, boolean modal) {
        final KeyMap km = map != null ? map.copy() : new KeyMap();
        km.mapKey('q', "vcsDiffQuit");
        if (!modal) {
            km.mapKey('n', "nextChange");
            km.mapKey('p', "previousChange");
        }
        return km;
    }
}
