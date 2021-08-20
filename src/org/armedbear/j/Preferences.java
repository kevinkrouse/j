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

import org.armedbear.j.util.Utilities;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public final class Preferences
{
    private Properties properties = new Properties();
    private ArrayList<PreferencesChangeListener> listeners;

    public static final File getPreferencesFile()
    {
        return File.getInstance(Directories.getEditorDirectory(), "prefs");
    }

    public static void editPrefs()
    {
        File prefs = getPreferencesFile();
        if (prefs == null)
            return;
        final Editor editor = Editor.currentEditor();
        Buffer buf = editor.openFile(prefs);
        if (buf != null)
            editor.activate(buf);
    }

    public synchronized void reload()
    {
        reloadInternal();
        firePreferencesChanged();
    }

    private void reloadInternal()
    {
        File file = getPreferencesFile();
        if (file == null || !file.isFile()) {
            // No preferences file.
            properties = new Properties();
            return;
        }

        // Load preferences file into a temporary Properties object so we can
        // see if the user has specified a theme.
        Properties temp = new Properties();
        try {
            InputStream in = file.getInputStream();
            temp.load(in);
            in.close();
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
    private static Properties canonicalize(Properties properties)
    {
        Properties newProperties = new Properties();
        for (Enumeration e = properties.keys(); e.hasMoreElements();) {
            String key = (String) e.nextElement();
            newProperties.put(key.toLowerCase(), properties.get(key));
        }
        return newProperties;
    }

    // FIXME This is far from ideal (but it does work).
    public synchronized void killTheme()
    {
        Set k = properties.keySet();
        Iterator<String> it = ((Set<String>)k).iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (key.startsWith("color."))
                it.remove();
            else if (key.contains(".color."))
                it.remove();
            else if (key.startsWith("style."))
                it.remove();
            else if (key.contains(".style."))
                it.remove();
        }
    }

    private static Properties loadTheme(String themeName, String themePath)
    {
        Properties properties = new Properties();
        File file = getThemeFile(themeName, themePath);
        if (file != null && file.isFile()) {
            try {
                InputStream in = file.getInputStream();
                properties.load(in);
                in.close();
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
        return canonicalize(properties);
    }

    private static File getThemeFile(String themeName, String themePath)
    {
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

    public synchronized void setProperty(Property property, String value)
    {
        properties.setProperty(property.key(), value);
    }

    public synchronized void setProperty(Property property, int value)
    {
        properties.setProperty(property.key(), String.valueOf(value));
    }

    public synchronized void setProperty(String key, String value)
    {
        properties.setProperty(key.toLowerCase(), value);
    }

    public synchronized void removeProperty(String key)
    {
        properties.remove(key.toLowerCase());
    }

    // Strips quotes if present.
    public synchronized String getStringProperty(Property property)
    {
        String value = getProperty(property.key());
        if (value != null)
            return stripQuotes(value);
        else
            return (String) property.getDefaultValue(); // May be null.
    }

    // Strips quotes if present.
    public synchronized String getStringProperty(String key)
    {
        String value = getProperty(key);
        if (value != null)
            return stripQuotes(value);
        else
            return null;
    }

    public synchronized boolean getBooleanProperty(Property property)
    {
        String value = getProperty(property.key());
        if (value != null) {
            value = value.trim();
            if (value.equals("true") || value.equals("1"))
                return true;
            if (value.equals("false") || value.equals("0"))
                return false;
        }
        return ((Boolean)property.getDefaultValue()).booleanValue();
    }

    public synchronized boolean getBooleanProperty(Property property, boolean defaultValue)
    {
        return getBooleanProperty(property.key(), defaultValue);
    }

    public synchronized boolean getBooleanProperty(String key, boolean defaultValue)
    {
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

    public synchronized int getIntegerProperty(Property property)
    {
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
                catch (NumberFormatException e) {}
            }
        }
        return ((Integer)property.getDefaultValue()).intValue();
    }

    public synchronized Color getColorProperty(String key)
    {
        String value = getStringProperty(key);
        if (value != null)
            return Utilities.getColor(value);
        return null;
    }

    private String getProperty(String key)
    {
        return properties.getProperty(key.toLowerCase());
    }

    private static String stripQuotes(String s)
    {
        final int length = s.length();
        if (length >= 2) {
            if (s.charAt(0) == '"' && s.charAt(length-1) == '"')
                return s.substring(1, length-1);
            else if (s.charAt(0) == '\'' && s.charAt(length-1) == '\'')
                return s.substring(1, length-1);
        }
        // Not quoted.
        return s.trim();
    }

    public synchronized void addPreferencesChangeListener(PreferencesChangeListener listener)
    {
        if (listeners == null)
            listeners = new ArrayList<PreferencesChangeListener>();
        listeners.add(listener);
    }

    public synchronized void firePreferencesChanged()
    {
        if (listeners != null)
            for (PreferencesChangeListener listener : listeners)
                listener.preferencesChanged();
    }

}
