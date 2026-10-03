/*
 * ExtensionContext.java
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

import org.armedbear.j.Preferences;

/**
 * What an {@link Extension} is handed to register itself with.
 */
public interface ExtensionContext {
    /**
     * Register a command.
     *
     * <p>The owning class is passed as a Class rather than a name because core
     * resolves command class names with Class.forName on its own loader, which
     * cannot see anything in an extension's loader. The method must be public
     * static, taking either no arguments or a single String.
     */
    void registerCommand(String name, Class<?> owner, String methodName);

    /** Register another name for a command the same class already provides. */
    void registerCommand(String name, Class<?> owner, String methodName, String alias);

    void registerLanguageClient(LanguageClient client);

    void registerHooks(EditorHooks hooks);

    void registerKeyMapProvider(KeyMapProvider provider);

    /** The loader this extension's classes and resources came from. */
    ClassLoader getClassLoader();

    /**
     * The editor's preferences. Extensions namespace their own keys by prefix
     * rather than declaring anything in Property, which stays closed.
     */
    Preferences getPreferences();
}
