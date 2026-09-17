/*
 * Extension.java
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
 * An optional part of j, discovered with java.util.ServiceLoader from jars
 * under lib/extensions/ or the user's config directory.
 *
 * <p>An implementation declares itself in
 * META-INF/services/org.armedbear.j.extension.Extension.
 *
 * <p>{@link #initialize} should only register things. Commands resolve their
 * classes on first use, so registering a name is cheap and nothing an extension
 * provides needs to be loaded -- let alone started -- until the user asks for
 * it. The abcl extension registers a command and three providers at startup and
 * does not boot an interpreter until something evaluates a form.
 */
public interface Extension
{
    /** Short, stable, lower-case: "abcl". Also the name used to disable it. */
    String getName();

    String getVersion();

    /** Register commands and providers. Keep it cheap; do no real work here. */
    void initialize(ExtensionContext context);

    /** Release anything held open. Called when the editor exits. */
    default void shutdown()
    {
    }
}
