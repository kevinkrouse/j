/*
 * Copyright (C) 2012 Kevin Krouse
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
package org.armedbear.j.util;

public class Tuple2<Type1, Type2> implements java.io.Serializable
{
    public Tuple2(Type1 first, Type2 second)
    {
        this.first = first;
        this.second = second;
    }

    public final Type1 first;
    public final Type2 second;

    public boolean equals(Object o)
    {
        if (!(o instanceof Tuple2))
            return false;
        Tuple2 that = (Tuple2) o;
        return (this.first == null ? that.first == null : this.first.equals(that.first))
                && (this.second == null ? that.second == null : this.second.equals(that.second));
    }

    public int hashCode()
    {
        return (first == null ? 0 : first.hashCode()) ^ (second == null ? 0 : second.hashCode());
    }

    public String toString()
    {
        return super.toString() + " (" + String.valueOf(first) + "," + String.valueOf(second) + ")";
    }

    public Tuple2<Type1, Type2> copy()
    {
        return new Tuple2<Type1, Type2>(first, second);
    }

    static public <T1, T2> Tuple2<T1, T2> of(T1 first, T2 second)
    {
        return new Tuple2<T1, T2>(first, second);
    }
}

