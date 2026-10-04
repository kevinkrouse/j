/*
 * EditorHooks.java
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

import org.armedbear.j.Buffer;

/**
 * Editor events an extension can listen for.
 *
 * <p>Core calls these unconditionally. {@link #NONE} is installed until an
 * extension registers something, which is what lets the call sites drop their
 * "is lisp running yet" guards -- notably the one in Dispatcher, which runs on
 * every keystroke.
 */
public interface EditorHooks {
    /** Does nothing, and is what core holds until an extension registers. */
    EditorHooks NONE = new EditorHooks() {
        @Override
        public void bufferActivated(Buffer buffer) {}

        @Override
        public void openFile(Buffer buffer) {}

        @Override
        public void afterSave(Buffer buffer) {}

        @Override
        public void modeCreated(String modeDisplayName) {}

        @Override
        public void eventHandled() {}

        @Override
        public void invoke(String hookName, Object... args) {}
    };

    void bufferActivated(Buffer buffer);

    void openFile(Buffer buffer);

    void afterSave(Buffer buffer);

    /** A mode was constructed; ABCL turns this into <name>-mode-hook. */
    void modeCreated(String modeDisplayName);

    /** Called after each dispatched event. Must stay cheap. */
    void eventHandled();

    /**
     * Fire a named hook. Arguments are passed raw -- quoting or escaping them
     * for the target language is the implementation's job, not the caller's.
     */
    void invoke(String hookName, Object... args);
}
