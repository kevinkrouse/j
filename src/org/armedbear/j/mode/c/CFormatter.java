/*
 * CFormatter.java
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

import org.armedbear.j.Buffer;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.mode.java.JavaFormatter;

/** C, C++ and Objective-C: Java's formatter with preprocessor lines and #if 0 blocks. */
public final class CFormatter extends JavaFormatter {
    private static final int C_FORMAT_PREPROCESSOR = JAVA_FORMAT_LAST + 1;
    private static final int C_FORMAT_DISABLED = JAVA_FORMAT_LAST + 2;

    /** For C, C++ or Objective-C, as mode says. */
    public CFormatter(Buffer buffer, Mode mode) {
        super(buffer);
        setLanguageMode(mode);
    }

    @Override
    protected boolean hasPreprocessor() {
        return true;
    }

    // A string continues only after a backslash.
    @Override
    protected boolean quoteContinues(boolean backslashAtEnd) {
        return backslashAtEnd;
    }

    @Override
    protected boolean isOperatorChar(char c) {
        return "!&|<>=+/*-".indexOf(c) >= 0;
    }

    @Override
    protected int format(int state) {
        return state == STATE_PREPROCESSOR ? C_FORMAT_PREPROCESSOR : super.format(state);
    }

    @Override
    protected void parseLine(Line line) {
        if (line.flags() == STATE_DISABLED)
            addSegment(getDetabbedText(line), C_FORMAT_DISABLED);
        else
            super.parseLine(line);
    }

    // Through the matching #endif, which is disabled too; an #else or #elif
    // is not.
    @Override
    protected Line endOfDisabledBlock(Line line) {
        if (!line.getText().startsWith("#if 0"))
            return line;
        Line match = CMode.findMatchPreprocessor(line);
        if (match != null && match.getText().startsWith("#en"))
            return match.next();
        return match;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            // Shared by C, C++ and Objective-C: CMode.color.* colors all three.
            formatTable = new FormatTable("CMode");
            addEntries(formatTable);
            formatTable.addEntryFromPrefs(C_FORMAT_PREPROCESSOR, "preprocessor");
            formatTable.addEntryFromPrefs(C_FORMAT_DISABLED, "disabled");
        }
        return formatTable;
    }
}
