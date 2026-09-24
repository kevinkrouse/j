/*
 * VimRegisters.java
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
 * Vim's registers: what yanks and deletes go into, and what paste comes out of.
 *
 * These are not j's own registers, which are files under the data directory
 * holding one plain string each, and not the kill ring, which is a global list
 * of strings. A vim register also remembers <em>how</em> the text was taken:
 * text yanked a line at a time comes back as whole lines below the caret, and
 * the same characters yanked mid-line come back beside it. Nothing in j records
 * that, so this is new.
 *
 * <p>Global, as in vim: yank in one window, put in another.
 */
public final class VimRegisters
{
    /** How the text was taken, which decides how it comes back. */
    public enum Type
    {
        CHARWISE,
        LINEWISE,
        BLOCKWISE
    }

    public static final class Register
    {
        public final String text;
        public final Type type;

        Register(String text, Type type)
        {
            this.text = text;
            this.type = type;
        }
    }

    /** The register a command uses when none is named. */
    public static final char UNNAMED = '"';
    /** Where the last yank goes, so a delete cannot clobber it. */
    public static final char YANK = '0';
    /** Deletes of less than a line. */
    public static final char SMALL_DELETE = '-';
    /** Writes here are discarded and reads come back empty. */
    public static final char BLACK_HOLE = '_';

    private static final VimRegisters INSTANCE = new VimRegisters();

    private final Map<Character, Register> registers =
        new HashMap<Character, Register>();

    private VimRegisters()
    {
    }

    public static VimRegisters getInstance()
    {
        return INSTANCE;
    }

    /** Empties every register. For tests, which must not leak into each other. */
    public void clear()
    {
        registers.clear();
    }

    public Register get(char name)
    {
        if (name == BLACK_HOLE)
            return null;
        return registers.get(Character.valueOf(Character.toLowerCase(name)));
    }

    /**
     * Stores a yank.
     *
     * An unnamed yank also fills register 0, which is what makes it possible
     * to delete something and still put back what was yanked before it.
     */
    public void yanked(char name, String text, Type type)
    {
        if (name == BLACK_HOLE)
            return;
        if (name != 0) {
            put(name, text, type);
            return;
        }
        put(UNNAMED, text, type);
        put(YANK, text, type);
    }

    /**
     * Stores a delete.
     *
     * Deletes of a line or more shift down through registers 1 to 9, so the
     * last nine are still there; anything smaller goes to the small delete
     * register instead and leaves that history alone.
     */
    public void deleted(char name, String text, Type type)
    {
        if (name == BLACK_HOLE)
            return;
        if (name != 0) {
            put(name, text, type);
            return;
        }
        put(UNNAMED, text, type);
        if (type == Type.LINEWISE || text.indexOf('\n') >= 0) {
            shiftNumbered();
            registers.put(Character.valueOf('1'), new Register(text, type));
        } else {
            put(SMALL_DELETE, text, type);
        }
    }

    private void shiftNumbered()
    {
        for (char c = '9'; c > '1'; c--) {
            final Register r = registers.get(Character.valueOf((char) (c - 1)));
            if (r == null)
                registers.remove(Character.valueOf(c));
            else
                registers.put(Character.valueOf(c), r);
        }
    }

    /**
     * Writes a register by name.
     *
     * An upper case name appends to the lower case one, which is how vim
     * collects several yanks into a single register.
     */
    private void put(char name, String text, Type type)
    {
        final char key = Character.toLowerCase(name);
        if (Character.isUpperCase(name)) {
            final Register existing = registers.get(Character.valueOf(key));
            if (existing != null) {
                // Appending a linewise capture to any register, or appending
                // anything to an already-linewise one, makes the result
                // linewise; a newline is inserted at the join (and at the
                // end) so the two captures do not run their lines together.
                final boolean linewise =
                    existing.type == Type.LINEWISE || type == Type.LINEWISE;
                String joined = existing.text;
                if (linewise && !joined.isEmpty() && !joined.endsWith("\n"))
                    joined += "\n";
                joined += text;
                if (linewise && !joined.endsWith("\n"))
                    joined += "\n";
                registers.put(Character.valueOf(key),
                              new Register(joined,
                                           linewise ? Type.LINEWISE : type));
                return;
            }
        }
        registers.put(Character.valueOf(key), new Register(text, type));
    }

    /** True for a name a command may use after a double quote. */
    public static boolean isValidName(char name)
    {
        return name == UNNAMED || name == SMALL_DELETE || name == BLACK_HOLE
            || (name >= '0' && name <= '9')
            || (name >= 'a' && name <= 'z')
            || (name >= 'A' && name <= 'Z');
    }
}
