/*
 * CaretPopup.java
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
 * A popup over an editor's text beside its caret, as a hunk's preview. The
 * editor holds one at a time and closes it after the next command, or on a
 * wheel scroll; see Editor.showCaretPopup.
 */
public interface CaretPopup {
    /** The editor's window scrolled: goes with the caret's line. */
    void scrolled();

    /** Takes it off the screen. Called only by the editor. */
    void removed();

    /** The keys it takes while it shows, before the editor's; null for none. */
    default KeyMap getKeyMap() {
        return null;
    }
}
