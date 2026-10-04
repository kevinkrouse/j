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

import java.awt.datatransfer.Clipboard;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.armedbear.j.Editor;
import org.armedbear.j.KillRing;
import org.armedbear.j.Registers;

/**
 * Vim's registers: what yanks and deletes go into, and what paste comes out of.
 * Most of them are j's own:
 *
 * <ul>
 * <li>the unnamed register is j's kill ring -- its newest text, after taking
 *     the system clipboard as j's paste does -- and {@code "1} to {@code "9}
 *     are its entries, newest first, so vim's y and d and j's copy, cut and
 *     kill are one history;
 * <li>{@code "a} to {@code "z} are j's registers, files under the data
 *     directory ({@link Registers}), so they are there next session;
 * <li>{@code "+} is the system clipboard and {@code "*} the primary selection.
 * </ul>
 *
 * <p>{@code "0}, the last yank, and {@code "-}, the last small delete, are
 * vim mode's own. j keeps text only; a vim register also remembers how the
 * text was taken, which decides how p puts it back -- a line at a time or
 * beside the caret. So a text vim put somewhere is remembered with its type,
 * and any other is taken as lines when it ends in a newline.
 *
 * <p>Global, as in vim: yank in one window, put in another.
 */
public final class VimRegisters {
    /** How the text was taken, which decides how it comes back. */
    public enum Type {
        CHARWISE,
        LINEWISE,
        BLOCKWISE
    }

    public record Register(String text, Type type) {}

    /** The register a command uses when none is named. */
    public static final char UNNAMED = '"';
    /** Where the last yank goes, so a delete cannot clobber it. */
    public static final char YANK = '0';
    /** Deletes of less than a line. */
    public static final char SMALL_DELETE = '-';
    /** Writes here are discarded and reads come back empty. */
    public static final char BLACK_HOLE = '_';
    /** The system clipboard. */
    public static final char CLIPBOARD = '+';
    /** The primary selection, or the clipboard where there is none. */
    public static final char SELECTION = '*';

    /** How many texts' types are remembered. */
    private static final int REMEMBERED = 100;

    private static final VimRegisters INSTANCE = new VimRegisters();

    /** "0 and "-, which only vim mode has. */
    private final Map<Character, Register> own =
        new HashMap<Character, Register>();

    /** How vim took the texts it put in j's registers, newest last. */
    private final Map<String, Type> types =
        new LinkedHashMap<String, Type>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Type> e) {
                return size() > REMEMBERED;
            }
        };

    private VimRegisters() {}

    public static VimRegisters getInstance() {
        return INSTANCE;
    }

    /** Forgets vim mode's own registers and the types, for tests. */
    public void clear() {
        own.clear();
        types.clear();
    }

    public Register get(char name) {
        if (name == BLACK_HOLE)
            return null;
        final char key = Character.toLowerCase(name);
        final String text;
        if (key == UNNAMED) {
            final KillRing ring = Editor.getKillRing();
            ring.takeClipboard();
            text = ring.peek();
        } else if (key >= '1' && key <= '9') {
            final KillRing ring = Editor.getKillRing();
            ring.takeClipboard();
            text = ring.get(ring.size() - (key - '0'));
        } else if (key >= 'a' && key <= 'z') {
            text = Registers.getText(String.valueOf(key));
        } else if (key == CLIPBOARD || key == SELECTION) {
            text = KillRing.getText(clipboard(key));
        } else {
            return own.get(Character.valueOf(key));
        }
        return text == null ? null : new Register(text, typeOf(text));
    }

    /**
     * Stores a yank.
     *
     * An unnamed yank also fills register 0, which is what makes it possible
     * to delete something and still put back what was yanked before it.
     */
    public void yanked(char name, String text, Type type) {
        if (name == BLACK_HOLE)
            return;
        if (name != 0) {
            putNamed(name, text, type);
            return;
        }
        toKillRing(text, type);
        own.put(Character.valueOf(YANK), new Register(text, type));
    }

    /**
     * Stores a delete: into the kill ring, where a delete of a line or more
     * is then "1 and the older ones move down, as in vim. Anything smaller
     * goes to the small delete register as well.
     */
    public void deleted(char name, String text, Type type) {
        if (name == BLACK_HOLE)
            return;
        if (name != 0) {
            putNamed(name, text, type);
            return;
        }
        toKillRing(text, type);
        if (type != Type.LINEWISE && text.indexOf('\n') < 0)
            own.put(Character.valueOf(SMALL_DELETE), new Register(text, type));
    }

    /**
     * Writes a register the user named, and points the unnamed register at
     * it, as vim does: after "add a bare p pastes what went into a. vim
     * leaves "0 and the numbered registers alone when a register is named.
     *
     * An upper case name appends to the lower case one, which is how vim
     * collects several yanks into a single register; the unnamed register
     * then gets the whole of it, not just the part appended.
     */
    private void putNamed(char name, String text, Type type) {
        final char key = Character.toLowerCase(name);
        Register now = new Register(text, type);
        if (Character.isUpperCase(name)) {
            final Register existing = get(key);
            if (existing != null)
                now = appended(existing, now);
        }
        if (key == UNNAMED) {
            toKillRing(now.text, now.type);
            return;
        }
        remember(now);
        if (key >= 'a' && key <= 'z')
            Registers.setText(String.valueOf(key), now.text);
        else if (key == CLIPBOARD || key == SELECTION)
            KillRing.setText(clipboard(key), now.text, null);
        else
            own.put(Character.valueOf(key), now);
        toKillRing(now.text, now.type);
    }

    /**
     * Appending a linewise capture to any register, or anything to an
     * already-linewise one, makes the result linewise; a newline is put at
     * the join (and at the end) so the two captures do not run their lines
     * together.
     */
    private static Register appended(Register existing, Register added) {
        final boolean linewise = existing.type == Type.LINEWISE
            || added.type == Type.LINEWISE;
        String joined = existing.text;
        if (linewise && !joined.isEmpty() && !joined.endsWith("\n"))
            joined += "\n";
        joined += added.text;
        if (linewise && !joined.endsWith("\n"))
            joined += "\n";
        return new Register(joined, linewise ? Type.LINEWISE : added.type);
    }

    /** The unnamed register: j's kill ring, and so the system clipboard. */
    private void toKillRing(String text, Type type) {
        remember(new Register(text, type));
        final KillRing ring = Editor.getKillRing();
        ring.appendNew(text);
        ring.copyLastKillToSystemClipboard();
    }

    private void remember(Register register) {
        types.put(register.text, register.type);
    }

    /** As vim took it, or lines when it ends in a newline. */
    private Type typeOf(String text) {
        final Type type = types.get(text);
        if (type != null)
            return type;
        return text.endsWith("\n") ? Type.LINEWISE : Type.CHARWISE;
    }

    /** "+ is the clipboard; "* the primary selection, else the clipboard. */
    private static Clipboard clipboard(char key) {
        if (key == SELECTION && KillRing.systemSelection() != null)
            return KillRing.systemSelection();
        return KillRing.systemClipboard();
    }

    /** True for a name a command may use after a double quote. */
    public static boolean isValidName(char name) {
        return name == UNNAMED
            || name == SMALL_DELETE
            || name == BLACK_HOLE
            || name == CLIPBOARD
            || name == SELECTION
            || (name >= '0' && name <= '9')
            || (name >= 'a' && name <= 'z')
            || (name >= 'A' && name <= 'Z');
    }
}
