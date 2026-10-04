/*
 * AbclExtension.java
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

package org.armedbear.j.extension.abcl;

import org.armedbear.j.Log;
import org.armedbear.j.extension.Extension;
import org.armedbear.j.extension.ExtensionContext;
import org.armedbear.j.mode.lisp.JLispBuffer;

/**
 * Armed Bear Common Lisp, embedded in j.
 *
 * <p>Registers a command and three providers, and starts nothing. ABCL boots
 * the first time something evaluates a form: opening a buffer, saving a file
 * or pressing a key costs nothing until then.
 */
public final class AbclExtension implements Extension {
    public String getName() {
        return "abcl";
    }

    public String getVersion() {
        return "1.8.0";
    }

    public void initialize(ExtensionContext context) {
        // The class literal, not its name: core resolves command classes with
        // Class.forName on its own loader, which cannot see this one.
        context.registerCommand("jlisp", JLispBuffer.class, "jlisp");
        context.registerLanguageClient(new AbclClient());
        context.registerHooks(new AbclHooks());
        context.registerKeyMapProvider(new AbclKeyMapProvider());
    }

    public void shutdown() {
        Log.debug("abcl extension shutting down");
    }
}
