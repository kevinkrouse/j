/*
 * Transient.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.transients;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.armedbear.j.Editor;

/**
 * A popup of a buffer's keys, as magit's transients are: switches and
 * options to set, and commands to run with them, in labeled groups. Data,
 * not a class per popup; {@link TransientPopup} shows one.
 *
 * @param id names its values, kept per buffer and session and saved as
 *     defaults: "dir", "git.log"
 * @param help the help it links to, as Help takes it: "dir.html#keys"
 */
public record Transient(String id, String title, String help, List<Group> groups) {
    public Transient {
        groups = List.copyOf(groups);
        final Set<String> keys = new HashSet<>();
        for (Group g : groups)
            for (Item item : g.items())
                if (!keys.add(item.key()))
                    throw new IllegalArgumentException(id + ": key " + item.key() + " is used twice");
        for (String key : keys)
            for (String other : keys)
                if (!key.equals(other) && !isNamed(other) && other.startsWith(key))
                    throw new IllegalArgumentException(id + ": key " + key + " is the start of " + other);
    }

    public record Group(String label, List<Item> items) {
        public Group {
            items = List.copyOf(items);
        }

        public static Group of(String label, Item... items) {
            return new Group(label, List.of(items));
        }
    }

    /** One line of the popup: a key, what it does, and maybe a value. */
    public sealed interface Item permits Switch, Option, Command, Prefix {
        /** One or two characters: "s", "-a". */
        String key();

        String description();
    }

    /**
     * On or off.
     *
     * @param name what it is called where it is shown on: "--all"
     * @param binding where its value lives, or null for the popup's values
     */
    public record Switch(String key, String name, String description, Binding<Boolean> binding) implements Item {
        public static Switch of(String key, String name, String description) {
            return new Switch(key, name, description, null);
        }
    }

    /**
     * A value, picked by a chooser.
     *
     * @param binding where its value lives, or null for the popup's values
     */
    public record Option(String key, String name, String description, Chooser chooser, Binding<String> binding)
            implements Item {}

    /**
     * Runs a j command, which reads the popup's values with
     * {@link Transients#args}.
     *
     * @param command null for one that can't be run here, shown dimmed;
     *     its argument, if any, after a space
     * @param stays whether the popup stays open after it, as for a filter
     */
    public record Command(String key, String command, String description, boolean stays) implements Item {
        public static Command of(String key, String command, String description) {
            return new Command(key, command, description, false);
        }

        /** A command that can't be run here, description saying why. */
        public static Command unavailable(String key, String description) {
            return new Command(key, null, description, true);
        }

        /** The command's name, without its argument; null if it can't be run. */
        public String name() {
            if (command == null)
                return null;
            final int space = command.indexOf(' ');
            return space < 0 ? command : command.substring(0, space);
        }
    }

    /** Shows another popup in this one's place; Escape comes back. */
    public record Prefix(String key, String description, Supplier<Transient> next) implements Item {}

    /** How an option's value is picked. */
    public sealed interface Chooser permits Cycle, Field, Pick {}

    /** Its key steps through the values, and back to the first. */
    public record Cycle(List<String> values) implements Chooser {
        public Cycle {
            if (values.isEmpty())
                throw new IllegalArgumentException("no values");
            values = List.copyOf(values);
        }
    }

    /**
     * Typed in a field on the option's row; an empty one unsets it.
     *
     * @param history the name of the {@link org.armedbear.j.History} Up and Down step through, or null
     */
    public record Field(String history) implements Chooser {
        public Field() {
            this(null);
        }
    }

    /**
     * Typed in a field on the option's row, with the candidates listed under
     * it, each after a box of its color if swatch gives one.
     *
     * @param several whether it is a checklist: Enter or a click checks or
     *     unchecks a candidate and sets the value, the field kept until
     *     Escape or Enter with none highlighted. Its value is the names
     *     checked, in the candidates' order, joined by {@link #SEPARATOR};
     *     none is unset.
     */
    public record Pick(Function<Editor, List<String>> candidates, BiFunction<Editor, String, Color> swatch,
            boolean several) implements Chooser {
        public static final String SEPARATOR = ", ";

        public Pick(Function<Editor, List<String>> candidates) {
            this(candidates, null, false);
        }

        public Pick(Function<Editor, List<String>> candidates, BiFunction<Editor, String, Color> swatch) {
            this(candidates, swatch, false);
        }

        /** A checklist of candidates. */
        public static Pick several(
                Function<Editor, List<String>> candidates,
                BiFunction<Editor, String, Color> swatch) {
            return new Pick(candidates, swatch, true);
        }

        /** The names in a checklist's value, each once; none for null or blank. */
        public static List<String> split(String value) {
            final List<String> out = new ArrayList<>();
            if (value != null)
                for (String s : value.split(","))
                    if (!s.isBlank() && !out.contains(s.strip()))
                        out.add(s.strip());
            return out;
        }

        /** A checklist's value of names; null for none. */
        public static String join(List<String> names) {
            return names.isEmpty() ? null : String.join(SEPARATOR, names);
        }
    }

    /** Where a value lives when it is not the popup's own: the buffer's sort, say. */
    public interface Binding<T> {
        T get(Editor editor);

        void set(Editor editor, T value);
    }

    // An item's keys that are pressed, not characters typed.

    public static final String BACKSPACE = "<BS>";
    public static final String ENTER = "<cr>";
    public static final String TAB = "<Tab>";

    /** Ctrl with a letter or ;, as {@code <C-r>}. */
    public static String ctrl(char key) {
        return "<C-" + key + ">";
    }

    /** Whether key is pressed, as Backspace and Ctrl R are, not typed. */
    public static boolean isNamed(String key) {
        return key.length() > 2 && key.startsWith("<") && key.endsWith(">");
    }

    /** The item whose key is key, or null. */
    public Item item(String key) {
        for (Group g : groups)
            for (Item item : g.items())
                if (item.key().equals(key))
                    return item;
        return null;
    }

    /** Whether key starts a longer key. */
    public boolean isPrefixOfKey(String key) {
        for (Group g : groups)
            for (Item item : g.items())
                if (item.key().length() > key.length() && item.key().startsWith(key) && !isNamed(item.key()))
                    return true;
        return false;
    }
}
