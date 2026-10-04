/*
 * AbclHooks.java
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

import java.util.Locale;
import org.armedbear.j.Buffer;
import org.armedbear.j.LispAPI;
import org.armedbear.j.Log;
import org.armedbear.j.extension.EditorHooks;
import org.armedbear.lisp.JavaObject;
import org.armedbear.lisp.LispObject;
import org.armedbear.lisp.Primitives;
import org.armedbear.lisp.SimpleString;

/**
 * Editor events, turned into Lisp hooks.
 *
 * <p>Every method here starts by asking whether the interpreter is up, and
 * does nothing if it is not. That guard used to live at the call sites in core
 * as {@code Editor.isLispInitialized()}, and it has to stay somewhere: without
 * it the first keystroke in the location bar would boot ABCL synchronously on
 * the event dispatch thread, which is exactly what used to happen.
 */
public final class AbclHooks implements EditorHooks {
    public void bufferActivated(Buffer buffer) {
        if (ready() && buffer != null)
            LispAPI.invokeBufferActivatedHook(buffer);
    }

    public void openFile(Buffer buffer) {
        if (ready())
            LispAPI.invokeOpenFileHook(buffer);
    }

    public void afterSave(Buffer buffer) {
        if (ready())
            LispAPI.invokeAfterSaveHook(buffer);
    }

    public void modeCreated(String modeDisplayName) {
        if (ready() && modeDisplayName != null) {
            // "Java" -> java-mode-hook, the name init.lisp defines.
            invoke(
                modeDisplayName.toLowerCase(Locale.ROOT)
                    .replace(' ', '-')
                    .concat("-mode-hook")
            );
        }
    }

    public void eventHandled() {
        // On the dispatcher's path, once per keystroke.
        if (ready())
            LispAPI.eventHandled();
    }

    public void invoke(String hookName, Object... args) {
        if (!ready() || hookName == null)
            return;
        try {
            LispObject[] form = new LispObject[args.length + 2];
            form[0] = LispAPI.PACKAGE_J.intern("INVOKE-HOOK");
            form[1] = LispAPI.PACKAGE_J.intern(
                hookName.toUpperCase(Locale.ROOT).replace('_', '-')
            );
            for (int i = 0; i < args.length; i++)
                form[i + 2] = coerce(args[i]);
            Primitives.FUNCALL.execute(form);
        }
        catch (Throwable t) {
            Log.debug(t);
        }
    }

    /**
     * Arguments arrive raw -- quoting them is this end's job, which is why
     * CustomFocusManager no longer escapes backslashes on j's behalf.
     */
    private static LispObject coerce(Object arg) {
        if (arg == null)
            return org.armedbear.lisp.Lisp.NIL;
        if (arg instanceof LispObject)
            return (LispObject) arg;
        if (arg instanceof String)
            return new SimpleString((String) arg);
        return new JavaObject(arg);
    }

    private static boolean ready() {
        return AbclSession.isInitialized();
    }
}
