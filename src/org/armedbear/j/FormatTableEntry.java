/*  
 * FormatTableEntry.java
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

import java.awt.Color;
import java.util.Collections;
import java.util.List;

/*package*/ class FormatTableEntry
{
    private final int format;
    private final Color color;
    private final int style;
    private final String name;
    private final List<String> names;
    private final String colorSource;
    private final String styleSource;

    /**
     * @param names name, then the names it links to and its fallbacks, in
     *     the order they were asked
     * @param colorSource the preference key the color came from, or
     *     "default name" for DefaultTheme's for a name
     * @param styleSource the same for the style, or null if nothing gave one
     *     and it is plain
     */
    /*package*/ FormatTableEntry(int format, Color color, int style,
        String name, List<String> names, String colorSource, String styleSource)
    {
        this.format = format;
        this.color = color;
        this.style = style;
        this.name = name;
        this.names = Collections.unmodifiableList(names);
        this.colorSource = colorSource;
        this.styleSource = styleSource;
    }

    /*package*/ final int getFormat()
    {
        return format;
    }

    /*package*/ final Color getColor()
    {
        return color;
    }

    /*package*/ final int getStyle()
    {
        return style;
    }

    /*package*/ final String getName()
    {
        return name;
    }

    /*package*/ final List<String> getNames()
    {
        return names;
    }

    /*package*/ final String getColorSource()
    {
        return colorSource;
    }

    /*package*/ final String getStyleSource()
    {
        return styleSource;
    }
}
