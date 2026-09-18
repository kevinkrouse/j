/*
 * VimOptions.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.HashMap;
import java.util.Map;

/**
 * What {@code :set} set.
 *
 * Only a handful are consulted so far; the rest are stored so that a vimrc
 * that sets them loads without complaint and they are there when whatever
 * reads them is built. An option j already has its own preference for --
 * shiftwidth against indentSize, say -- falls back to j's when unset, so a
 * user who has configured j once does not have to do it again in vim's words.
 */
public final class VimOptions
{
    /** Short names vim accepts, mapped to the long ones. */
    private static final Map<String, String> ALIASES =
        new HashMap<String, String>();

    static {
        ALIASES.put("sw", "shiftwidth");
        ALIASES.put("ts", "tabstop");
        ALIASES.put("et", "expandtab");
        ALIASES.put("ic", "ignorecase");
        ALIASES.put("scs", "smartcase");
        ALIASES.put("hls", "hlsearch");
        ALIASES.put("is", "incsearch");
        ALIASES.put("ws", "wrapscan");
        ALIASES.put("so", "scrolloff");
        ALIASES.put("tm", "timeoutlen");
    }

    private final Map<String, String> values = new HashMap<String, String>();

    private static String canonical(String name)
    {
        final String longName = ALIASES.get(name);
        return longName == null ? name : longName;
    }

    public void set(String name, String value)
    {
        values.put(canonical(name), value);
    }

    public void toggle(String name)
    {
        final String key = canonical(name);
        values.put(key, Boolean.parseBoolean(values.get(key)) ? "false" : "true");
    }

    public boolean isSet(String name)
    {
        return values.containsKey(canonical(name));
    }

    public boolean getBoolean(String name, boolean defaultValue)
    {
        final String value = values.get(canonical(name));
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public int getInt(String name, int defaultValue)
    {
        final String value = values.get(canonical(name));
        if (value == null)
            return defaultValue;
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getString(String name, String defaultValue)
    {
        final String value = values.get(canonical(name));
        return value == null ? defaultValue : value;
    }

    public void clear()
    {
        values.clear();
    }
}
