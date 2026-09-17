/*
 * ScriptFunction.java
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

/**
 * A function defined in some other language that has been bound to a key or
 * recorded in a macro.
 *
 * <p>This is what lets core hold on to, say, a Lisp closure without naming its
 * type: {@link org.armedbear.j.KeyMapping} already stores its command as an
 * opaque Object, and core tests for this interface instead of for
 * org.armedbear.lisp.LispObject.
 */
public interface ScriptFunction
{
    /** Run it. Implementations report their own errors rather than throwing. */
    void invoke();

    /** How to print it, for describeKey and the key map listing. */
    String describe();
}
