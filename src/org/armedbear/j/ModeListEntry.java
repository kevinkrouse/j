/*
 * ModeListEntry.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.armedbear.j.extension.ModeDescriptor;

public final class ModeListEntry {
    private final int id;
    private final ModeDescriptor descriptor;
    private final Object lock;
    private final Pattern defaultFiles;
    private Mode mode;
    private String userFiles;
    private Pattern userFilesPattern;

    // The list's lock, so making a mode takes one lock, not two.
    ModeListEntry(int id, ModeDescriptor descriptor, Object lock) {
        this.id = id;
        this.descriptor = descriptor;
        this.lock = lock;
        defaultFiles = descriptor.files() == null ? null : compile(descriptor.files());
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return descriptor.name();
    }

    public boolean isSelectable() {
        return descriptor.selectable();
    }

    List<String> getAliases() {
        return descriptor.aliases();
    }

    List<String> getFenceNames() {
        return descriptor.fenceNames();
    }

    public Mode getMode(boolean create) {
        synchronized (lock) {
            if (mode == null && create) {
                try {
                    mode = descriptor.factory().apply(id);
                }
                catch (Throwable e) {
                    Log.error(e);
                }
            }
            return mode;
        }
    }

    /**
     * Whether the mode edits filename, by the user's JavaMode.files or the
     * default. "mode.java.JavaMode.files", the key that worked while the
     * modes moved into packages, is read too.
     */
    public boolean accepts(String filename) {
        if (defaultFiles == null)
            return false;
        Class<? extends Mode> c = descriptor.modeClass();
        String user = filesPreference(c.getSimpleName());
        if (user == null && c.getName().startsWith("org.armedbear.j."))
            user = filesPreference(c.getName().substring("org.armedbear.j.".length()));
        Pattern files = defaultFiles;
        if (user != null) {
            if (user.trim().isEmpty())
                return false;
            files = userFilesPattern(user);
        }
        return files != null && files.matcher(filename).matches();
    }

    private Pattern userFilesPattern(String user) {
        synchronized (lock) {
            if (!user.equals(userFiles)) {
                userFiles = user;
                userFilesPattern = compile(user);
            }
            return userFilesPattern;
        }
    }

    private static String filesPreference(String prefix) {
        return Editor.preferences().getStringProperty(prefix + "." + Property.FILES.key());
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
        catch (PatternSyntaxException e) {
            Log.error(e);
            return null;
        }
    }

    @Override
    public String toString() {
        return descriptor.name();
    }
}
