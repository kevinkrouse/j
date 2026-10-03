/*
 * TextLink.java
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


package org.armedbear.j;

/**
 * A link in a line's text: where it goes, and where on the line it is,
 * for following it and for underlining it under a Ctrl-hovered mouse.
 */
public final class TextLink
{
    private final String target;
    private final int begin;
    private final int end;
    private final String problem;
    private final boolean definition;

    /**
     * @param target where it goes, as FollowLink takes it: a URL, a path,
     *     "path#anchor" or "#anchor"
     * @param begin the offset in the line it starts at
     * @param end the offset just past it
     */
    public TextLink(String target, int begin, int end)
    {
        this(target, begin, end, null, false);
    }

    /** A link that goes nowhere, and why, as a reference not defined. */
    public static TextLink broken(String problem, int begin, int end)
    {
        return new TextLink(null, begin, end, problem, false);
    }

    /**
     * An identifier that goes to where it is defined, as the tags say: a
     * method called, a class named. Its target is the identifier.
     */
    public static TextLink definition(String name, int begin, int end)
    {
        return new TextLink(name, begin, end, null, true);
    }

    private TextLink(String target, int begin, int end, String problem,
                     boolean definition)
    {
        this.target = target;
        this.begin = begin;
        this.end = end;
        this.problem = problem;
        this.definition = definition;
    }

    /** Whether it goes to its identifier's definition. */
    public boolean isDefinition()
    {
        return definition;
    }

    /** Where it goes, or null if it goes nowhere. */
    public String getTarget()
    {
        return target;
    }

    public int getBegin()
    {
        return begin;
    }

    public int getEnd()
    {
        return end;
    }

    /** Why it goes nowhere, for the status bar, or null. */
    public String getProblem()
    {
        return problem;
    }
}
