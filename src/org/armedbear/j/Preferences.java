/*
 * Preferences.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.armedbear.j.util.Colors;
import org.armedbear.j.util.Utilities;

public final class Preferences {
    private Properties properties = new Properties();
    private ArrayList<PreferencesChangeListener> listeners;

    public static final File getPreferencesFile() {
        return File.getInstance(Directories.getConfigDirectory(), "prefs");
    }

    public static void editPrefs() {
        File prefs = getPreferencesFile();
        if (prefs == null)
            return;
        final Editor editor = Editor.currentEditor();
        Buffer buf = editor.openFile(prefs);
        if (buf != null)
            editor.activate(buf);
    }

    public void reload() {
        reloadSynchronized();
        // Deliberately not inside the lock: UIScale takes its own lock
        UIScale.reset();
        firePreferencesChanged();
    }

    private synchronized void reloadSynchronized() {
        reloadInternal();
    }

    private void reloadInternal() {
        File file = getPreferencesFile();
        if (file == null || !file.isFile()) {
            // No preferences file.
            properties = new Properties();
            return;
        }

        // Load preferences file into a temporary Properties object so we can
        // see if the user has specified a theme.
        Properties temp = new Properties();
        try (InputStream in = file.getInputStream()) {
            temp.load(in);
        }
        catch (IOException e) {
            Log.error(e);
        }
        // Convert keys to lower case.
        temp = canonicalize(temp);

        String themeName = temp.getProperty(Property.THEME.key());
        if (themeName == null || themeName.length() == 0) {
            // No theme specified.
            properties = temp;
            return;
        }

        String themePath = temp.getProperty(Property.THEME_PATH.key());

        // User has specified a theme. Load theme into a new Properties object.
        properties = loadTheme(themeName, themePath);

        // User preferences from temporary Properties object override theme.
        properties.putAll(temp);
    }

    // Returns new Properties with keys converted to lower case.
    private static Properties canonicalize(Properties properties) {
        Properties newProperties = new Properties();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            newProperties.put(key.toLowerCase(), entry.getValue());
        }
        return newProperties;
    }

    // FIXME This is far from ideal (but it does work).
    public synchronized void killTheme() {
        Iterator<Object> it = properties.keySet().iterator();
        while (it.hasNext()) {
            String key = (String) it.next();
            if (key.startsWith("color."))
                it.remove();
            else if (key.contains(".color."))
                it.remove();
            else if (key.startsWith("style."))
                it.remove();
            else if (key.contains(".style."))
                it.remove();
            else if (key.startsWith("link."))
                it.remove();
            else if (key.contains(".link."))
                it.remove();
        }
    }

    private static Properties loadTheme(String themeName, String themePath) {
        Properties properties = new Properties();
        File file = getThemeFile(themeName, themePath);
        if (file != null && file.isFile()) {
            try (InputStream in = file.getInputStream()) {
                properties.load(in);
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
        return canonicalize(properties);
    }

    /**
     * Whether file looks like a theme: the theme in use, or a name with no
     * extension in a directory on themePath or in j's own themes directory,
     * or in another directory named "themes" if its first setting is a
     * color, style or link. Themes are properties files, and most say so
     * only by where they are.
     */
    public synchronized boolean isThemeFile(File file) {
        if (file == null || !file.isLocal())
            return false;
        final String theme = getStringProperty(Property.THEME);
        if (
            theme != null
                && file.equals(
                    getThemeFile(theme, getStringProperty(Property.THEME_PATH))
                )
        )
            return true;
        final String name = file.getName();
        if (name == null || name.indexOf('.') >= 0)
            return false;
        final File dir = file.getParentFile();
        if (dir == null)
            return false;
        for (File resourceDir : Utilities.resourceDirs())
            if (dir.equals(File.getInstance(resourceDir, "themes")))
                return true;
        final String themePath = getStringProperty(Property.THEME_PATH);
        if (themePath != null) {
            final String[] dirs = new Path(stripQuotes(themePath)).list();
            if (dirs != null)
                for (String part : dirs)
                    if (dir.equals(File.getInstance(part)))
                        return true;
        }
        return "themes".equals(dir.getName()) && startsWithThemeSetting(file);
    }

    // Whether the first line that is not blank or a comment sets a color,
    // style or link, for all modes or for one.
    private static boolean startsWithThemeSetting(File file) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String s;
            while ((s = reader.readLine()) != null) {
                s = s.trim().toLowerCase();
                if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!')
                    continue;
                return s.matches("(\\w+\\.)?(color|style|link)\\..*");
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
        return false;
    }

    private static File getThemeFile(String themeName, String themePath) {
        if (themeName == null)
            return null;
        themeName = stripQuotes(themeName);

        // The string passed in is either the name of a theme ("Anokha") or
        // the full pathname of the file ("/home/peter/Anokha").
        if (Utilities.isFilenameAbsolute(themeName))
            return File.getInstance(themeName);

        // It's not an absolute filename. Check theme path.
        if (themePath != null) {
            Path path = new Path(stripQuotes(themePath));
            String[] array = path.list();
            if (array != null) {
                for (String part : array) {
                    File dir = File.getInstance(part);
                    if (dir != null && dir.isDirectory()) {
                        File themeFile = File.getInstance(dir, themeName);
                        if (themeFile != null && themeFile.isFile())
                            return themeFile;
                    }
                }
            }
        }

        // We haven't found it yet.  Look in default locations.
        Set<File> dirs = Utilities.resourceDirs();
        for (File dir : dirs) {
            // Look for a "themes" subdirectory under prefix directory.
            File themeDir = File.getInstance(dir, "themes");
            // "/usr/local/share/j/themes"
            if (themeDir != null && themeDir.isDirectory()) {
                File themeFile = File.getInstance(themeDir, themeName);
                if (themeFile != null && themeFile.isFile())
                    return themeFile;
            }
        }

        return null;
    }

    public synchronized void setProperty(Property property, String value) {
        properties.setProperty(property.key(), value);
    }

    public synchronized void setProperty(Property property, int value) {
        properties.setProperty(property.key(), String.valueOf(value));
    }

    public synchronized void setProperty(String key, String value) {
        properties.setProperty(key.toLowerCase(), value);
    }

    public synchronized void removeProperty(String key) {
        properties.remove(key.toLowerCase());
    }

    // Strips quotes if present.
    public synchronized String getStringProperty(Property property) {
        String value = getProperty(property.key());
        if (value != null)
            return stripQuotes(value);
        else
            return (String) property.getDefaultValue(); // May be null.
    }

    // Strips quotes if present.
    public synchronized String getStringProperty(String key) {
        String value = getProperty(key);
        if (value != null)
            return stripQuotes(value);
        else
            return null;
    }

    public synchronized boolean getBooleanProperty(Property property) {
        String value = getProperty(property.key());
        if (value != null) {
            value = value.trim();
            if (value.equals("true") || value.equals("1"))
                return true;
            if (value.equals("false") || value.equals("0"))
                return false;
        }
        return ((Boolean) property.getDefaultValue()).booleanValue();
    }

    public synchronized boolean getBooleanProperty(Property property, boolean defaultValue) {
        return getBooleanProperty(property.key(), defaultValue);
    }

    public synchronized boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value != null) {
            value = value.trim();
            if (value.equals("true") || value.equals("1"))
                return true;
            if (value.equals("false") || value.equals("0"))
                return false;
        }
        return defaultValue;
    }

    // Returns true if this property has been set explicitly, as opposed to
    // falling back to its built-in default. Lets a caller tell "the user asked
    // for 12" apart from "nobody said, so 12".
    public synchronized boolean isPropertySet(Property property) {
        return getProperty(property.key()) != null;
    }

    public synchronized int getIntegerProperty(Property property) {
        String value = getProperty(property.key());
        if (value != null) {
            value = value.trim();
            if (value.length() > 0) {
                // Integer.parseInt() doesn't understand a plus sign.
                if (value.charAt(0) == '+')
                    value = value.substring(1).trim();
                try {
                    return Integer.parseInt(value);
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return ((Integer) property.getDefaultValue()).intValue();
    }

    public synchronized Color getColorProperty(String key) {
        String value = getStringProperty(key);
        if (value != null)
            return Colors.parseColor(value);
        return null;
    }

    private String getProperty(String key) {
        return properties.getProperty(key.toLowerCase());
    }

    private static String stripQuotes(String s) {
        final int length = s.length();
        if (length >= 2) {
            if (s.charAt(0) == '"' && s.charAt(length - 1) == '"')
                return s.substring(1, length - 1);
            else if (s.charAt(0) == '\'' && s.charAt(length - 1) == '\'')
                return s.substring(1, length - 1);
        }
        // Not quoted.
        return s.trim();
    }

    public synchronized void addPreferencesChangeListener(PreferencesChangeListener listener) {
        if (listeners == null)
            listeners = new ArrayList<PreferencesChangeListener>();
        listeners.add(listener);
    }

    public synchronized void firePreferencesChanged() {
        if (listeners != null)
            for (PreferencesChangeListener listener : listeners)
                listener.preferencesChanged();
    }

}
