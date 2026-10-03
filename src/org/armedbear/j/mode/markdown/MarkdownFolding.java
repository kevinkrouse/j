/*
 * MarkdownFolding.java
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
import org.armedbear.j.Editor;
import org.armedbear.j.Line;

/**
 * What folding hides in Markdown: a fence's code, a list item's children,
 * or the section under a heading, down to the next heading as high. Folding
 * where one is already folded folds the one around it, so that folding again
 * and again closes outward, as vim's zc does.
 */
public final class MarkdownFolding
{
    private static final Line[] NOTHING = new Line[0];

    private MarkdownFolding() {}

    /**
     * The first and last lines to hide folding at line: the innermost range
     * it is in or heads with a line still to be seen. An empty array if
     * everything around it is folded or there is nothing to fold.
     */
    static Line[] getFoldRange(Buffer buffer, Line line)
    {
        parse(buffer);
        final Line[] fence = fenceBody(line);
        if (isOpen(fence))
            return fence;
        if (isOpen(indentedCode(line)))
            return indentedCode(line);
        Line item = MarkdownFormatter.startsListItem(line.getText())
            && !MarkdownFormatter.isInFence(line)
            ? line : enclosingItem(buffer, line);
        for (; item != null; item = enclosingItem(buffer, item)) {
            final Line[] range = children(buffer, item);
            if (isOpen(range))
                return range;
        }
        for (Line h = headingAt(line); h != null; h = parentHeading(h)) {
            final Line[] range = section(h);
            if (isOpen(range))
                return range;
        }
        return NOTHING;
    }

    // Whether any line of a range is still to be seen.
    private static boolean isOpen(Line[] range)
    {
        if (range == null)
            return false;
        for (Line l = range[0]; l != null; l = l.next()) {
            if (!l.isHidden())
                return true;
            if (l == range[1])
                break;
        }
        return false;
    }

    private static void parse(Buffer buffer)
    {
        if (buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
    }

    private static int level(Line line)
    {
        return MarkdownFormatter.getHeadingLevel(line);
    }

    // The fence's code and the line closing it, or null.
    private static Line[] fenceBody(Line line)
    {
        final Line[] block = MarkdownFormatter.fenceBlock(line);
        if (block == null || block[1] == block[0])
            return null;
        return new Line[] { block[0].next(), block[1] };
    }

    // An indented code block's lines after its first, which stays to show
    // where it is.
    private static Line[] indentedCode(Line line)
    {
        if (!MarkdownFormatter.isIndentedCodeBlock(line))
            return null;
        Line first = line;
        for (Line l = line.previous(); l != null; l = l.previous()) {
            if (MarkdownFormatter.isIndentedCodeBlock(l))
                first = l;
            else if (!l.isBlank())
                break;
        }
        Line last = line;
        for (Line l = line.next(); l != null; l = l.next()) {
            if (MarkdownFormatter.isIndentedCodeBlock(l))
                last = l;
            else if (!l.isBlank())
                break;
        }
        return last == first ? null : new Line[] { first.next(), last };
    }

    // The lines under a list item: those after it indented more than it,
    // with the blank lines among them.
    private static Line[] children(Buffer buffer, Line item)
    {
        final int indent = buffer.getIndentation(item);
        Line last = item;
        for (Line line = item.next(); line != null; line = line.next()) {
            if (line.isBlank())
                continue;
            if (buffer.getIndentation(line) <= indent || level(line) > 0)
                break;
            last = line;
        }
        return last == item ? null : new Line[] { item.next(), last };
    }

    // The list item line is under, or null. A blank line is as far in as
    // the line after it.
    private static Line enclosingItem(Buffer buffer, Line line)
    {
        Line measured = line;
        while (measured != null && measured.isBlank())
            measured = measured.next();
        if (measured == null)
            return null;
        int indent = buffer.getIndentation(measured);
        for (Line l = line.previous(); l != null && indent > 0; l = l.previous()) {
            if (l.isBlank())
                continue;
            if (level(l) > 0)
                return null;
            final int i = buffer.getIndentation(l);
            if (i < indent) {
                if (MarkdownFormatter.startsListItem(l.getText())
                    && !MarkdownFormatter.isInFence(l))
                    return l;
                indent = i;
            }
        }
        return null;
    }

    // The heading line is, or whose underline it is, or the one it is under.
    private static Line headingAt(Line line)
    {
        for (Line l = line; l != null; l = l.previous())
            if (level(l) > 0)
                return l;
        return null;
    }

    private static Line parentHeading(Line heading)
    {
        final int level = level(heading);
        for (Line l = heading.previous(); l != null; l = l.previous()) {
            final int n = level(l);
            if (n > 0 && n < level)
                return l;
        }
        return null;
    }

    // Everything under a heading down to the next as high, but the blank
    // lines before that, which keep the headings apart when folded.
    private static Line[] section(Line heading)
    {
        final int level = level(heading);
        Line first = heading.next();
        if (first != null && MarkdownFormatter.isSetextHeading(heading))
            first = first.next();
        if (first == null)
            return null;
        Line last = null;
        for (Line l = first; l != null; l = l.next()) {
            final int n = level(l);
            if (n > 0 && n <= level)
                break;
            last = l;
        }
        while (last != null && last != first.previous() && last.isBlank())
            last = last.previous();
        return last == null || last == first.previous() ? null
            : new Line[] { first, last };
    }

    public static void foldHeadings()
    {
        foldHeadings(null);
    }

    /**
     * Folds all but the headings down to a level, 1-6, all of them if none
     * is given: an outline of the document.
     */
    public static void foldHeadings(String arg)
    {
        final Editor editor = Editor.currentEditor();
        int depth = 6;
        if (arg != null && !arg.trim().isEmpty()) {
            try {
                depth = Integer.parseInt(arg.trim());
            }
            catch (NumberFormatException e) {
                depth = 0;
            }
            if (depth < 1 || depth > 6) {
                editor.status("foldHeadings: expected a level from 1 to 6");
                return;
            }
        }
        foldHeadings(editor, depth);
    }

    static void foldHeadings(Editor editor, int depth)
    {
        final Buffer buffer = editor.getBuffer();
        // Elsewhere a line beginning with # is a comment, not a heading.
        if (!(buffer.getMode() instanceof MarkdownMode)) {
            editor.status("foldHeadings: only in Markdown mode");
            return;
        }
        parse(buffer);
        Line first = buffer.getFirstLine();
        while (first != null && level(first) == 0)
            first = first.next();
        if (first == null) {
            editor.status("No headings");
            return;
        }
        // What comes before the first heading stays: there is nothing above
        // it to fold it into.
        editor.showOnly(first, line -> {
            final int n = level(line);
            return n > 0 && n <= depth
                || line.previous() != null
                   && MarkdownFormatter.isSetextHeading(line.previous())
                   && level(line.previous()) <= depth;
        });
    }
}
