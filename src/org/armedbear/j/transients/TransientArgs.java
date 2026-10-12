/*
 * TransientArgs.java
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** A popup's own values: the switches that are on, and the options set. */
public final class TransientArgs {
    private final Set<String> on = new LinkedHashSet<>();
    private final Map<String, String> values = new LinkedHashMap<>();

    public synchronized boolean isOn(String name) {
        return on.contains(name);
    }

    public synchronized void setOn(String name, boolean b) {
        if (b)
            on.add(name);
        else
            on.remove(name);
    }

    /** The option's value, or null if it is not set. */
    public synchronized String get(String name) {
        return values.get(name);
    }

    /** Sets the option; null or empty unsets it. */
    public synchronized void set(String name, String value) {
        if (value == null || value.isEmpty())
            values.remove(name);
        else
            values.put(name, value);
    }

    /**
     * As a command line takes them: the switches that are on, then each
     * option set, its name and value together ("--author=" and "kk" make
     * "--author=kk").
     */
    public synchronized List<String> arguments() {
        final List<String> list = new ArrayList<>(on);
        for (Map.Entry<String, String> e : values.entrySet())
            list.add(e.getKey() + e.getValue());
        return list;
    }

    public synchronized TransientArgs copy() {
        final TransientArgs args = new TransientArgs();
        args.on.addAll(on);
        args.values.putAll(values);
        return args;
    }

    // As saved defaults: "<id>.on.<name>=true", "<id>.value.<name>=<value>".
    synchronized void store(String id, Properties p) {
        // Only id's own: "git" must not take "git.log"'s with it.
        p.keySet().removeIf(k -> ((String) k).startsWith(id + ".on.") || ((String) k).startsWith(id + ".value."));
        for (String name : on)
            p.setProperty(id + ".on." + name, "true");
        for (Map.Entry<String, String> e : values.entrySet())
            p.setProperty(id + ".value." + e.getKey(), e.getValue());
    }

    static TransientArgs load(String id, Properties p) {
        final TransientArgs args = new TransientArgs();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(id + ".on."))
                args.setOn(key.substring(id.length() + 4), true);
            else if (key.startsWith(id + ".value."))
                args.set(key.substring(id.length() + 7), p.getProperty(key));
        }
        return args;
    }

    @Override
    public synchronized String toString() {
        return arguments().toString();
    }
}
