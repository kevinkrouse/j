/*
 * BlameCommands.java
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

package org.armedbear.j.vcs.blame;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;

/** vcsBlame: the buffer's blame column, on and off. */
public final class BlameCommands {
    private BlameCommands() {}

    /** Toggles the blame column on the editor's buffer. */
    public static void vcsBlame(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        final BlameColumn on = column(buffer);
        if (on != null) {
            buffer.removeGutterColumn(on);
            on.blame().removeListener(on.changed());
            return;
        }
        final Blame blame = Blame.of(buffer);
        if (blame == null) {
            editor.status("No blame: not a file in a repository");
            return;
        }
        final BlameColumn column = new BlameColumn(buffer, blame);
        blame.addListener(column.changed());
        buffer.addGutterColumn(column);
        // What another has read already, as the caret line's, shows now.
        column.changed().run();
        if (!column.told())
            editor.status("Reading blame\u2026");
    }

    // The column on buffer, or null.
    private static BlameColumn column(Buffer buffer) {
        for (GutterColumn c : buffer.getGutterColumns())
            if (c instanceof BlameColumn b)
                return b;
        return null;
    }
}
