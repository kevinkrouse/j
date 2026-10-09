/*
 * ListOccurrencesMode.java
 *
 * Copyright (C) 2000-2005 Peter Graves
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

package org.armedbear.j.mode.list;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import javax.swing.JPopupMenu;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Mode;
import org.armedbear.j.Property;

public final class ListOccurrencesMode extends AbstractMode implements Mode {
    private static final ListOccurrencesMode mode = new ListOccurrencesMode();

    private ListOccurrencesMode() {
        super(LIST_OCCURRENCES_MODE, LIST_OCCURRENCES_MODE_NAME);
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.SHOW_CHANGE_MARKS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
    }

    public static final ListOccurrencesMode getMode() {
        return mode;
    }

    @Override
    public JPopupMenu getContextMenu(Editor editor) {
        return null;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new ListOccurrencesFormatter(buffer);
    }

    @Override
    public String getTransientTag() {
        return "Results";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "findOccurrenceAtDot");
        km.mapKey(KeyEvent.VK_G, CTRL_MASK | SHIFT_MASK, "findOccurrenceAtDot");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "mouseFindOccurrence");
        km.mapKey(VK_MOUSE_2, 0, "mouseFindOccurrence");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "findOccurrenceAtDotAndKillList");
    }
}
