/*
 * VerilogMode.java
 *
 * Copyright (C) 2002 Peter Graves
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

package org.armedbear.j.mode.verilog;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.util.regex.Pattern;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Keywords;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.RegexTagger;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;
import org.armedbear.j.util.Utilities;

public final class VerilogMode extends AbstractMode implements Mode {
    private static final Pattern MODULE = Pattern.compile("^module\\s+([a-zA-Z_][a-zA-Z0-9_$]*)");
    private static final Pattern PRIMITIVE = Pattern.compile("^primitive\\s+([a-zA-Z_][a-zA-Z0-9_$]*)");

    public static final String NAME = "Verilog";

    private static volatile VerilogMode mode;

    private VerilogMode(int id) {
        super(id, NAME);
        keywords = new Keywords(this);
    }

    /** Made once, by the mode list, which assigns its id. */
    public static synchronized VerilogMode create(int id) {
        if (mode == null)
            mode = new VerilogMode(id);
        return mode;
    }

    public static VerilogMode getMode() {
        VerilogMode m = mode;
        return m != null ? m : Editor.getModeList().getModeFromModeName(NAME) instanceof VerilogMode x ? x : null;
    }

    @Override
    public String getCommentStart() {
        return "// ";
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new VerilogFormatter(buffer);
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_TAB, CTRL_MASK, "insertTab");
        km.mapKey(KeyEvent.VK_TAB, 0, "tab");
        km.mapKey(KeyEvent.VK_ENTER, 0, "newlineAndIndent");
        km.mapKey(KeyEvent.VK_T, CTRL_MASK, "findTag");
        km.mapKey(KeyEvent.VK_PERIOD, ALT_MASK, "findTagAtDot");
        km.mapKey(KeyEvent.VK_F12, 0, "wrapComment");
    }

    @Override
    public boolean isTaggable() {
        return true;
    }

    @Override
    public Tagger getTagger(SystemBuffer buffer) {
        return new RegexTagger(buffer, MODULE, PRIMITIVE);
    }

    @Override
    public boolean canIndent() {
        return true;
    }

    @Override
    public int getCorrectIndentation(Line line, Buffer buffer) {
        final int indentSize = buffer.getIndentSize();
        final Line model = findModel(line);
        if (model == null)
            return 0;
        final String trim = line.getText().trim();
        final int modelIndent = buffer.getIndentation(model);
        final String modelTrim = model.getText().trim();
        if (line.flags() == STATE_COMMENT) {
            if (modelTrim.startsWith("/*") && trim.startsWith("*"))
                return modelIndent + 1;
            else
                return modelIndent;
        }
        if (modelTrim.endsWith("("))
            return modelIndent + indentSize;
        final String modelIdentifier =
            Utilities.getFirstIdentifier(modelTrim, this);
        if (Utilities.isOneOf(modelIdentifier, alwaysIndentAfter))
            return modelIndent + indentSize;
        if (Utilities.isOneOf(modelIdentifier, maybeIndentAfter)) {
            if (modelTrim.endsWith(";"))
                return modelIndent;
            else
                return modelIndent + indentSize;
        }
        final String identifier = Utilities.getFirstIdentifier(trim, this);
        if ("end".equals(identifier)) {
            Line beginLine = findBeginLine(line);
            if (beginLine != null)
                return buffer.getIndentation(beginLine);
        }
        return modelIndent;
    }

    private static Line findModel(Line line) {
        Line model = line.previous();
        if (line.flags() == STATE_COMMENT) {
            // Any non-blank line is an acceptable model.
            while (model != null && model.isBlank())
                model = model.previous();
        } else {
            while (model != null && !isAcceptableModel(model))
                model = model.previous();
        }
        return model;
    }

    private static boolean isAcceptableModel(Line line) {
        if (line.isBlank())
            return false;
        if (line.flags() == STATE_COMMENT)
            return false;

        return true;
    }

    private Line findBeginLine(Line line) {
        int count = 1;
        while (true) {
            line = line.previous();
            if (line == null)
                return null;
            String identifier = Utilities.getFirstIdentifier(line.trim(), this);
            if (identifier != null) {
                if (identifier.equals("begin")) {
                    if (--count == 0)
                        return line;
                } else if (identifier.equals("end"))
                    ++count;
            }
        }
    }

    @Override
    public boolean isIdentifierStart(char c) {
        return startChars.indexOf(c) >= 0;
    }

    @Override
    public boolean isIdentifierPart(char c) {
        return partChars.indexOf(c) >= 0;
    }

    private static final String startChars =
        "`ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_";

    private static final String partChars =
        "`ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_$";

    private static final String[] alwaysIndentAfter = {
        "begin",
        "case",
        "casex",
        "casez",
        "fork",
        "function",
        "generate",
        "module",
        "primitive",
        "specify",
        "table",
        "task"
    };

    private static final String[] maybeIndentAfter = {
        "always",
        "else",
        "for",
        "forever",
        "if",
        "initial",
        "repeat",
        "while"
    };

    @Override
    public String getWrapCommentStart(String trimmed) {
        return Mode.wrapPrefix(trimmed, "// ", "* ");
    }

}
