/*
 * MarkdownLinks.java
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


package org.armedbear.j.mode.markdown;

import org.armedbear.j.Buffer;
import org.armedbear.j.FollowLink;
import org.armedbear.j.Line;
import org.armedbear.j.TextLink;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown's links, for followLink: what the link at the caret points to,
 * and the heading an anchor names.
 */
final class MarkdownLinks
{
    // "[label]: destination", a link reference definition.
    private static final Pattern DEFINITION =
        Pattern.compile("^ {0,3}\\[([^\\]]+)\\]:\\s*(<[^>]*>|\\S+)");

    private MarkdownLinks() {}

    /**
     * The link at offset in line: [text](dest), ![alt](src), [text][ref]
     * and [text][] whether or not ref is defined, [ref] where it is, a
     * definition's own, an autolink or a bare URL; or null.
     */
    static TextLink find(Buffer buffer, Line line, int offset)
    {
        if (MarkdownFormatter.isCode(line))
            return null;
        final String text = line.getText();
        Matcher m = DEFINITION.matcher(text);
        if (m.lookingAt())
            return new TextLink(destination(m.group(2)), m.start(2), m.end(2));
        for (int open = text.indexOf('['); open >= 0;
             open = text.indexOf('[', open + 1)) {
            if (open > 0 && text.charAt(open - 1) == '\\' || inCode(text, open))
                continue;
            final int close = MarkdownFormatter.findClose(text, open, text.length(), '[', ']');
            if (close < 0)
                continue;
            final int start = open > 0 && text.charAt(open - 1) == '!' ? open - 1 : open;
            final char after = close + 1 < text.length() ? text.charAt(close + 1) : 0;
            if (after == '(') {
                final int stop = MarkdownFormatter.findClose(text, close + 1, text.length(), '(', ')');
                if (stop >= 0 && start <= offset && offset <= stop)
                    return new TextLink(destination(text.substring(close + 2, stop)),
                                        start, stop + 1);
            } else if (after == '[') {
                final int stop = text.indexOf(']', close + 2);
                if (stop >= 0 && start <= offset && offset <= stop) {
                    String label = text.substring(close + 2, stop);
                    if (label.trim().isEmpty())
                        label = text.substring(open + 1, close);
                    final String target = definition(buffer, label);
                    return target != null ? new TextLink(target, start, stop + 1)
                        : TextLink.broken("No definition of [" + label + "]",
                                          start, stop + 1);
                }
            } else if (start <= offset && offset <= close) {
                // A shortcut, [ref], is a link only where ref is defined:
                // a task's box looks the same.
                final String target = definition(buffer, text.substring(open + 1, close));
                if (target != null)
                    return new TextLink(target, start, close + 1);
            }
        }
        return FollowLink.urlAt(text, offset);
    }

    // Whether index is in a code span, where brackets are code.
    private static boolean inCode(String text, int index)
    {
        int i = 0;
        while (i < text.length() && i <= index) {
            if (text.charAt(i) != '`') {
                ++i;
                continue;
            }
            int run = 0;
            while (i + run < text.length() && text.charAt(i + run) == '`')
                ++run;
            final int close =
                MarkdownFormatter.findCodeSpanClose(text, i + run, text.length(), run);
            if (close < 0)
                return false;
            if (i < index && index < close + run)
                return true;
            i = close + run;
        }
        return false;
    }

    // "dest", "<dest with spaces>", or "dest \"title\"": the dest.
    private static String destination(String s)
    {
        s = s.trim();
        if (s.startsWith("<")) {
            final int end = s.indexOf('>');
            return end > 0 ? s.substring(1, end) : s.substring(1);
        }
        final int space = s.indexOf(' ');
        return space < 0 ? s : s.substring(0, space);
    }

    // The destination a label is defined as, matched as CommonMark does:
    // whatever the case and however the white space runs.
    private static String definition(Buffer buffer, String label)
    {
        final String wanted = normalize(label);
        if (wanted.isEmpty())
            return null;
        for (Line l = buffer.getFirstLine(); l != null; l = l.next()) {
            final Matcher m = DEFINITION.matcher(l.getText());
            if (m.lookingAt() && !MarkdownFormatter.isCode(l)
                && normalize(m.group(1)).equals(wanted))
                return destination(m.group(2));
        }
        return null;
    }

    private static String normalize(String label)
    {
        return label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
