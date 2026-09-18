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
    /**
     * True when this motion is an operator's argument rather than a move.
     *
     * It changes how far the motion may reach: the caret cannot rest past the
     * last character of a line, but an operator can certainly delete it, so
     * {@code l} stops one place short of where {@code dl} does.
     */
    public final boolean forOperator;
    /** The handler running this command, for the few that need it back. */
    public final VimInputHandler handler;

    MotionContext(VimInputHandler handler, Editor editor, VimState state,
                  int count, boolean countGiven, VimCommand command,
                  String character)
    {
        this(handler, editor, state, count, countGiven, command, character,
             false);
    }

    MotionContext(VimInputHandler handler, Editor editor, VimState state,
                  int count, boolean countGiven, VimCommand command,
                  String character, boolean forOperator)
    {
        this.handler = handler;
        this.editor = editor;
        this.state = state;
        this.count = count;
        this.countGiven = countGiven;
        this.command = command;
        this.character = character;
        this.forOperator = forOperator;
    }

    /**
     * The character argument, or 0 if there was none.
     *
     * The raw {@link #character} is a key name, so f&lt; arrives as
     * "&lt;lt&gt;" rather than as "&lt;".
     */
    public char characterArg()
    {
        return KeyNotation.characterOf(character);
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
