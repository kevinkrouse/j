/*
 * EvalHandler.java
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
 * Receives the result of an asynchronous evaluation. Conjure's on-result
 * callback.
 *
 * <p>May be called on any thread, including one belonging to the language
 * runtime, so an implementation that touches the display must hop to the event
 * dispatch thread itself.
 */
@FunctionalInterface
public interface EvalHandler
{
    void onResult(EvalResult result);
}
