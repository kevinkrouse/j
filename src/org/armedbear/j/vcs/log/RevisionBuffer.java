/*
 * RevisionBuffer.java
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

import static org.armedbear.j.Constants.*;

import org.armedbear.j.Buffer;

/** A file as a commit has it, read-only, in its file's mode: "Display.java @ 1a2b3c4". */
final class RevisionBuffer extends Buffer {
    RevisionBuffer(String path, String id, String text) {
        supportsUndo = false;
        type = TYPE_OUTPUT;
        readOnly = true;
        final String name = path.substring(path.lastIndexOf('/') + 1);
        setModeFromFilename(name);
        title = name + " @ " + (id.length() > 7 ? id.substring(0, 7) : id);
        setText(text);
        setInitialized(true);
    }

    @Override
    public int load() {
        return LOAD_COMPLETED;
    }

    @Override
    public String getFileNameForDisplay() {
        return title;
    }
}
