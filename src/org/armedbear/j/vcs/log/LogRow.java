/*
 * LogRow.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * A commit's row in the log, laid out for a width: the subject, its refs as
 * badges, then the author and the date at the right. As the width narrows,
 * the tags go first, then the remote branches (local ones and HEAD stay),
 * then the author is cut to initials, and then the subject to an ellipsis.
 * The date never goes.
 *
 * @param text the row's text
 * @param spans what each part of the text is, in order
 * @param cut whether it shows less than the commit has
 */
public record LogRow(String text, List<Span> spans, boolean cut) {
    /** What a part of the row is: the subject, a badge of a ref, the author or the date. */
    public enum Part {
        SUBJECT, HEAD, BRANCH, REMOTE, TAG, AUTHOR, DATE
    }

    /** A part of the row, from start to end (exclusive). */
    public record Span(Part part, int start, int end) {}

    public LogRow {
        spans = List.copyOf(spans);
    }

    /** The row for width columns, now in seconds since the epoch for the date. */
    public static LogRow layout(LogCommit commit, int width, long now) {
        final String date = age(commit.time(), now);
        final List<LogRef> all = badges(commit.refs());
        for (int step = 0;; step++) {
            final List<LogRef> refs = new ArrayList<>();
            for (LogRef r : all)
                if ((step < 1 || r.kind() != LogRef.Kind.TAG) && (step < 2 || r.kind() != LogRef.Kind.REMOTE))
                    refs.add(r);
            final String author = step < 3 ? commit.author() : initials(commit.author());
            if (step == 3 || length(commit.subject(), refs, author, date) <= width)
                return build(
                    commit.subject(),
                    refs,
                    author,
                    date,
                    width,
                    step > 0 && refs.size() < all.size() || step == 3);
        }
    }

    // HEAD's first, then the branches, the remotes and the tags, as they came.
    private static List<LogRef> badges(List<LogRef> refs) {
        final List<LogRef> out = new ArrayList<>();
        for (LogRef r : refs)
            if (r.head())
                out.add(r);
        for (LogRef.Kind kind : List.of(LogRef.Kind.BRANCH, LogRef.Kind.REMOTE, LogRef.Kind.TAG))
            for (LogRef r : refs)
                if (!r.head() && r.kind() == kind)
                    out.add(r);
        return out;
    }

    private static int badge(LogRef r) {
        // A space, then the name with a space either side.
        return 1 + r.name().length() + 2;
    }

    private static int length(String subject, List<LogRef> refs, String author, String date) {
        int n = subject.length();
        for (LogRef r : refs)
            n += badge(r);
        return n + 2 + author.length() + 2 + date.length();
    }

    private static LogRow build(
            String subject,
            List<LogRef> refs,
            String author,
            String date,
            int width,
            boolean less) {
        int badges = 0;
        for (LogRef r : refs)
            badges += badge(r);
        final int right = 2 + author.length() + 2 + date.length();
        // The subject takes what is left; cut, it ends in an ellipsis.
        final int room = Math.max(1, width - badges - right);
        String s = subject;
        boolean cut = less;
        if (s.length() > room) {
            s = room <= 1 ? "…" : s.substring(0, cut(s, room - 1)).stripTrailing() + "…";
            cut = true;
        }
        final List<Span> spans = new ArrayList<>();
        final StringBuilder sb = new StringBuilder(s);
        spans.add(new Span(Part.SUBJECT, 0, s.length()));
        for (LogRef r : refs) {
            sb.append(' ');
            final int start = sb.length();
            sb.append(' ').append(r.name()).append(' ');
            spans.add(new Span(part(r), start, sb.length()));
        }
        sb.append(" ".repeat(Math.max(2, width - sb.length() - (author.length() + 2 + date.length()))));
        spans.add(new Span(Part.AUTHOR, sb.length(), sb.length() + author.length()));
        sb.append(author).append("  ");
        spans.add(new Span(Part.DATE, sb.length(), sb.length() + date.length()));
        sb.append(date);
        return new LogRow(sb.toString(), spans, cut);
    }

    // Where to cut s for n chars, short of n rather than in a surrogate pair.
    private static int cut(String s, int n) {
        return Character.isHighSurrogate(s.charAt(n - 1)) ? n - 1 : n;
    }

    private static Part part(LogRef r) {
        if (r.head())
            return Part.HEAD;
        return switch (r.kind()) {
            case HEAD -> Part.HEAD;
            case BRANCH -> Part.BRANCH;
            case REMOTE -> Part.REMOTE;
            case TAG -> Part.TAG;
        };
    }

    /** "Ada Lovelace" as "AL"; one name as its first letter. */
    static String initials(String name) {
        final StringBuilder sb = new StringBuilder();
        for (String w : name.strip().split("\\s+"))
            if (!w.isEmpty())
                sb.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
        return sb.isEmpty() ? "?" : sb.toString();
    }

    /** How long ago, shortest: "now", "5m", "3h", "2d", "6w", "4mo", "2y". */
    static String age(long time, long now) {
        final long s = Math.max(0, now - time);
        if (s < 60)
            return "now";
        if (s < 3600)
            return s / 60 + "m";
        if (s < 86400)
            return s / 3600 + "h";
        if (s < 86400 * 14)
            return s / 86400 + "d";
        if (s < 86400 * 60)
            return s / (86400 * 7) + "w";
        if (s < 86400 * 365)
            return s / (86400 * 30) + "mo";
        return s / (86400 * 365) + "y";
    }
}
