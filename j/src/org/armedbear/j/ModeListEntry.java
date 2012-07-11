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

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.lang.reflect.Method;

public final class ModeListEntry
{
    private final int id;
    private final String displayName;
    private final String className;
    private final boolean selectable;
    private final String defaultFiles;
    private Mode mode;

    public ModeListEntry(int id, String displayName, String className,
        boolean selectable, String defaultFiles)
    {
        this.id = id;
        this.displayName = displayName;
        this.className = className;
        this.selectable = selectable;
        this.defaultFiles = defaultFiles;
    }

    public final int getId()
    {
        return id;
    }

    public final String getDisplayName()
    {
        return displayName;
    }

    public final String getClassName()
    {
        return className;
    }

    public final boolean isSelectable()
    {
        return selectable;
    }

    public Mode getMode(boolean create)
    {
        if (mode == null && create) {
            if (className != null) {
                try {
                    Class c = Class.forName("org.armedbear.j.".concat(className));
                    Method method = c.getMethod("getMode", new Class[0]);
                    mode = (Mode) method.invoke(null, new Object[0]);
                }
                catch (Throwable t) {
                    Log.error(t);
                }
            }
        }
        return mode;
    }

    public boolean accepts(String filename)
    {
        if (defaultFiles == null)
            return false;
        final String key = className.concat(".").concat(Property.FILES.key());
        final String userFiles = Editor.preferences().getStringProperty(key);
        Pattern filesRE = null;
        if (userFiles != null) {
            if (userFiles.trim().length() == 0)
                return false;
            try {
                filesRE = Pattern.compile(userFiles, Pattern.CASE_INSENSITIVE);
            }
            catch (PatternSyntaxException e) {
                Log.error(e);
            }
        } else {
            try {
                filesRE = Pattern.compile(defaultFiles, Pattern.CASE_INSENSITIVE);
            }
            catch (PatternSyntaxException e) {
                Log.error(e);
            }
        }
        if (filesRE != null && filesRE.matcher(filename).matches())
            return true;
        else
            return false;
    }

    public String toString()
    {
        return displayName;
    }
}
