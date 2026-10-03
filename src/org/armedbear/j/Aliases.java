/*
 * Aliases.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public final class Aliases {
    private static final Properties systemAliases = new Properties();

    private final File file;

    private Properties userAliases;

    public Aliases() {
        file = File.getInstance(Directories.getConfigDirectory(), "aliases");
        systemAliases.setProperty("prefs", Preferences.getPreferencesFile().netPath());
        systemAliases.setProperty("aliases", file.netPath());
        // Load user aliases from file.
        loadUserAliases();
    }

    public final void reload() {
        loadUserAliases();
    }

    public final File getFile() {
        return file;
    }

    public static final boolean isSystemAlias(String alias) {
        return systemAliases.containsKey(alias);
    }

    public final String get(String alias) {
        // Look for system alias first (system aliases cannot be overridden).
        String value = systemAliases.getProperty(alias);
        if (value != null)
            return value;
        // Not a system alias.
        return userAliases.getProperty(alias);
    }

    public final void setAlias(String alias, String value) {
        // Ignore attempt to set system alias.
        if (isSystemAlias(alias))
            return;
        userAliases.setProperty(alias, value);
        save();
    }

    // "alias foo here"
    public void setAliasForBuffer(String alias, Buffer buffer) {
        // Ignore attempt to set system alias.
        if (isSystemAlias(alias))
            return;
        String value = buffer.getAliasValue();
        if (value != null)
            setAlias(alias, value);
    }

    public final void remove(String alias) {
        if (userAliases.remove(alias) != null)
            save();
    }

    private void loadUserAliases() {
        userAliases = new Properties();
        if (file.isFile()) {
            try (InputStream inputStream = file.getInputStream()) {
                userAliases.load(inputStream);
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
    }

    private void save() {
        try (OutputStream outputStream = file.getOutputStream()) {
            userAliases.store(outputStream, null);
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    /** Sets an alias users can't change, such as an extension's "inbox"; null removes it. */
    public static void setSystemAlias(String alias, String value) {
        if (value != null)
            systemAliases.setProperty(alias, value);
        else
            systemAliases.remove(alias);
    }
}
