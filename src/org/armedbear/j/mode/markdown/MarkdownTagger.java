/*
 * MarkdownTagger.java
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

import org.armedbear.j.LocalTag;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tags a Markdown buffer's headings, each knowing the one it is under. */
public final class MarkdownTagger extends Tagger
{
    // An escape, a link or image, or emphasis, strikethrough or code markup.
    private static final Pattern MARKUP = Pattern.compile(
        "\\\\(\\p{Punct})"
        + "|!?\\[([^\\]]*)\\](?:\\([^)]*\\)|\\[[^\\]]*\\])"
        + "|\\*\\*|__|~~|\\*|`|(?<![\\p{L}\\p{N}])_|_(?![\\p{L}\\p{N}])");

    public MarkdownTagger(SystemBuffer buffer)
    {
        super(buffer);
    }

    public void run()
    {
        final List<LocalTag> tags = new ArrayList<LocalTag>();
        final MarkdownTag[] under = new MarkdownTag[7];
        MarkdownFormatter.scan(buffer.getFirstLine(), (line, flags) -> {
            final int level = MarkdownFormatter.headingLevel(line, flags);
            if (level == 0)
                return;
            final String name = plain(MarkdownFormatter.headingText(line, flags));
            if (name.isEmpty())
                return;
            MarkdownTag parent = null;
            for (int i = level - 1; i > 0 && parent == null; i--)
                parent = under[i];
            final MarkdownTag tag = new MarkdownTag(name, line, level, parent);
            tags.add(tag);
            under[level] = tag;
            for (int i = level + 1; i < under.length; i++)
                under[i] = null;
        });
        buffer.setTags(tags);
    }

    /** A heading's text as it reads: links as their text, no markup. */
    static String plain(String text)
    {
        if (text == null)
            return "";
        final Matcher m = MARKUP.matcher(text);
        final StringBuilder sb = new StringBuilder();
        while (m.find()) {
            final String kept = m.group(1) != null ? m.group(1)
                : m.group(2) != null ? plain(m.group(2)) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(kept));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }
}
