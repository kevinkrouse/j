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
 * The log's colors: the subject plain, refs as tinted badges in a smaller
 * font, HEAD's bold, and the author and date dimmed. A branch's badge is in
 * its commit's lane's color, when there is a graph. The gap before the
 * author ends on its column, so that the author and date line up whatever
 * the badges' widths.
 */
final class LogFormatter extends Formatter {
    static final int TEXT = 0;
    static final int DIM = 1;
    static final int HEAD = 2;
    static final int BRANCH = 3;
    static final int REMOTE = 4;
    static final int TAG = 5;
    static final int GAP = 6;
    // A branch's badge in a lane's color, and HEAD's: these plus the color.
    private static final int LANE = 16;
    private static final int HEAD_LANE = LANE + LogGraph.COLORS;

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
        final int lane = l.graph() == null ? -1 : l.graph().color();
        int at = 0;
        for (LogRow.Span s : l.row().spans()) {
            final int start = Math.min(s.start(), text.length());
            final int end = Math.min(s.end(), text.length());
            if (start > at)
                addSegment(text, at, start, s.part() == LogRow.Part.AUTHOR ? GAP : TEXT);
            if (end > start)
                addSegment(text, start, end, format(s.part(), lane));
            at = Math.max(at, end);
        }
        if (at < text.length() || at == 0)
            addSegment(text, at, TEXT);
        return segmentList;
    }

    // A part's format; a branch's in lane's color when it is 0 or more.
    static int format(LogRow.Part part, int lane) {
        return switch (part) {
            case SUBJECT -> TEXT;
            case HEAD -> lane < 0 ? HEAD : HEAD_LANE + lane;
            case BRANCH -> lane < 0 ? BRANCH : LANE + lane;
            case REMOTE -> lane < 0 ? REMOTE : LANE + lane;
            case TAG -> TAG;
            case COUNT, AUTHOR, DATE -> DIM;
        };
    }

    private static boolean isLane(int format) {
        return format >= LANE && format < HEAD_LANE + LogGraph.COLORS;
    }

    @Override
    public Color getColor(int format) {
        if (format == GAP)
            return super.getColor(TEXT);
        return isLane(format) ? LogGraphColumn.color((format - LANE) % LogGraph.COLORS) : super.getColor(format);
    }

    @Override
    public int getStyle(int format) {
        if (isLane(format))
            return TextStyle.SMALL | (format >= HEAD_LANE ? TextStyle.BOLD : TextStyle.PLAIN);
        if (format == HEAD)
            return TextStyle.SMALL | TextStyle.BOLD;
        // The gap is laid out apart, but looks as the text does, with no entry of its own.
        if (format == GAP)
            return super.getStyle(TEXT);
        final int style = super.getStyle(format);
        return isBadge(format) ? style | TextStyle.SMALL : style;
    }

    @Override
    public boolean endsOnColumn(int format) {
        return format == GAP;
    }

    private static boolean isBadge(int format) {
        return format == HEAD || format == BRANCH || format == REMOTE || format == TAG || isLane(format);
    }

    // A badge: a shade of its text's color behind it.
    @Override
    public Color getRunBackground(int format) {
        if (!isBadge(format))
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
