/*
 * SchemeMode.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

package org.armedbear.j.mode.scheme;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Keywords;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;

public final class SchemeMode extends AbstractMode implements Mode {
    public static final String NAME = "Scheme";

    private static volatile SchemeMode mode;

    private SchemeMode(int id) {
        super(id, NAME);
        keywords = new Keywords(this);
        setProperty(Property.INDENT_SIZE, 2);
        setProperty(Property.HIGHLIGHT_BRACKETS, true);
    }

    /** Made once, by the mode list, which assigns its id. */
    public static synchronized SchemeMode create(int id) {
        if (mode == null)
            mode = new SchemeMode(id);
        return mode;
    }

    public static SchemeMode getMode() {
        SchemeMode m = mode;
        return m != null ? m : Editor.getModeList().getModeFromModeName(NAME) instanceof SchemeMode x ? x : null;
    }

    @Override
    public final String getCommentStart() {
        return "; ";
    }

    @Override
    public final Formatter getFormatter(Buffer buffer) {
        return new SchemeFormatter(buffer);
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "newlineAndIndent");
        km.mapKey(KeyEvent.VK_T, CTRL_MASK, "findTag");
        km.mapKey(KeyEvent.VK_PERIOD, ALT_MASK, "findTagAtDot");
        km.mapKey(KeyEvent.VK_L, CTRL_MASK | SHIFT_MASK, "listTags");
        km.mapKey(')', "closeParen");
    }

    @Override
    public boolean isTaggable() {
        return true;
    }

    @Override
    public Tagger getTagger(SystemBuffer buffer) {
        return new SchemeTagger(buffer);
    }

    private static final String validChars =
        "!$%&*+-./0123456789:<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_abcdefghijklmnopqrstuvwxyz{}~";

    @Override
    public final boolean isIdentifierStart(char c) {
        return validChars.indexOf(c) >= 0;
    }

    @Override
    public final boolean isIdentifierPart(char c) {
        return validChars.indexOf(c) >= 0;
    }

    @Override
    public boolean isInQuote(Buffer buffer, Position pos) {
        // This implementation only considers the current line.
        Line line = pos.getLine();
        int offset = pos.getOffset();
        boolean inQuote = false;
        for (int i = 0; i < offset; i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                // Escape.
                ++i;
            } else if (inQuote) {
                if (c == '"')
                    inQuote = false;
            } else {
                if (c == '"')
                    inQuote = true;
            }
        }
        return inQuote;
    }
}
