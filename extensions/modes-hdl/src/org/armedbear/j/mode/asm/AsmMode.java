/*
 * AsmMode.java
 *
 * Copyright (C) 2003 Peter Graves
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

package org.armedbear.j.mode.asm;

import java.awt.event.KeyEvent;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Property;

public final class AsmMode extends AbstractMode implements Constants, Mode {
    public static final String NAME = "Assembly";

    private static volatile AsmMode mode;

    private AsmMode(int id) {
        super(id, NAME);
        setProperty(Property.INDENT_SIZE, 8);
    }

    /** Made once, by the mode list, which assigns its id. */
    public static synchronized AsmMode create(int id) {
        if (mode == null)
            mode = new AsmMode(id);
        return mode;
    }

    public static AsmMode getMode() {
        AsmMode m = mode;
        return m != null ? m : Editor.getModeList().getModeFromModeName(NAME) instanceof AsmMode x ? x : null;
    }

    public String getCommentStart() {
        return "; ";
    }

    public Formatter getFormatter(Buffer buffer) {
        return new AsmFormatter(buffer);
    }

    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "newlineAndIndent");
        km.mapKey(KeyEvent.VK_F9, 0, "compile");
        km.mapKey(KeyEvent.VK_F9, CTRL_MASK, "recompile");
    }

    public boolean canIndent() {
        return true;
    }

    public boolean canIndentPaste() {
        return false;
    }

    public int getCorrectIndentation(Line line, Buffer buffer) {
        final int indentSize = buffer.getIndentSize();
        final Line model = line.previousNonBlank();
        if (model == null)
            return 0;
        if (model.getText().trim().endsWith(":"))
            return indentSize;
        return buffer.getIndentation(model);
    }

}
