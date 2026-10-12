/*
 * TransientCommands.java
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

import org.armedbear.j.Editor;

public final class TransientCommands {
    private TransientCommands() {}

    /** Opens the popup of the buffer's keys, arguments and commands. */
    public static void modePopup(Editor editor) {
        final Transient t = editor.getMode().getTransient(editor);
        if (t == null)
            editor.status("No popup in " + editor.getMode() + " mode");
        else
            TransientPopup.open(editor, t);
    }
}
