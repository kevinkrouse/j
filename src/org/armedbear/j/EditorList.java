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
import java.util.List;

public final class EditorList implements Collection<Editor> {
    private final ArrayList<Editor> list = new ArrayList<Editor>(4);

    public synchronized int size() {
        return list.size();
    }

    public synchronized boolean isEmpty() {
        return list.isEmpty();
    }

    public synchronized int indexOf(Editor editor) {
        return list.indexOf(editor);
    }

    public synchronized Editor get(int i) {
        if (i >= 0 && i < list.size())
            return list.get(i);
        else
            return null;
    }

    public synchronized boolean add(Editor editor) {
        return add(list.size(), editor);
    }

    private boolean add(int i, Editor editor) {
        if (editor == null || list.contains(editor)) {
            Debug.bug();
            return false;
        }
        list.add(i, editor);
        return true;
    }

    /** Inserts editor after {@code after}, or first if after is null or not listed. */
    public synchronized void addAfter(Editor editor, Editor after) {
        add(after != null ? list.indexOf(after) + 1 : 0, editor);
    }

    public boolean remove(Object o) {
        return remove((Editor) o);
    }

    public synchronized boolean remove(Editor editor) {
        return list.remove(editor);
    }

    public boolean contains(Object o) {
        return contains((Editor) o);
    }

    public synchronized boolean contains(Editor editor) {
        return list.contains(editor);
    }

    /** A snapshot, so the list may change while a caller iterates. */
    public synchronized Iterator<Editor> iterator() {
        return List.copyOf(list).iterator();
    }

    public synchronized boolean addAll(Collection<? extends Editor> c) {
        boolean changed = false;
        for (Editor editor : c)
            changed |= add(editor);
        return changed;
    }

    public synchronized boolean removeAll(Collection<?> c) {
        return list.removeAll(c);
    }

    public synchronized boolean retainAll(Collection<?> c) {
        return list.retainAll(c);
    }

    public synchronized boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    public synchronized void clear() {
        list.clear();
    }

    public synchronized Object[] toArray() {
        return list.toArray();
    }

    public synchronized <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }
}
