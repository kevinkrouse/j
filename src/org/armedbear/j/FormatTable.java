/*
 * FormatTable.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class FormatTable
{
    private static final Preferences preferences = Editor.preferences();

    private String modeName;
    private ArrayList<FormatTableEntry> list;
    private FormatTableEntry[] array;
    private boolean initialized;

    public FormatTable(String modeName)
    {
        this.modeName = modeName;
        list = new ArrayList<FormatTableEntry>();
    }

    public synchronized final String getModeName()
    {
        return modeName;
    }

    public synchronized final void setModeName(String s)
    {
        modeName = s;
    }

    public synchronized FormatTableEntry lookup(int format)
    {
        if (array != null) {
            try {
                return array[format];
            }
            catch (ArrayIndexOutOfBoundsException e) {
                Log.error(e);
                // Fall through...
            }
        }
        if (!initialized) {
            initialized = true;
            boolean ok = true;
            int largest = -1;
            for (int i = list.size()-1; i >= 0; i--) {
                final FormatTableEntry entry = list.get(i);
                final int f = entry.getFormat();
                if (f < 0) {
                    ok = false;
                    break;
                }
                if (f > largest)
                    largest = f;
            }
            if (ok && largest < 128) {
                array = new FormatTableEntry[largest+1];
                for (int i = list.size()-1; i >= 0; i--) {
                    FormatTableEntry entry = list.get(i);
                    array[entry.getFormat()] = entry;
                }
                list = null; // We don't need it any more.
                try {
                    return array[format];
                }
                catch (ArrayIndexOutOfBoundsException e) {
                    Log.error(e);
                    return null;
                }
            } else
                Debug.bug("FormatTableEntry.lookup unable to build array");
        }
        if (list != null) {
            for (int i = list.size()-1; i >= 0; i--) {
                FormatTableEntry entry = list.get(i);
                if (entry.getFormat() == format)
                    return entry;
            }
        }
        return null;
    }

    public synchronized void addEntryFromPrefs(int format, String thing)
    {
        addEntryFromPrefs(format, thing, (String[]) null);
    }

    public synchronized void addEntryFromPrefs(int format, String thing, String fallback)
    {
        addEntryFromPrefs(format, thing,
                          fallback == null ? null : new String[] { fallback });
    }

    /**
     * Gives format the color and style preferences set for thing. What they
     * leave out comes from the names thing links to, then each fallback and
     * what it links to, as an emacs face inherits or an nvim group links: the
     * preferences for every one of those names first, "JavaMode.color.thing"
     * then "color.thing", and only then DefaultTheme's for each.
     *
     * A theme or prefs link a name with "link.thing = other", or for one mode
     * "JavaMode.link.thing = other"; DefaultTheme.getLink says where a name
     * links when they do not.
     */
    public synchronized void addEntryFromPrefs(int format, String thing,
                                               String... fallbacks)
    {
        final List<String> names = new ArrayList<String>();
        addLinked(names, thing);
        if (fallbacks != null)
            for (String fallback : fallbacks)
                addLinked(names, fallback);

        Color color = null;
        String colorSource = null;
        for (String name : names) {
            final Preference<Color> p =
                findPreference("color", name, preferences::getColorProperty);
            if (p != null) {
                color = p.value;
                colorSource = p.key;
                break;
            }
        }
        if (color == null) {
            final boolean dark = DefaultTheme.isDark(getBackground());
            for (String name : names) {
                if ((color = DefaultTheme.getColor(modeName, name, dark)) != null) {
                    colorSource = "default " + name;
                    break;
                }
            }
        }
        // Nothing at all: the theme's text, not DefaultTheme's black on what
        // may be a dark background.
        if (color == null) {
            final Preference<Color> p =
                findPreference("color", "text", preferences::getColorProperty);
            if (p != null) {
                color = p.value;
                colorSource = p.key;
            }
        }
        if (color == null) {
            color = DefaultTheme.getColor("text");
            colorSource = "default text";
        }

        int style = -1;
        String styleSource = null;
        for (String name : names) {
            final Preference<Integer> p = findPreference("style", name, k -> {
                final int parsed = TextStyle.parse(preferences.getStringProperty(k));
                return parsed >= 0 ? parsed : null;
            });
            if (p != null) {
                style = p.value;
                styleSource = p.key;
                break;
            }
        }
        if (style < 0) {
            for (String name : names) {
                if ((style = DefaultTheme.getStyle(modeName, name)) >= 0) {
                    styleSource = "default " + name;
                    break;
                }
            }
        }
        if (style < 0)
            style = TextStyle.PLAIN;

        addEntry(new FormatTableEntry(format, color, style, thing, names,
                                      colorSource, styleSource));
    }

    /**
     * The entries, in order of format, with the names each was resolved
     * through and where its color and style came from: for listStyles.
     */
    /*package*/ synchronized List<FormatTableEntry> getEntries()
    {
        final List<FormatTableEntry> entries = new ArrayList<FormatTableEntry>();
        if (list != null)
            entries.addAll(list);
        else if (array != null)
            for (FormatTableEntry entry : array)
                if (entry != null)
                    entries.add(entry);
        entries.sort((a, b) -> Integer.compare(a.getFormat(), b.getFormat()));
        return entries;
    }

    // The links of a chain are few; more than this is a cycle.
    private static final int MAX_LINKS = 8;

    // Only called from synchronized methods.
    private void addLinked(List<String> names, String name)
    {
        for (int i = 0; name != null && i < MAX_LINKS; i++) {
            if (names.contains(name))
                return;
            names.add(name);
            String link = getPreference("link", name, preferences::getStringProperty);
            name = link != null ? link.trim() : DefaultTheme.getLink(modeName, name);
        }
    }

    // A preference's key and its value.
    private static final class Preference<T>
    {
        final String key;
        final T value;

        Preference(String key, T value)
        {
            this.key = key;
            this.value = value;
        }
    }

    // "JavaMode.color.comment", then "color.comment": the first that get
    // makes a value of, or null.
    private <T> Preference<T> findPreference(String kind, String name,
                                             Function<String, T> get)
    {
        if (modeName != null) {
            final String key = modeName + "." + kind + "." + name;
            final T value = get.apply(key);
            if (value != null)
                return new Preference<T>(key, value);
        }
        final String key = kind + "." + name;
        final T value = get.apply(key);
        return value != null ? new Preference<T>(key, value) : null;
    }

    private <T> T getPreference(String kind, String name, Function<String, T> get)
    {
        final Preference<T> p = findPreference(kind, name, get);
        return p != null ? p.value : null;
    }

    /** Whether the shared styles took their colors for a dark background. */
    /*package*/ synchronized boolean isDarkBackground()
    {
        return DefaultTheme.isDark(getBackground());
    }

    // The background the mode's colors are for, so that a shared style can
    // take its color for a light background or a dark.
    private Color getBackground()
    {
        Color background = getPreference("color", "background",
                                         preferences::getColorProperty);
        return background != null ? background : DefaultTheme.getColor("background");
    }

    // Only called from synchronized methods.
    private void addEntry(FormatTableEntry entry)
    {
        int index = indexOf(entry.getFormat());
        if (index >= 0)
            list.set(index, entry);
        else
            list.add(entry);
    }

    // Only called from synchronized methods.
    private int indexOf(int format)
    {
        for (int i = 0; i < list.size(); i++) {
            FormatTableEntry entry = list.get(i);
            if (entry.getFormat() == format)
                return i;
        }
        return -1;
    }
}
