/*
 * FileHistoryEntry.java
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

package org.armedbear.j;

import java.lang.StringBuilder;

import java.io.Serializable;
import java.util.Iterator;

public final class FileHistoryEntry
{
    private static final String lineSeparator =
        System.getProperty("line.separator");

    private String name;
    private String encoding;
    private String mode;
    private long when;
    private PropertyList properties = new PropertyList();

    public FileHistoryEntry()
    {
    }

    public String toXml()
    {
        StringBuilder sb = new StringBuilder("  <file name=\"");
        sb.append(name);
        sb.append("\"");
        if (encoding != null) {
            sb.append(" encoding=\"");
            sb.append(encoding);
            sb.append("\"");
        }
        sb.append(" mode=\"");
        sb.append(mode);
        sb.append("\"");
        sb.append(" when=\"");
        sb.append(String.valueOf(when));
        sb.append("\"");
        sb.append(">");
        sb.append(lineSeparator);
        Iterator<Property> it = properties.keyIterator();
        if (it != null) {
            while (it.hasNext()) {
                Property property = it.next();
                Object value = properties.getProperty(property);
                sb.append(propertyToXml(property.getDisplayName(),
                    value.toString()));
            }
        }
        sb.append("  </file>");
        return sb.toString();
    }

    private static String propertyToXml(String name, String value)
    {
        StringBuilder sb = new StringBuilder("    <property name=\"");
        sb.append(name);
        sb.append("\" value=\"");
        sb.append(value);
        sb.append("\"/>");
        sb.append(lineSeparator);
        return sb.toString();
    }

    public final PropertyList getProperties()
    {
        return properties;
    }

    public void setProperties(PropertyList props)
    {
        properties = props;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getEncoding()
    {
        return encoding;
    }

    public void setEncoding(String encoding)
    {
        this.encoding = encoding;
    }

    public String getMode()
    {
        return mode;
    }

    public void setMode(String mode)
    {
        this.mode = mode.intern();
    }

    public void setWhen(long when)
    {
        this.when = when;
    }

    public void setProperty(Property property, Serializable value)
    {
        properties.setProperty(property, value);
    }

    public void setProperty(Property property, boolean value)
    {
        properties.setProperty(property, value);
    }

    public void setProperty(Property property, int value)
    {
        properties.setProperty(property, value);
    }

    public boolean setPropertyFromString(Property property, String value)
    {
        return properties.setPropertyFromString(property, value);
    }

    public String getStringProperty(Property property)
    {
        return properties.getStringProperty(property);
    }

    public boolean getBooleanProperty(Property property)
    {
        return properties.getBooleanProperty(property);
    }

    public int getIntegerProperty(Property property)
    {
        return properties.getIntegerProperty(property);
    }
}
