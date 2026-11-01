/*
 * DirectoryFormatter.java
 *
 * Copyright (C) 1998-2003 Peter Graves
 * Copyright (C) 2012-2026 Kevin Krouse
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

package org.armedbear.j.mode.dir;

import java.util.regex.Matcher;
import org.armedbear.j.Buffer;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;

public final class DirectoryFormatter extends Formatter {
    // Formats.
    private static final byte DIRECTORY_FORMAT_TEXT = 0;
    private static final byte DIRECTORY_FORMAT_DIRECTORY = 1;
    private static final byte DIRECTORY_FORMAT_SYMLINK = 2;
    private static final byte DIRECTORY_FORMAT_TAGGED = 3;
    private static final byte DIRECTORY_FORMAT_HIDDEN = 4;
    private static final byte DIRECTORY_FORMAT_IGNORED = 5;
    private static final byte DIRECTORY_FORMAT_READ = 6;
    private static final byte DIRECTORY_FORMAT_WRITE = 7;
    private static final byte DIRECTORY_FORMAT_EXECUTE = 8;
    private static final byte DIRECTORY_FORMAT_NO_PERMISSION = 9;
    private static final byte DIRECTORY_FORMAT_SIZE = 10;
    private static final byte DIRECTORY_FORMAT_DATE = 11;

    private final DirectoryBuffer directory;

    public DirectoryFormatter(Buffer buffer) {
        this.buffer = buffer;
        directory = (DirectoryBuffer) buffer;
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        if (line == null || line.length() == 0) {
            addSegment("", DIRECTORY_FORMAT_TEXT);
            return segmentList;
        }
        final String text = line.getText();
        // Where the entry says its columns are, as an age's, which no pattern finds.
        int sizeStart;
        int sizeEnd;
        int nameOffset;
        final DirectoryEntry entry = line instanceof DirectoryLine dl ? dl.getDirectoryEntry() : null;
        if (entry != null && entry.nameColumn() >= 0 && entry.sizeColumn() >= 0) {
            sizeStart = entry.sizeColumn();
            sizeEnd = entry.sizeEndColumn();
            nameOffset = entry.nameColumn();
        } else {
            final Matcher matcher = matcher(text);
            if (!matcher.find()) {
                // The total under the sizes, or an empty listing's line.
                addSegment(text, DIRECTORY_FORMAT_TEXT);
                return segmentList;
            }
            sizeStart = matcher.start();
            sizeEnd = Math.min(text.indexOf(' ', matcher.start()), matcher.end());
            nameOffset = matcher.end();
        }
        final boolean tagged = text.charAt(0) == 'T';
        span(text, 0, 1, tagged ? DIRECTORY_FORMAT_TAGGED : DIRECTORY_FORMAT_TEXT);
        final int modeEnd = formatMode(text, 1, sizeStart);
        span(text, modeEnd, sizeStart, DIRECTORY_FORMAT_TEXT);
        // The size, then the date up to the name.
        span(text, sizeStart, sizeEnd, DIRECTORY_FORMAT_SIZE);
        span(text, sizeEnd, nameOffset, DIRECTORY_FORMAT_DATE);
        formatName(line, text, nameOffset, tagged);
        return segmentList;
    }

    // ls -l's first column, from the first letter after from, before limit:
    // read, write and execute each in their colors, the type as text.
    // Returns where it ends.
    private int formatMode(String text, int from, int limit) {
        int i = from;
        while (i < limit && text.charAt(i) == ' ')
            i++;
        final int start = i;
        int end = text.indexOf(' ', start);
        if (end < 0 || end > limit)
            end = start;
        // Not a mode, as a remote listing's other format: as text.
        if (end - start < 10)
            return from;
        span(text, from, start + 1, DIRECTORY_FORMAT_TEXT);
        for (int k = start + 1; k < start + 10; k++) {
            final byte format = switch (text.charAt(k)) {
                case 'r' -> DIRECTORY_FORMAT_READ;
                case 'w' -> DIRECTORY_FORMAT_WRITE;
                case 'x', 's', 't', 'S', 'T' -> DIRECTORY_FORMAT_EXECUTE;
                default -> DIRECTORY_FORMAT_NO_PERMISSION;
            };
            span(text, k, k + 1, format);
        }
        // An ACL's + or a context's . after it.
        span(text, start + 10, end, DIRECTORY_FORMAT_TEXT);
        return end;
    }

    private void formatName(Line line, String text, int nameOffset, boolean tagged) {
        if (tagged) {
            addSegment(text, nameOffset, DIRECTORY_FORMAT_TAGGED);
            return;
        }
        // A hidden name, shown: muted, and italic if version control ignores it.
        final int hidden = line instanceof DirectoryLine dl ? dl.getDirectoryEntry().getHidden() : HiddenNames.SHOWN;
        if (hidden != HiddenNames.SHOWN) {
            addSegment(
                text,
                nameOffset,
                hidden == HiddenNames.IGNORED ? DIRECTORY_FORMAT_IGNORED : DIRECTORY_FORMAT_HIDDEN);
            return;
        }
        final int index = text.indexOf(" -> ", nameOffset);
        if (index >= 0) {
            span(text, nameOffset, index, DIRECTORY_FORMAT_SYMLINK);
            addSegment(text, index, DIRECTORY_FORMAT_TEXT);
            return;
        }
        final String trim = text.substring(2).trim();
        if (trim.startsWith("d"))
            addSegment(text, nameOffset, DIRECTORY_FORMAT_DIRECTORY);
        else
            addSegment(text, nameOffset, DIRECTORY_FORMAT_TEXT);
    }

    // A segment from begin to end, if that's anything.
    private void span(String text, int begin, int end, int format) {
        if (end > begin)
            addSegment(text, begin, end, format);
    }

    private Matcher matcher(String text) {
        return directory.isUsingNativeFormat()
                ? DirectoryBuffer.getNativeMoveToFilenameRegExp().matcher(text)
                : DirectoryBuffer.getInternalMoveToFilenameRegExp().matcher(text);
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("DirectoryMode");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_DIRECTORY, "directory");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_SYMLINK, "symlink");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_TAGGED, "marked");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_HIDDEN, "hidden");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_IGNORED, "ignored");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_READ, "permissionRead");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_WRITE, "permissionWrite");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_EXECUTE, "permissionExecute");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_NO_PERMISSION, "permissionNone");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_SIZE, "size");
            formatTable.addEntryFromPrefs(DIRECTORY_FORMAT_DATE, "date");
        }
        return formatTable;
    }
}
