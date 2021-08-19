/*
 * EditorList.java
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

public final class EditorList implements Collection<Editor>
{
    private final ArrayList<Editor> list = new ArrayList<Editor>(4);

    public synchronized int size()
    {
        return list.size();
    }

    public synchronized boolean isEmpty()
    {
        return list.isEmpty();
    }

    public synchronized int indexOf(Editor editor)
    {
        return list.indexOf(editor);
    }

    public synchronized Editor get(int i)
    {
        if (i >= 0 && i < list.size())
            return list.get(i);
        else
            return null;
    }

    public synchronized boolean add(Editor editor)
    {
        if (editor == null)
            Debug.bug("can't add null editor");
        if (list.contains(editor)) {
            Debug.bug();
            return false;
        }
        list.add(editor);
        return false;
    }

    private void add(int i, Editor editor)
    {
        if (editor == null)
            Debug.bug("can't add null editor");
        if (list.contains(editor)) {
            Debug.bug();
            return;
        }
        list.add(i, editor);
    }

    public synchronized void addAfter(Editor editor, Editor after)
    {
        if (editor == null)
            Debug.bug("can't add null editor");
        if (list.contains(editor)) {
            Debug.bug();
            return;
        }

        int insertAt = 0;
        if (after != null)
        {
            // if after isn't in the list, insert at the head of the list
            insertAt = list.indexOf(after) + 1;
        }
        
        add(insertAt, editor);
    }

    public boolean remove(Object o)
    {
        return remove((Editor)o);
    }

    public synchronized boolean remove(Editor editor)
    {
        return list.remove(editor);
    }

    public boolean contains(Object o)
    {
        return contains((Editor)o);
    }

    public synchronized boolean contains(Editor editor)
    {
        return list.contains(editor);
    }

    // XXX: Callers should be synchronizing on this EditorList while iterating.
    public synchronized Iterator<Editor> iterator()
    {
        return list.iterator();
    }

    public synchronized boolean addAll(Collection<? extends Editor> c)
    {
        return list.addAll(c);
    }

    public synchronized boolean removeAll(Collection<?> c)
    {
        return list.removeAll(c);
    }

    public synchronized boolean retainAll(Collection<?> c)
    {
        return list.retainAll(c);
    }

    public synchronized boolean containsAll(Collection<?> c)
    {
        return list.containsAll(c);
    }

    public synchronized void clear()
    {
        list.clear();
    }

    public synchronized Object[] toArray()
    {
        return list.toArray();
    }

    public synchronized <T> T[] toArray(T[] a)
    {
        return list.toArray(a);
    }
}
