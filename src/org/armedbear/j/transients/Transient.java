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
                if (!key.equals(other) && other.startsWith(key))
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
     * @param stays whether the popup stays open after it, as for a filter
     */
    public record Command(String key, String command, String description, boolean stays) implements Item {
        public static Command of(String key, String command, String description) {
            return new Command(key, command, description, false);
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

    /** Typed in a field on the option's row; an empty one unsets it. */
    public record Field() implements Chooser {}

    /**
     * Typed in a field on the option's row, with the candidates listed under
     * it, each after a box of its color if swatch gives one.
     */
    public record Pick(Function<Editor, List<String>> candidates, BiFunction<Editor, String, Color> swatch)
            implements Chooser {
        public Pick(Function<Editor, List<String>> candidates) {
            this(candidates, null);
        }
    }

    /** Where a value lives when it is not the popup's own: the buffer's sort, say. */
    public interface Binding<T> {
        T get(Editor editor);

        void set(Editor editor, T value);
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
                if (item.key().length() > key.length() && item.key().startsWith(key))
                    return true;
        return false;
    }
}
