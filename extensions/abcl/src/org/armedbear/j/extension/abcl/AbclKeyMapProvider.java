/*
 * AbclKeyMapProvider.java
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
import org.armedbear.j.KeyMap;
import org.armedbear.j.Log;
import org.armedbear.j.extension.KeyMapProvider;
import org.armedbear.lisp.Interpreter;
import org.armedbear.lisp.JavaObject;
import org.armedbear.lisp.LispObject;

/**
 * Key maps defined in init.lisp.
 *
 * <p>Returning null means "nothing to say", and core then falls back to a key
 * map file and finally to its own defaults -- so an interpreter that has not
 * started yet is simply silent, not an error.
 */
public final class AbclKeyMapProvider implements KeyMapProvider {
    public KeyMap getGlobalKeyMap() {
        return evaluate("(j:current-global-map)");
    }

    public KeyMap getKeyMapForMode(String modeDisplayName) {
        if (modeDisplayName == null)
            return null;
        // "Lisp Shell" -> (j::lisp-shell-mode-map), which init.lisp may or may
        // not have defined; ignore-errors covers the may-not.
        String function =
            modeDisplayName.toLowerCase(Locale.ROOT)
                .replace(' ', '-')
                .concat("-mode-map");
        return evaluate("(ignore-errors (j::".concat(function).concat("))"));
    }

    private static KeyMap evaluate(String form) {
        if (!AbclSession.isInitialized())
            return null;
        try {
            LispObject result = Interpreter.evaluate(form);
            if (result instanceof JavaObject) {
                Object obj = ((JavaObject) result).getObject();
                if (obj instanceof KeyMap)
                    return (KeyMap) obj;
            }
        }
        catch (Throwable t) {
            Log.debug(t);
        }
        return null;
    }
}
