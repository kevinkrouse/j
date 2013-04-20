/*
 * BufferIterator.java
 *
 * Copyright (C) 2002 Peter Graves
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

import java.util.Iterator;

public final class BufferIterator implements Iterator<Buffer>
{
    private Iterator<Buffer> it;

    public BufferIterator()
    {
        this(Editor.getBufferList().iterator());
    }

    public BufferIterator(Iterator<Buffer> it)
    {
        Debug.assertTrue(it != null);
        Debug.assertFalse(it instanceof BufferIterator);
        this.it = it;
    }

    public boolean hasNext()
    {
        return it.hasNext();
    }

    public Buffer next()
    {
        return it.next();
    }

    public void remove()
    {
        it.remove();
    }
}
