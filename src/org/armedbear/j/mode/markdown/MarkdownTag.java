/*
 * MarkdownTag.java
 *
 * Copyright (C) 2026 Kevin Krouse
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

package org.armedbear.j.mode.markdown;

import org.armedbear.j.Line;
import org.armedbear.j.LocalTag;
import org.armedbear.j.Position;

import javax.swing.Icon;

/** A heading, for the sidebar's outline and the status bar. */
public final class MarkdownTag extends LocalTag
{
    private final int level;
    private final MarkdownTag parent;

    MarkdownTag(String name, Line line, int level, MarkdownTag parent)
    {
        super(name, new Position(line, 0), TAG_HEADING);
        this.level = level;
        this.parent = parent;
    }

    /** 1 for a top-level heading, through 6. */
    public int getLevel()
    {
        return level;
    }

    /** The heading this one is under, or null. */
    public MarkdownTag getParent()
    {
        return parent;
    }

    /** The headings down to this one: "Syntaxes › Markdown › Tasks". */
    public String getLongName()
    {
        return parent == null ? name : parent.getLongName() + " › " + name;
    }

    // A heading needs no icon to say what it is.
    public Icon getIcon()
    {
        return null;
    }
}
