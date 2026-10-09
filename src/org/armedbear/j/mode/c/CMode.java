/*
 * CMode.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

package org.armedbear.j.mode.c;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Keywords;
import org.armedbear.j.Line;
import org.armedbear.j.Menu;
import org.armedbear.j.Mode;
import org.armedbear.j.PairMatcher;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;
import org.armedbear.j.mode.compilation.CompilationCommands;
import org.armedbear.j.mode.java.JavaMode;

public class CMode extends JavaMode implements Mode {
    private static final String[] cConditionals = {
        "if",
        "else",
        "do",
        "while",
        "for",
        "switch"
    };

    private CMode() {
        super(C_MODE, C_MODE_NAME);
        keywords = new Keywords(this);
        conditionals = cConditionals;
    }

    protected CMode(int id, String displayName) {
        super(id, displayName);
    }

    // Built on first use, not when CppMode, a subclass, loads this class.
    private static final class Instance {
        static final CMode mode = new CMode();
    }

    public static Mode getMode() {
        return Instance.mode;
    }

    @Override
    public String getCommentStart() {
        return "/*";
    }

    @Override
    public String getCommentEnd() {
        return "*/";
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new CFormatter(buffer, this);
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        super.setKeyMapDefaults(km);
        km.mapKey('#', "electricPound");
        km.mapKey(KeyEvent.VK_F6, CTRL_MASK, "iList");
    }

    @Override
    public void populateModeMenu(Editor editor, Menu menu) {
        menu.add(editor, "Compile...", 'C', "compile");
        menu.add(editor, "Recompile", 'R', "recompile");
        boolean enabled = CompilationCommands.getCompilationBuffer() != null;
        menu.addSeparator();
        menu.add(editor, "Next Error", 'N', "nextError", enabled);
        menu.add(editor, "Previous Error", 'P', "previousError", enabled);
        menu.add(editor, "Show Error Message", 'M', "showMessage", enabled);
    }

    @Override
    public Tagger getTagger(SystemBuffer buffer) {
        return new CTagger(buffer);
    }

    @Override
    public boolean hasQualifiedNames() {
        return false;
    }

    @Override
    public boolean isQualifiedName(String s) {
        return false;
    }

    @Override
    public int getCorrectIndentation(Line line, Buffer buffer) {
        if (line.trim().startsWith("#"))
            return 0; // Preprocessor directive.

        return super.getCorrectIndentation(line, buffer);
    }

    protected static String getPreprocessorToken(Line line) {
        String s = line.trim();
        if (s.length() == 0 || s.charAt(0) != '#')
            return null;
        final int limit = s.length();
        int i;
        for (i = 1; i < limit; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t')
                break;
        }
        StringBuilder sb = new StringBuilder();
        for (; i < limit; i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z')
                sb.append(c);
            else
                break;
        }
        return sb.toString();
    }

    // Used only by findMatchPreprocessor. This needs to persist between calls
    // so we can handle #else/#elif correctly in successive calls.
    private static boolean matchBackwards = false;

    public static Line findMatchPreprocessor(Line startLine) {
        final String patternIf = "if";
        final String patternElse = "el";
        final String patternEndif = "endif";
        String token = null;
        String match = null;
        boolean searchBackwards = false;
        String s = getPreprocessorToken(startLine);
        if (s == null)
            return null;
        if (s.startsWith(patternIf)) {
            token = patternIf;
            match = patternEndif;
            matchBackwards = false;
        } else if (s.startsWith(patternEndif)) {
            token = patternEndif;
            match = patternIf;
            matchBackwards = true;
        } else if (s.startsWith(patternElse)) {
            if (matchBackwards) {
                token = patternEndif;
                match = patternIf;
            } else {
                token = patternIf;
                match = patternEndif;
            }
        } else
            return null;
        int count = 1;
        Line line = startLine;
        while (true) {
            if (matchBackwards)
                line = line.previous();
            else
                line = line.next();
            if (line == null)
                break;
            s = getPreprocessorToken(line);
            if (s != null) {
                if (count == 1 && s.startsWith(patternElse))
                    return line;
                if (s.startsWith(token))
                    ++count;
                else if (s.startsWith(match))
                    --count;
                if (count == 0)
                    return line;
            }
        }
        return null;
    }

    @Override
    public PairMatcher getPairMatcher() {
        return CPairMatcher.INSTANCE;
    }

    @Override
    public boolean isIdentifierStart(char c) {
        if (c >= 'a' && c <= 'z')
            return true;
        if (c >= 'A' && c <= 'Z')
            return true;
        if (c == '_')
            return true;
        return false;
    }

    @Override
    public boolean isIdentifierPart(char c) {
        if (c >= 'a' && c <= 'z')
            return true;
        if (c >= 'A' && c <= 'Z')
            return true;
        if (c >= '0' && c <= '9')
            return true;
        if (c == '_')
            return true;
        return false;
    }
}
