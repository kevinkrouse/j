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

public class StatusOutputBuffer extends VersionControlBuffer
{
    public StatusOutputBuffer(Buffer parentBuffer, String output, int vcType)
    {
        super(parentBuffer, output, vcType);
    }

    public StatusOutputBuffer(File directory, String output, int vcType)
    {
        super(directory, output, vcType);
    }

    protected void init()
    {
        supportsUndo = false;
        type = TYPE_OUTPUT;
        mode = StatusMode.getMode();
        formatter = new StatusFormatter(this);
        readOnly = true;
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setTransient(true);
        setInitialized(true);
    }
}
