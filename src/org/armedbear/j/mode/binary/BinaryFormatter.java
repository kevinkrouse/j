/*
 * BinaryFormatter.java
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

package org.armedbear.j.mode.binary;

import org.armedbear.j.Buffer;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;

/**
 * Colorizes a hex dump line by byte category
 *
 * A line's layout is fixed by BinaryLine.getText:
 * - an 8-digit offset,
 * - two spaces,
 * - sixteen space-separated hex bytes (columns 10..58),
 * - one space,
 * - the ASCII rendering (column 59)
 */
public final class BinaryFormatter extends Formatter {
    // Byte categories, each its own format.
    private static final int BINARY_NULL = 0;
    private static final int BINARY_PRINTABLE = 1;
    private static final int BINARY_WHITESPACE = 2;
    // Any other ASCII byte < 0x80 besides null: the C0/C1 controls.
    private static final int BINARY_ASCII_OTHER = 3;
    // Any non-ASCII byte > 0x7f.
    private static final int BINARY_NONASCII = 4;
    // The left-hand offset.
    private static final int BINARY_OFFSET = 5;

    public static final int BINARY_FORMAT_LAST = 5;

    // Column layout of a dump line, as BinaryLine.getText builds it.
    public static final int OFFSET_END = 8;
    public static final int HEX_START = 10;
    public static final int HEX_END = 58;
    public static final int ASCII_START = 59;

    public BinaryFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        if (line == null || line.length() == 0) {
            addSegment("", BINARY_OFFSET);
            return segmentList;
        }
        final String text = getDetabbedText(line);
        final int limit = text.length();

        // The offset: the first 8 digits, then the two spaces after it.
        addSegment(text, 0, Math.min(OFFSET_END, limit), BINARY_OFFSET);
        if (limit > OFFSET_END)
            addSegment(text, OFFSET_END, Math.min(HEX_START, limit), BINARY_NULL);

        // The hex column: each byte's two digits in its category's color,
        // and the space after it kept quiet.
        for (int i = 0; i < 16; i++) {
            final int begin = HEX_START + i * 3;
            if (begin >= limit)
                break;
            final int end = Math.min(begin + 2, limit);
            addSegment(text, begin, end, categoryForHex(text, begin, end));
            if (end < limit && end < HEX_END)
                addSegment(text, end, Math.min(end + 1, limit), BINARY_NULL);
        }
        // The separator between the hex and ASCII columns.
        if (limit > HEX_END)
            addSegment(text, HEX_END, Math.min(HEX_END + 1, limit), BINARY_NULL);

        // The ASCII column: each character in the category of the byte it
        // renders. The value comes from the byte's hex digits.
        for (int i = 0; i < 16; i++) {
            final int col = ASCII_START + i;
            if (col >= limit)
                break;
            final int value = byteAt(text, i);
            addSegment(text, col, col + 1, value >= 0 ? category(value) : BINARY_ASCII_OTHER);
        }
        return segmentList;
    }

    // The value of byte i, read from its two hex digits; -1 if the byte
    // is past the line's content (a padded slot).
    private static int byteAt(String text, int i) {
        final int begin = HEX_START + i * 3;
        if (begin + 2 > text.length() || text.charAt(begin) == ' ')
            return -1;
        return Integer.parseInt(text.substring(begin, begin + 2), 16);
    }

    // The category of the hex byte whose two digits are at [begin, end).
    // A padded slot (two spaces) is not a byte; keep it quiet.
    private static int categoryForHex(String text, int begin, int end) {
        if (end - begin < 2 || text.charAt(begin) == ' ')
            return BINARY_NULL;
        return category(Integer.parseInt(text.substring(begin, end), 16));
    }

    // The category of a byte value.
    private static int category(int value) {
        if (value == 0)
            return BINARY_NULL;
        if (value >= 0x21 && value <= 0x7e)
            return BINARY_PRINTABLE;
        if (value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == 0x0b)
            return BINARY_WHITESPACE;
        if (value < 0x80)
            return BINARY_ASCII_OTHER;
        return BINARY_NONASCII;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("BinaryMode");
            formatTable.addEntryFromPrefs(BINARY_NULL, "null");
            formatTable.addEntryFromPrefs(BINARY_PRINTABLE, "printable");
            formatTable.addEntryFromPrefs(BINARY_WHITESPACE, "whitespace");
            formatTable.addEntryFromPrefs(BINARY_ASCII_OTHER, "asciiOther");
            formatTable.addEntryFromPrefs(BINARY_NONASCII, "nonAscii");
            formatTable.addEntryFromPrefs(BINARY_OFFSET, "offset");
        }
        return formatTable;
    }
}
