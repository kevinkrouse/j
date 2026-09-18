/*
 * MotionKind.java
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
 * How far an operator reaches when it follows a motion.
 *
 * This is the difference between {@code dw} and {@code de}: the same caret
 * moves to nearly the same place, but {@code w} is exclusive and stops short
 * of where it lands while {@code e} is inclusive and takes the character it
 * lands on. Getting this wrong is what makes an emulation feel almost right.
 */
public enum MotionKind
{
    /** Up to, but not including, where the motion ended. */
    CHARWISE_EXCLUSIVE,
    /** Up to and including where the motion ended. */
    CHARWISE_INCLUSIVE,
    /** Whole lines, however far along them the motion started and ended. */
    LINEWISE;

    /** The kind a binding declares through its arguments. */
    static MotionKind of(VimCommand command)
    {
        if (command.getBoolean("linewise"))
            return LINEWISE;
        return command.getBoolean("inclusive") ? CHARWISE_INCLUSIVE
                                               : CHARWISE_EXCLUSIVE;
    }
}
