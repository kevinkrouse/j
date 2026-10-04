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
import org.armedbear.j.Buffer;

/**
 * What {@code :set} set.
 *
 * Only a handful are consulted so far; the rest are stored so that a vimrc
 * that sets them loads without complaint and they are there when whatever
 * reads them is built. An option j already has its own preference for --
 * shiftwidth against indentSize, say -- falls back to j's when unset, so a
 * user who has configured j once does not have to do it again in vim's words.
 */
public final class VimOptions {
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

    /**
     * The switches read here, with their defaults: nvim's, but for
     * smartcase, which is on here and off in both vim and nvim.
     */
    private static final Map<String, Boolean> SWITCHES =
        new HashMap<String, Boolean>();

    static {
        SWITCHES.put("hlsearch", true);
        SWITCHES.put("incsearch", true);
        SWITCHES.put("ignorecase", false);
        SWITCHES.put("smartcase", true);
        SWITCHES.put("wrapscan", true);
    }

    private final Map<String, String> values = new HashMap<String, String>();

    private static String canonical(String name) {
        final String longName = ALIASES.get(name);
        return longName == null ? name : longName;
    }

    public void set(String name, String value) {
        values.put(canonical(name), value);
    }

    public void toggle(String name) {
        final String key = canonical(name);
        values.put(key, isOn(key) ? "false" : "true");
    }

    /** A switch, set or at its default; see {@link #SWITCHES}. */
    public boolean isOn(String name) {
        final Boolean defaultValue = SWITCHES.get(canonical(name));
        return getBoolean(name, defaultValue != null && defaultValue);
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        final String value = values.get(canonical(name));
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public int getInt(String name, int defaultValue) {
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

    /**
     * What > and < shift by: the vimrc's shiftwidth, or j's indentSize when
     * it sets none. Zero is the tab width, as in vim.
     */
    public static int shiftWidth(Buffer buffer) {
        final int sw = VimKeyMap.getSharedOptions()
            .getInt("shiftwidth", buffer.getIndentSize());
        return sw > 0 ? sw : buffer.getTabWidth();
    }
}
