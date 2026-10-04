/*
 * LispFunction.java
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
import org.armedbear.j.extension.ScriptFunction;
import org.armedbear.lisp.Lisp;
import org.armedbear.lisp.LispObject;
import org.armedbear.lisp.LispThread;

/**
 * A Lisp function bound to a key or recorded in a macro.
 *
 * <p>Core used to hold the {@code LispObject} itself and test for it with
 * {@code instanceof}. It holds this instead, so that {@code KeyMapping} can
 * carry a closure from init.lisp without core ever naming an ABCL type.
 */
public final class LispFunction implements ScriptFunction {
    private final LispObject function;

    public LispFunction(LispObject function) {
        if (function == null)
            throw new IllegalArgumentException("function is required");
        this.function = function;
    }

    /** The object itself, for Lisp code that wants it back. */
    public LispObject getFunction() {
        return function;
    }

    @Override
    public void invoke() {
        // Reports rather than throws: this runs from the dispatcher, where a
        // broken binding must not take the editor down with it -- and must not
        // drop it into ABCL's debugger on standard input either, which is what
        // a bare execute() does.
        try {
            LispObject result = LispThread.currentThread()
                .execute(
                    AbclSession.safeCaller(),
                    Lisp.coerceToFunction(function)
                );
            if (result != Lisp.NIL)
                Log.error("key binding failed: ".concat(AbclSession.report(result)));
        }
        catch (Throwable t) {
            Log.error(t);
        }
    }

    @Override
    public String describe() {
        try {
            return function.printObject();
        }
        catch (Throwable t) {
            Log.debug(t);
            return String.valueOf(function);
        }
    }

    @Override
    public String toString() {
        return describe();
    }
}
