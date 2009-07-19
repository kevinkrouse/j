/*
 * Copyright (C) 2009 Kevin Krouse
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

package org.armedbear.j;

public final class StatusFormatter extends Formatter
{
    private static final int STATUS_FORMAT_TEXT = 0;
    private static final int STATUS_FORMAT_NOCHANGE = 1;
    private static final int STATUS_FORMAT_ADDED = 2;
    private static final int STATUS_FORMAT_REMOVED = 3;
    private static final int STATUS_FORMAT_MODIFIED = 4;
    private static final int STATUS_FORMAT_CONFLICT = 5;
    private static final int STATUS_FORMAT_UNKNOWN = 6;
    private static final int STATUS_FORMAT_IGNORED = 7;

    public StatusFormatter(StatusOutputBuffer buffer)
    {
        this.buffer = buffer;
    }

    public StatusOutputBuffer getBuffer()
    {
        return (StatusOutputBuffer)this.buffer;
    }

    public LineSegmentList formatLine(Line line)
    {
        clearSegmentList();
        if (line == null || line.length() == 0) {
            addSegment("", STATUS_FORMAT_TEXT);
            return segmentList;
        }

        switch (getBuffer().getVCType()) {
            case VC_SVN:
                parseSvnLine(line);
                break;
            default:
                throw new IllegalStateException("StatusFormatter not supported on buffer");
        }
        return segmentList;
    }

    private void parseSvnLine(Line line)
    {
        final String text = getDetabbedText(line);
        final char c = text.charAt(0);
        int segment = STATUS_FORMAT_TEXT;
        switch (c)
        {
            case ' ': segment = STATUS_FORMAT_NOCHANGE; break;
            case 'A': segment = STATUS_FORMAT_ADDED;    break;
            case 'D': segment = STATUS_FORMAT_REMOVED;  break;
            case 'M': segment = STATUS_FORMAT_MODIFIED; break;
            case 'C': segment = STATUS_FORMAT_CONFLICT; break;
            case '?': segment = STATUS_FORMAT_UNKNOWN;  break;
            case 'I': segment = STATUS_FORMAT_IGNORED;  break;
        }
        addSegment(text, segment);
    }

    public FormatTable getFormatTable()
    {
        if (formatTable == null) {
            formatTable = new FormatTable("StatusMode");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_ADDED, "added", "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_REMOVED, "deleted", "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_MODIFIED, "changed", "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_CONFLICT, "conflict", "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_UNKNOWN, "unknown", "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_NOCHANGE, "nochange", "text");
            formatTable.addEntryFromPrefs(STATUS_FORMAT_IGNORED, "ignored", "text");
        }
        return formatTable;
    }
}
