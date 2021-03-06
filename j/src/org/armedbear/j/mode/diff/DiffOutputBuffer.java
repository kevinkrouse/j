/*
 * DiffOutputBuffer.java
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

package org.armedbear.j.mode.diff;

import org.armedbear.j.Buffer;
import org.armedbear.j.File;
import org.armedbear.j.Property;
import org.armedbear.j.vcs.VersionControlBuffer;

public final class DiffOutputBuffer extends VersionControlBuffer
{
    public DiffOutputBuffer(Buffer parentBuffer, String output, int vcType)
    {
        super(parentBuffer, output, vcType);
    }

    public DiffOutputBuffer(File directory, String output, int vcType)
    {
        super(directory, output, vcType);
    }

    protected void init()
    {
        supportsUndo  = false;
        type = TYPE_OUTPUT;
        mode = DiffMode.getMode();
        formatter = new DiffFormatter(this);
        lineSeparator = System.getProperty("line.separator");
        readOnly = true;
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setTransient(true);
        setInitialized(true);
    }
    
}
