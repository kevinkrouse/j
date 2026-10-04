/*
 * ModeList.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.armedbear.j.extension.ModeDescriptor;
import org.armedbear.j.extension.ModeProvider;

/** Every mode j knows: core's, then any an extension provides. */
public final class ModeList implements Iterable<ModeListEntry> {
    // Ids for modes that don't have a Constants id start here.
    private static final int FIRST_ASSIGNED_ID = 1000;

    private static ModeList modeList;

    public static synchronized ModeList getInstance() {
        if (modeList == null)
            modeList = new ModeList();
        return modeList;
    }

    private final ArrayList<ModeListEntry> list = new ArrayList<>();
    private int nextId = FIRST_ASSIGNED_ID;

    private ModeList() {
        register(new BuiltinModes());
    }

    /** Adds a provider's modes, skipping one whose id, name or an alias is taken. */
    public synchronized void register(ModeProvider provider) {
        for (ModeDescriptor descriptor : provider.modes()) {
            String taken = taken(descriptor);
            if (taken != null) {
                Log.error("mode " + descriptor.name() + " not registered: " + taken + " is taken");
                continue;
            }
            int id = descriptor.id() > 0 ? descriptor.id() : nextId++;
            list.add(new ModeListEntry(id, descriptor, this));
        }
    }

    private String taken(ModeDescriptor descriptor) {
        if (descriptor.id() > 0 && (descriptor.id() >= FIRST_ASSIGNED_ID || getEntry(descriptor.id()) != null))
            return "id " + descriptor.id();
        if (getEntry(descriptor.name()) != null)
            return descriptor.name();
        for (String alias : descriptor.aliases()) {
            if (getEntry(alias) != null)
                return alias;
        }
        return null;
    }

    public synchronized Mode getMode(int id) {
        final ModeListEntry entry = getEntry(id);
        return entry == null ? null : entry.getMode(true);
    }

    public synchronized boolean modeAccepts(int id, String filename) {
        final ModeListEntry entry = getEntry(id);
        if (entry == null) {
            Debug.bug("ModeList.modeAccepts() invalid mode id " + id);
            return false;
        }
        return entry.accepts(filename);
    }

    /** The mode by its name or an alias, ignoring case. */
    public synchronized Mode getModeFromModeName(String modeName) {
        final ModeListEntry entry = getEntry(modeName);
        return entry == null ? null : entry.getMode(true);
    }

    public synchronized int getModeIdFromModeName(String modeName) {
        final ModeListEntry entry = getEntry(modeName);
        return entry == null ? -1 : entry.getId();
    }

    /** The id of the mode a Markdown fence language names, or -1. */
    public synchronized int getModeIdForFenceName(String fenceName) {
        for (ModeListEntry entry : list) {
            if (entry.getFenceNames().contains(fenceName))
                return entry.getId();
        }
        return -1;
    }

    public synchronized Mode getModeForFileName(String fileName) {
        int id = getModeIdForFileName(fileName);
        return id > 0 ? getMode(id) : null;
    }

    // Later entries win, so an extension's mode can claim core's files.
    public synchronized int getModeIdForFileName(String fileName) {
        if (fileName != null) {
            for (int i = list.size(); i-- > 0;) {
                ModeListEntry entry = list.get(i);
                if (entry.accepts(fileName))
                    return entry.getId();
            }
        }
        return -1;
    }

    // Hard-coded for now.
    public synchronized Mode getModeForContentType(String contentType) {
        if (contentType != null) {
            if (contentType.toLowerCase(Locale.ROOT).startsWith("text/css"))
                return getMode(CSS_MODE);
        }
        return null;
    }

    /** A snapshot, so a mode may be registered while a caller iterates. */
    @Override
    public synchronized Iterator<ModeListEntry> iterator() {
        return List.copyOf(list).iterator();
    }

    private ModeListEntry getEntry(int id) {
        for (ModeListEntry entry : list) {
            if (entry.getId() == id)
                return entry;
        }
        return null;
    }

    private ModeListEntry getEntry(String name) {
        if (name == null)
            return null;
        for (ModeListEntry entry : list) {
            if (name.equalsIgnoreCase(entry.getDisplayName()))
                return entry;
        }
        for (ModeListEntry entry : list) {
            for (String alias : entry.getAliases()) {
                if (name.equalsIgnoreCase(alias))
                    return entry;
            }
        }
        return null;
    }
}
