/*
 * FakeExtension.java
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

/** A minimal well-behaved extension, used by ExtensionsTest. */
public final class FakeExtension implements Extension {
    public static final EditorHooks HOOKS = new EditorHooks() {
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

    public static boolean shutdownCalled;

    @Override
    public String getName() {
        return "fake";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public void initialize(ExtensionContext context) {
        context.registerHooks(HOOKS);
    }

    @Override
    public void shutdown() {
        shutdownCalled = true;
    }
}
