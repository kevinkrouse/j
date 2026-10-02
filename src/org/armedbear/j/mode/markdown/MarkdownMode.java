/*
 * MarkdownMode.java
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

import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Mode;
import org.armedbear.j.Property;

import java.awt.event.KeyEvent;

public final class MarkdownMode extends AbstractMode implements Constants, Mode
{
    private static final MarkdownMode mode = new MarkdownMode();

    private MarkdownMode()
    {
        super(MARKDOWN_MODE, MARKDOWN_MODE_NAME);
        // Brackets in prose are links and task boxes, colored as such.
        setProperty(Property.RAINBOW_DELIMITERS, false);
    }

    public static final MarkdownMode getMode()
    {
        return mode;
    }

    public final Formatter getFormatter(Buffer buffer)
    {
        return new MarkdownFormatter(buffer);
    }

    public String getCommentStart()
    {
        return "<!-- ";
    }

    public String getCommentEnd()
    {
        return " -->";
    }

    protected void setKeyMapDefaults(KeyMap km)
    {
        km.mapKey(KeyEvent.VK_F12, CTRL_MASK | SHIFT_MASK,
                  "wrapParagraphsInRegion");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "task");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK | SHIFT_MASK, "task cancel");
    }
}
