/*
 * JavaClass.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

package org.armedbear.j.mode.java;

import org.armedbear.j.Constants;

public final class JavaClass implements Constants
{
    private final String name;
    private final int type;

    public JavaClass(String name, int type)
    {
        this.name = name;
        this.type = type;
    }

    public final String getName()
    {
        return name;
    }

    public final boolean isInterface()
    {
        return type == TAG_INTERFACE;
    }

    public final boolean isClass()
    {
        return type == TAG_CLASS;
    }
}
