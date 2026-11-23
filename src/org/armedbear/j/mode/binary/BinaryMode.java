/*
 * BinaryMode.java
 *
 * Copyright (C) 1998-2002 Peter Graves
 * Copyright (C) 2013-2026 Kevin Krouse
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

package org.armedbear.j.mode.binary;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Property;

public final class BinaryMode extends AbstractMode implements Mode {
    private static final BinaryMode mode = new BinaryMode();

    private BinaryMode() {
        super(BINARY_MODE, BINARY_MODE_NAME);
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.RAINBOW_DELIMITERS, false);
    }

    public static final BinaryMode getMode() {
        return mode;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new BinaryFormatter(buffer);
    }

    @Override
    protected final void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_B, CTRL_MASK | ALT_MASK, "defaultMode");
    }

    // For the status bar.
    @Override
    public String getContextString(Editor editor, boolean verbose /*ignored*/) {
        if (editor.getMode() instanceof BinaryMode) {
            final Line dotLine = editor.getDotLine();
            final int len = dotLine.length();
            final int col = editor.getDisplay().getCaretCol();
            // The byte the caret is on: in the hex column, either digit of
            // a byte (not the trailing space); in the ASCII column, the
            // character itself.
            int offset = -1;
            if (col >= BinaryFormatter.HEX_START && col < BinaryFormatter.HEX_END) {
                if ((col - BinaryFormatter.HEX_START) % 3 != 2)
                    offset = (col - BinaryFormatter.HEX_START) / 3;
            } else if (col >= BinaryFormatter.ASCII_START && col < len) {
                offset = col - BinaryFormatter.ASCII_START;
            }
            // The byte must exist on the line, not be a padded slot.
            final int index = BinaryFormatter.HEX_START + offset * 3;
            if (offset >= 0 && offset < 16 && index + 2 <= len && dotLine.charAt(index) != ' ') {
                final String hex = dotLine.substring(index, index + 2);
                StringBuilder sb = new StringBuilder("0x");
                sb.append(dotLine.substring(0, 7));
                sb.append(Integer.toHexString(offset));
                sb.append("  0x");
                sb.append(hex);
                final char c = (char) Integer.parseInt(hex, 16);
                if (c >= ' ' && c < 0x7f) {
                    sb.append("  '");
                    if (c == '\'')
                        sb.append('\\');
                    sb.append(c);
                    sb.append('\'');
                }
                return sb.toString();
            }
        }
        return null;
    }

    public static void binaryMode() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer.isModified()) {
            String prompt = "Buffer will be reloaded in binary mode; discard changes?";
            if (!editor.confirm(buffer.getFile().getName(), prompt))
                return;
        }
        editor.setWaitCursor();
        buffer.changeMode(mode);
        editor.setDefaultCursor();
    }
}
