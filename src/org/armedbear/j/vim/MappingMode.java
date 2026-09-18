/*
 * MappingMode.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

/**
 * Which key map a binding belongs to.
 *
 * Vim keeps a separate set of bindings per mode, spelled with the letters that
 * prefix its map commands: {@code nmap}, {@code vmap}, {@code omap},
 * {@code imap}. A binding can be in several at once, which is what plain
 * {@code map} means.
 */
public enum MappingMode
{
    NORMAL('n'),
    VISUAL('v'),
    OP_PENDING('o'),
    INSERT('i');

    private final char letter;

    MappingMode(char letter)
    {
        this.letter = letter;
    }

    public char getLetter()
    {
        return letter;
    }

    /** Parses one of the letters used in the key map table and in :map. */
    public static MappingMode forLetter(char letter)
    {
        for (MappingMode mode : values())
            if (mode.letter == letter)
                return mode;
        throw new IllegalArgumentException("no mapping mode '" + letter + "'");
    }

    /** The map to consult while the editor is in this editing mode. */
    public static MappingMode forVimMode(VimMode mode)
    {
        if (mode.isInsert())
            return INSERT;
        if (mode.isVisual())
            return VISUAL;
        if (mode == VimMode.OP_PENDING)
            return OP_PENDING;
        return NORMAL;
    }
}
