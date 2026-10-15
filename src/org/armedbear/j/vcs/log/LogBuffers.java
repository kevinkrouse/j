/*
 * LogBuffers.java
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

package org.armedbear.j.vcs.log;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;

/** What the log's buffers share. */
final class LogBuffers {
    private LogBuffers() {}

    /** The log a buffer of it belongs to: the list itself, or its files or diff; else null. */
    static LogBuffer owner(Buffer buffer) {
        return switch (buffer) {
            case LogBuffer l -> l;
            case LogPane p -> p.owner;
            case null, default -> null;
        };
    }

    /** The window showing buffer, the current one if it does; else null. */
    static Editor windowOf(Buffer buffer) {
        if (buffer == null)
            return null;
        final Editor current = Editor.currentEditor();
        if (current != null && current.getBuffer() == buffer)
            return current;
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                return ed;
        return null;
    }
}
