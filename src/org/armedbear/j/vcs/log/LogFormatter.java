/*
 * LogFormatter.java
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

package org.armedbear.j.vcs.log;

import java.awt.Color;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.TextStyle;

/**
 * The log's colors: the subject plain, refs as tinted badges, HEAD's
 * bold, and the author and date dimmed.
 */
final class LogFormatter extends Formatter {
    static final int TEXT = 0;
    static final int DIM = 1;
    static final int HEAD = 2;
    static final int BRANCH = 3;
    static final int REMOTE = 4;
    static final int TAG = 5;

    LogFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        final String text = getDetabbedText(line);
        if (!(line instanceof LogLine l)) {
            addSegment(text, DIM);
            return segmentList;
        }
        int at = 0;
        for (LogRow.Span s : l.row().spans()) {
            final int start = Math.min(s.start(), text.length());
            final int end = Math.min(s.end(), text.length());
            if (start > at)
                addSegment(text, at, start, TEXT);
            if (end > start)
                addSegment(text, start, end, format(s.part()));
            at = Math.max(at, end);
        }
        if (at < text.length() || at == 0)
            addSegment(text, at, TEXT);
        return segmentList;
    }

    private static int format(LogRow.Part part) {
        return switch (part) {
            case SUBJECT -> TEXT;
            case HEAD -> HEAD;
            case BRANCH -> BRANCH;
            case REMOTE -> REMOTE;
            case TAG -> TAG;
            case AUTHOR, DATE -> DIM;
        };
    }

    @Override
    public int getStyle(int format) {
        return format == HEAD ? TextStyle.BOLD : super.getStyle(format);
    }

    // A badge: a shade of its text's color behind it.
    @Override
    public Color getRunBackground(int format) {
        if (format != HEAD && format != BRANCH && format != REMOTE && format != TAG)
            return null;
        return DefaultTheme.blend(getBackgroundColor(), getColor(format), 0.18);
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("VcsLogMode");
            formatTable.addEntryFromPrefs(TEXT, "text");
            formatTable.addEntryFromPrefs(DIM, "comment");
            formatTable.addEntryFromPrefs(HEAD, "head", "keyword");
            formatTable.addEntryFromPrefs(BRANCH, "branch", "function");
            formatTable.addEntryFromPrefs(REMOTE, "remote", "string");
            formatTable.addEntryFromPrefs(TAG, "tag", "type");
        }
        return formatTable;
    }
}
