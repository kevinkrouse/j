/*
 * LogDetailsFormatter.java
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
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.TextStyle;
import org.armedbear.j.vcs.RepositoryColors;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.VcsStatusFormatter;

/**
 * The log's details: as the status buffer's, but for a line of badges at
 * the top, as the list's: the commit's repository's, in its color, when the
 * log has several, then its branches' and tags', a branch's in its lane's
 * color.
 */
final class LogDetailsFormatter extends VcsStatusFormatter {
    // A ref's badge: the list's format, plus this.
    private static final int REF = 100;
    // A repository's: its color's index, plus this.
    private static final int REPOSITORY_BADGE = 200;

    /** A badge's text, from start to end in its line, and its format here. */
    record Badge(String name, int start, int end, int format) {}

    private final LogFilesBuffer files;
    // The list's colors, for the refs'.
    private LogFormatter refs;

    LogDetailsFormatter(LogFilesBuffer files) {
        super(files);
        this.files = files;
    }

    private LogFormatter refs() {
        if (refs == null)
            refs = new LogFormatter(files.getOwner());
        return refs;
    }

    /** c's badges in owner's details: none for a commit of one repository with no refs. */
    static List<Badge> badges(CommitDetails.Host owner, LogCommit c) {
        final List<Badge> out = new ArrayList<>();
        int at = 2;
        if (owner.getScope().isSeveral()) {
            final VcsRepository r = owner.repositoryOf(c);
            final String name = owner.getScope().name(r);
            out.add(new Badge(name, at, at + name.length() + 2, REPOSITORY_BADGE + owner.getScope().colorIndex(r)));
            at += name.length() + 3;
        }
        final int lane = owner.laneColorOf(c);
        for (LogRef ref : LogRow.badges(c.refs())) {
            out.add(
                new Badge(ref.name(),
                        at,
                        at + ref.name().length() + 2,
                        REF + LogFormatter.format(LogRow.part(ref), lane)));
            at += ref.name().length() + 3;
        }
        return out;
    }

    /** The line's text: each badge's name with a space either side, a space apart. */
    static String text(List<Badge> badges) {
        final StringBuilder sb = new StringBuilder("  ");
        for (Badge b : badges) {
            if (sb.length() > 2)
                sb.append(' ');
            sb.append(' ').append(b.name()).append(' ');
        }
        return sb.toString();
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        if (!(line instanceof StatusLine s) || !LogFilesBuffer.BADGES.equals(s.key()))
            return super.formatLine(line);
        clearSegmentList();
        final String text = getDetabbedText(line);
        int at = 0;
        for (Badge b : files.badges()) {
            final int start = Math.min(b.start(), text.length());
            final int end = Math.min(b.end(), text.length());
            if (start > at)
                addSegment(text, at, start, 0);
            if (end > start)
                addSegment(text, start, end, b.format());
            at = Math.max(at, end);
        }
        if (at < text.length() || at == 0)
            addSegment(text, at, 0);
        return segmentList;
    }

    @Override
    public Color getColor(int format) {
        if (format >= REPOSITORY_BADGE)
            return RepositoryColors.color(format - REPOSITORY_BADGE, DefaultTheme.isDark(getBackgroundColor()));
        if (format >= REF)
            return refs().getColor(format - REF);
        return super.getColor(format);
    }

    @Override
    public int getStyle(int format) {
        // Small, as the refs' badges beside it are.
        if (format >= REPOSITORY_BADGE)
            return TextStyle.SMALL;
        if (format >= REF)
            return refs().getStyle(format - REF);
        return super.getStyle(format);
    }

    // A badge: a shade of its text's color behind it, as the list's.
    @Override
    public Color getRunBackground(int format) {
        if (format >= REF)
            return DefaultTheme.blend(getBackgroundColor(), getColor(format), 0.18);
        return super.getRunBackground(format);
    }
}
