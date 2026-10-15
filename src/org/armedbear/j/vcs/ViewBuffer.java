/*
 * ViewBuffer.java
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

package org.armedbear.j.vcs;

import static org.armedbear.j.Constants.LOAD_COMPLETED;

import org.armedbear.j.Buffer;
import org.armedbear.j.Mode;

/**
 * A read-only view of a repository, built from what was read of it, as the
 * status buffer and the log's: no file and no undo. Its mode's properties
 * keep line numbers, change marks and bracket highlights off.
 */
public abstract class ViewBuffer extends Buffer {
    /** A view in mode, called title, showing placeholder until its lines are read. */
    protected ViewBuffer(Mode mode, String title, String placeholder) {
        supportsUndo = false;
        type = TYPE_OUTPUT;
        this.mode = mode;
        formatter = mode.getFormatter(this);
        readOnly = true;
        this.title = title;
        withWriteLock(() -> {
            appendLine(placeholder);
            renumber();
        });
        setInitialized(true);
        setLoaded(true);
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
