/*
 * VimCommand.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * One row of a modal key map: a key sequence, and what it means.
 *
 * The shape is CodeMirror's, which is the one that makes a whole vim key map
 * expressible as data: a {@link Kind} says how to run the command, a name says
 * which one, and the arguments are the knobs that let one implementation serve
 * many bindings -- {@code w}, {@code W}, {@code e}, {@code b} and {@code ge}
 * are all the same word motion with different arguments.
 */
public final class VimCommand
{
    /** How the dispatcher should treat a command. */
    public enum Kind
    {
        /** Moves the caret; can also be an operator's argument. */
        MOTION,
        /** Does something that is neither a motion nor an operator. */
        ACTION,
        /** Waits for a motion, then acts on the text it covers. */
        OPERATOR,
        /** An operator with its motion built in, as {@code x} is {@code dl}. */
        OPERATOR_MOTION,
        /** Defines a range directly, as {@code iw} does. */
        TEXT_OBJECT,
        /** Asks for a pattern, then moves to it: / and ?. */
        SEARCH,
        /** Asks for a command line, then runs it: the : commands. */
        EX,
        /** Stands for another key sequence. */
        KEY_TO_KEY,
        /** Runs one of j's own named commands. */
        EDITOR_COMMAND,
        /** Consumes the key and does nothing. */
        IDLE;

        static Kind parse(String s)
        {
            switch (s) {
                case "motion":   return MOTION;
                case "action":   return ACTION;
                case "operator": return OPERATOR;
                case "opmotion": return OPERATOR_MOTION;
                case "textobj":  return TEXT_OBJECT;
                case "search":   return SEARCH;
                case "ex":       return EX;
                case "keytokey": return KEY_TO_KEY;
                case "command":  return EDITOR_COMMAND;
                case "idle":     return IDLE;
                default:
                    throw new IllegalArgumentException("unknown kind: " + s);
            }
        }
    }

    private final Set<MappingMode> modes;
    private final String keys;
    private final Kind kind;
    private final String command;
    private final Map<String, String> args;

    VimCommand(Set<MappingMode> modes, String keys, Kind kind, String command,
               Map<String, String> args)
    {
        this.modes = Collections.unmodifiableSet(modes);
        this.keys = keys;
        this.kind = kind;
        this.command = command;
        this.args = Collections.unmodifiableMap(args);
    }

    public Set<MappingMode> getModes()
    {
        return modes;
    }

    public String getKeys()
    {
        return keys;
    }

    public Kind getKind()
    {
        return kind;
    }

    /**
     * The name of the thing to run: a motion, an action, an operator, or for
     * {@link Kind#KEY_TO_KEY} the key sequence to stand in for.
     */
    public String getCommand()
    {
        return command;
    }

    public boolean getBoolean(String name)
    {
        return Boolean.parseBoolean(args.get(name));
    }

    public int getInt(String name, int defaultValue)
    {
        final String value = args.get(name);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    public String getString(String name, String defaultValue)
    {
        final String value = args.get(name);
        return value == null ? defaultValue : value;
    }

    public Map<String, String> getArgs()
    {
        return args;
    }

    @Override
    public String toString()
    {
        return keys + " -> " + command + " " + args;
    }
}
