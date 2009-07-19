/*
 * VersionControlEntry.java
 *
 * Copyright (C) 2009 Kevin Krouse
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

public abstract class VersionControlEntry
{
    protected Buffer buffer;
    protected String revision;

    protected VersionControlEntry(Buffer buffer, String revision)
    {
        this.buffer = buffer;
        this.revision = revision;
    }

    public Buffer getBuffer()
    {
        return buffer;
    }

    public String getRevision()
    {
        return revision;
    }

    public abstract int getVersionControl();

    public abstract String getStatusText();

    public abstract String getLongStatusText();
}
