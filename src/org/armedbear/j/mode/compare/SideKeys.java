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

package org.armedbear.j.mode.compare;

import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import org.armedbear.j.KeyMap;

/**
 * A side's keys over its file's mode's: q closes it, and outside vim n and
 * p go from change to change, Tab opens or closes a fold, s is the scroll
 * lock and w ignores whitespace; in vim n and p are its search's, ]c and [c
 * go by change and its z keys fold, and w is a word; then the
 * keys of the view it is part of, if any. Made again when the mode's map
 * changes; one for both sides.
 */
final class SideKeys {
    // A map made over base, as base was at changes; one modal, one not.
    private record Made(KeyMap base, int changes, KeyMap map) {}

    private final Made[] made = new Made[2];
    private Consumer<KeyMap> extra = km -> {};

    /** extra maps its keys over the rest from now on. */
    void setExtra(Consumer<KeyMap> extra) {
        this.extra = extra;
        made[0] = made[1] = null;
    }

    KeyMap over(KeyMap map, boolean modal) {
        final int changes = map == null ? 0 : map.changes();
        final int i = modal ? 1 : 0;
        final Made m = made[i];
        if (m == null || m.base() != map || m.changes() != changes)
            made[i] = new Made(map, changes, with(map, modal));
        return made[i].map();
    }

    private KeyMap with(KeyMap map, boolean modal) {
        final KeyMap km = map != null ? map.copy() : new KeyMap();
        km.mapKey('q', "compareQuit");
        if (!modal) {
            km.mapKey(KeyEvent.VK_TAB, 0, "toggleFold");
            km.mapKey('n', "nextChange");
            km.mapKey('p', "previousChange");
            km.mapKey('s', "compareScrollLock");
            km.mapKey('w', "diffIgnoreWhitespace");
        }
        extra.accept(km);
        return km;
    }
}
