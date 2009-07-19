/*
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

public abstract class VersionControlBuffer extends Buffer
{
    private final File directory;
    private final int vcType;

    public VersionControlBuffer(Buffer parentBuffer, String output, int vcType)
    {
        super();
        this.parentBuffer = parentBuffer;
        directory =
            (parentBuffer == null) ? null : parentBuffer.getCurrentDirectory();
        this.vcType = vcType;
        init();
        setText(output);
    }

    public VersionControlBuffer(File directory, String output, int vcType)
    {
        super();
        this.directory = directory;
        this.vcType = vcType;
        init();
        setText(output);
    }

    protected abstract void init();
    
    public final File getCurrentDirectory()
    {
        return directory;
    }

    public final File getDirectory()
    {
        return directory;
    }

    public final int getVCType()
    {
        return vcType;
    }

    public String getFileNameForDisplay()
    {
        return title != null ? title : "";
    }
}
