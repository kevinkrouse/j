/*
 * VimMode.java
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
 * Which keystrokes mean what, right now.
 *
 * Vim's own mode set is larger than this and the missing ones arrive with the
 * features that need them. The one structural thing vim does that an enum
 * cannot express -- a mode remembering the mode to return to, so that
 * {@code i CTRL-O d/foo} unwinds Insert to Normal to Operator-pending to
 * Command-line correctly -- is kept as a separate field on {@link VimState},
 * because only the CTRL-O family needs it and that is not built yet.
 */
public enum VimMode
{
    NORMAL("NORMAL"),
    INSERT("INSERT"),
    REPLACE("REPLACE"),
    VISUAL("VISUAL"),
    VISUAL_LINE("VISUAL LINE"),
    VISUAL_BLOCK("VISUAL BLOCK"),
    OP_PENDING(null);

    private final String indicator;

    VimMode(String indicator)
    {
        this.indicator = indicator;
    }

    /**
     * What the status bar shows, as in {@code -- INSERT --}, or null for a
     * mode vim does not announce.
     *
     * Operator-pending is not announced: vim shows the partial command
     * instead, which is a different slot.
     */
    public String getIndicator()
    {
        return indicator;
    }

    public boolean isInsert()
    {
        return this == INSERT || this == REPLACE;
    }

    public boolean isVisual()
    {
        return this == VISUAL || this == VISUAL_LINE || this == VISUAL_BLOCK;
    }

    /** True when a keystroke is a command rather than text to insert. */
    public boolean isCommandMode()
    {
        return !isInsert();
    }
}
