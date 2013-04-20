/*
 * EventSequence.java
 *
 * Copyright (C) 2005 Peter Graves
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

import org.armedbear.j.util.FastStringBuffer;

import java.util.ArrayList;

public final class EventSequence
{
    private ArrayList<JEvent> events;

    public EventSequence()
    {
        events = new ArrayList<JEvent>();
    }

    public int size()
    {
        return events.size();
    }

    public EventSequence copy()
    {
        EventSequence copy = new EventSequence();
        for (int i = 0; i < events.size(); i++)
            copy.events.add(events.get(i));
        return copy;
    }

    public void addEvent(JEvent event)
    {
        events.add(event);
    }

    public JEvent getEvent(int index)
    {
        return events.get(index);
    }

    public String getStatusText()
    {
        FastStringBuffer sb = new FastStringBuffer();
        for (int i = 0; i < events.size(); i++) {
            JEvent event = getEvent(i);
            if (i > 0)
                sb.append(' ');
            sb.append(event.getKeyText());
        }
        return sb.toString();
    }

    public String toString()
    {
        FastStringBuffer sb = new FastStringBuffer();
        sb.append("begin EventSequence\n");
        for (JEvent event : events) {
            sb.append(event.toString());
            sb.append('\n');
        }
        sb.append("end EventSequence");
        return sb.toString();
    }
}
