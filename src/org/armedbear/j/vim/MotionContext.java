/*
 * MotionContext.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.Editor;

/** What a motion is given besides the position it starts from. */
public final class MotionContext
{
    public final Editor editor;
    public final VimState state;
    /** The count, already defaulted to 1. */
    public final int count;
    /** Whether the user typed the count, which a few motions care about. */
    public final boolean countGiven;
    /** The binding being run, for its arguments. */
    public final VimCommand command;
    /** The key a {@code <character>} placeholder matched, or null. */
    public final String character;

    MotionContext(Editor editor, VimState state, int count, boolean countGiven,
                  VimCommand command, String character)
    {
        this.editor = editor;
        this.state = state;
        this.count = count;
        this.countGiven = countGiven;
        this.command = command;
        this.character = character;
    }

    public boolean arg(String name)
    {
        return command.getBoolean(name);
    }

    public String arg(String name, String defaultValue)
    {
        return command.getString(name, defaultValue);
    }
}
