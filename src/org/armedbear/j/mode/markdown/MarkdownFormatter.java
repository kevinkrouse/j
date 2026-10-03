/*
 * MarkdownFormatter.java
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
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Log;
import org.armedbear.j.Mode;
import org.armedbear.j.TextLine;
import org.armedbear.j.TextStyle;

import java.awt.Color;

import java.util.Arrays;
import java.util.function.ObjIntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colors Markdown, CommonMark with GitHub's tables, task lists and
 * strikethrough, a line at a time.
 *
 * parseBuffer leaves in each line's flags what a line cannot know from its
 * own text: whether it begins inside a fence, an HTML comment or front
 * matter, and for the text of a setext heading, its level. formatLine then
 * colors the line's block structure -- headings, quotes, list items, task
 * boxes, rules, tables -- and the inline markup in what is left.
 */
public final class MarkdownFormatter extends Formatter
{
    static final int TEXT               = 0;
    static final int HEADING_MARKER     = 1;
    static final int HEADING_1          = 2; // through HEADING_1 + 5
    static final int CODE               = 8;
    static final int CODE_BLOCK         = 9;
    static final int FENCE              = 10;
    static final int LINK_TEXT          = 11;
    static final int URL                = 12;
    static final int MARKUP             = 13;
    static final int EMPHASIS           = 14;
    static final int STRONG             = 15;
    static final int STRONG_EMPHASIS    = 16;
    static final int STRIKETHROUGH      = 17;
    static final int QUOTE              = 18;
    static final int QUOTE_MARKER       = 19;
    static final int LIST_MARKER        = 20;
    static final int RULE               = 21;
    static final int TODO               = 22;
    static final int IN_PROGRESS        = 23;
    static final int IN_PROGRESS_MARKER = 24;
    static final int DONE               = 25;
    static final int DONE_TEXT          = 26;
    static final int CANCELLED          = 27;
    static final int CANCELLED_TEXT     = 28;
    static final int COMMENT            = 29;
    static final int HTML_TAG           = 30;
    static final int FRONT_MATTER       = 31;
    static final int CODE_MARKER        = 32; // A code span's backticks.

    // A line's flags: the block it begins in, in the low bits...
    private static final int BLOCK_MASK      = 0x7;
    private static final int NORMAL          = 0;
    private static final int IN_FENCE        = 1;
    private static final int IN_COMMENT      = 2;
    private static final int IN_FRONT_MATTER = 3;
    // ...the level of a heading's text, 1-6...
    private static final int HEADING_SHIFT = 3;
    private static final int HEADING_MASK  = 0x7 << HEADING_SHIFT;
    // ...and in a fence, how it was opened, so that only a like fence closes
    // it: with tildes or backticks, and how many (up to 31; more close
    // with 31).
    private static final int FENCE_TILDE        = 1 << 6;
    private static final int FENCE_LENGTH_SHIFT = 7;
    private static final int FENCE_LENGTH_MASK  = 0x1f << FENCE_LENGTH_SHIFT;
    // ...and the language its info string names, as a FenceLanguages slot.
    private static final int LANGUAGE_SHIFT = 12;
    private static final int LANGUAGE_MASK  = FenceLanguages.MAX_SLOT << LANGUAGE_SHIFT;

    // The format of a fenced line's character that the language's formatter
    // colored: its slot and its own format. Display keeps a formatter's
    // formats below bit 20 when it colors brackets.
    private static final int EMBED            = 1 << 19;
    private static final int EMBED_SLOT_SHIFT = 12;
    private static final int EMBED_FORMAT     = (1 << EMBED_SLOT_SHIFT) - 1;

    private static final Pattern FENCE_OPEN =
        Pattern.compile("^ {0,3}(`{3,}(?=[^`]*$)|~{3,}).*$");
    private static final Pattern FENCE_CLOSE =
        Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*$");
    private static final Pattern ATX_HEADING =
        Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))??(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern SETEXT_UNDERLINE =
        Pattern.compile("^ {0,3}(=+|-+)\\s*$");
    private static final Pattern RULE_LINE =
        Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern QUOTE_PREFIX =
        Pattern.compile("^(?: {0,3}>[ \\t]?)+");
    private static final Pattern LIST_ITEM =
        Pattern.compile("[ \\t]*([-*+]|\\d{1,9}[.)])(?=[ \\t]|$)[ \\t]*");
    private static final Pattern TASK_BOX =
        Pattern.compile("\\[([ xX/-])\\](?=[ \\t]|$)");
    private static final Pattern TABLE_DELIMITER_ROW =
        Pattern.compile("^ {0,3}\\|?(?:[ \\t]*:?-+:?[ \\t]*\\|)+(?:[ \\t]*:?-+:?[ \\t]*)?$");
    private static final Pattern LINK_DEFINITION =
        Pattern.compile("^ {0,3}(\\[)([^\\]]+)(\\]:)[ \\t]*(\\S+)");
    private static final Pattern AUTOLINK =
        Pattern.compile("<(?:[a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^\\s<>]*|[^\\s<>@]+@[^\\s<>]+)>");
    private static final Pattern HTML_TAG_PATTERN =
        Pattern.compile("</?[a-zA-Z][a-zA-Z0-9-]*(?:\\s[^<>]*)?/?>");
    private static final Pattern BARE_URL =
        Pattern.compile("https?://[^\\s<>()\\[\\]]*[^\\s<>()\\[\\].,;:!?'\"]");

    // The format of each character of the line being formatted, whether it
    // is markup to hide, and the item it is part of.
    private int[] formats = new int[0];
    private boolean[] hidden = new boolean[0];
    private boolean[] bars = new boolean[0];
    private int[] items = new int[0];
    private int itemCount;

    // What markup to hide until the caret is in it, as Property.CONCEAL
    // names it: "markup", links, emphasis, code, escapes and fences; and
    // "headings", their markers.
    private boolean concealMarkup;
    private boolean concealHeadings;

    // A formatter for each fence language met, by slot, lent this buffer.
    private final Formatter[] languages = new Formatter[FenceLanguages.MAX_SLOT + 1];

    public MarkdownFormatter(Buffer buffer)
    {
        this.buffer = buffer;
    }

    /** The level of the heading on line, 1-6, or 0 if it is not one. */
    public static int getHeadingLevel(Line line)
    {
        return headingLevel(line, line.flags());
    }

    /** The level of the heading on line had it flags. */
    static int headingLevel(Line line, int flags)
    {
        if ((flags & BLOCK_MASK) != NORMAL)
            return 0;
        final int level = (flags & HEADING_MASK) >> HEADING_SHIFT;
        if (level > 0)
            return level;
        final Matcher m = ATX_HEADING.matcher(line.getText());
        return m.matches() ? m.group(1).length() : 0;
    }

    /**
     * The text of the heading on line, without its markers, or null if
     * there is none.
     */
    static String headingText(Line line, int flags)
    {
        if ((flags & BLOCK_MASK) != NORMAL)
            return null;
        if ((flags & HEADING_MASK) != 0)
            return line.getText().trim();
        final Matcher m = ATX_HEADING.matcher(line.getText());
        if (!m.matches())
            return null;
        return m.group(2) != null ? m.group(2) : "";
    }

    /** Whether line is in a fence, or closes one. */
    static boolean isInFence(Line line)
    {
        return (line.flags() & BLOCK_MASK) == IN_FENCE;
    }

    /** Whether line opens a fence. */
    static boolean opensFence(Line line)
    {
        return (line.flags() & BLOCK_MASK) == NORMAL
            && FENCE_OPEN.matcher(line.getText()).matches();
    }

    /** Whether line is the text of a heading underlined on the next. */
    static boolean isSetextHeading(Line line)
    {
        return (line.flags() & BLOCK_MASK) == NORMAL
            && (line.flags() & HEADING_MASK) != 0;
    }

    /** Whether text begins with a list marker. */
    static boolean startsListItem(String text)
    {
        return LIST_ITEM.matcher(text).lookingAt();
    }

    /** Whether line is in a fence, or opens or closes one. */
    public static boolean isCode(Line line)
    {
        return (line.flags() & BLOCK_MASK) == IN_FENCE
            || ((line.flags() & BLOCK_MASK) == NORMAL
                && FENCE_OPEN.matcher(line.getText()).matches());
    }

    public boolean parseBuffer()
    {
        final boolean[] changed = { false };
        scan(buffer.getFirstLine(), (line, flags) -> {
            if (line.flags() != flags) {
                line.setFlags(flags);
                changed[0] = true;
            }
        });
        buffer.setNeedsParsing(false);
        return changed[0];
    }

    /**
     * Gives sink each line from first on with the flags parseBuffer keeps
     * for it, without keeping them, for those that cannot wait for it.
     */
    static void scan(Line first, ObjIntConsumer<Line> sink)
    {
        int block = NORMAL;
        int fence = 0;
        for (Line line = first; line != null; line = line.next()) {
            final String text = line.getText();
            int flags = block;
            int next = block;
            switch (block) {
                case IN_FRONT_MATTER:
                    if (text.equals("---") || text.equals("..."))
                        next = NORMAL;
                    break;
                case IN_FENCE:
                    flags |= fence;
                    if (closesFence(text, fence))
                        next = NORMAL;
                    break;
                case IN_COMMENT: {
                    final int end = text.indexOf("-->");
                    if (end >= 0)
                        next = commentStateAfter(text, end + 3);
                    break;
                }
                default: {
                    Matcher m;
                    if (line == first && line.previous() == null
                        && text.equals("---")) {
                        next = IN_FRONT_MATTER;
                    } else if ((m = FENCE_OPEN.matcher(text)).matches()) {
                        final String run = m.group(1);
                        fence = (run.charAt(0) == '~' ? FENCE_TILDE : 0)
                            | Math.min(run.length(), 31) << FENCE_LENGTH_SHIFT
                            | FenceLanguages.slotFor(text.substring(m.end(1)))
                              << LANGUAGE_SHIFT;
                        next = IN_FENCE;
                    } else {
                        flags |= setextLevel(line) << HEADING_SHIFT;
                        next = commentStateAfter(text, 0);
                    }
                    break;
                }
            }
            sink.accept(line, flags);
            block = next;
        }
    }

    private static boolean closesFence(String text, int fence)
    {
        final Matcher m = FENCE_CLOSE.matcher(text);
        if (!m.matches())
            return false;
        final String run = m.group(1);
        final boolean tilde = (fence & FENCE_TILDE) != 0;
        final int length = (fence & FENCE_LENGTH_MASK) >> FENCE_LENGTH_SHIFT;
        return (run.charAt(0) == '~') == tilde && run.length() >= length;
    }

    // NORMAL, or IN_COMMENT if an HTML comment opened from offset on is not
    // closed by the end of text.
    private static int commentStateAfter(String text, int offset)
    {
        while (true) {
            final int begin = text.indexOf("<!--", offset);
            if (begin < 0)
                return NORMAL;
            final int end = text.indexOf("-->", begin + 4);
            if (end < 0)
                return IN_COMMENT;
            offset = end + 3;
        }
    }

    // The level of a setext heading whose text is line, from the underline
    // below it: 1 for '=', 2 for '-'; else 0.
    private static int setextLevel(Line line)
    {
        final Line next = line.next();
        if (next == null)
            return 0;
        final Matcher m = SETEXT_UNDERLINE.matcher(next.getText());
        if (!m.matches())
            return 0;
        final String text = line.getText();
        if (text.trim().isEmpty() || isIndentedCode(text)
            || ATX_HEADING.matcher(text).matches()
            || RULE_LINE.matcher(text).matches()
            || FENCE_OPEN.matcher(text).matches()
            || QUOTE_PREFIX.matcher(text).lookingAt()
            || LIST_ITEM.matcher(text).lookingAt()
            || text.trim().startsWith("|"))
            return 0;
        return m.group(1).charAt(0) == '=' ? 1 : 2;
    }

    private static boolean isIndentedCode(String text)
    {
        int col = 0;
        for (int i = 0; i < text.length() && col < 4; i++) {
            final char c = text.charAt(i);
            if (c == ' ')
                ++col;
            else if (c == '\t')
                col = 4;
            else
                return false;
        }
        return col >= 4;
    }

    public LineSegmentList formatLine(Line line)
    {
        clearSegmentList();
        final String text = getDetabbedText(line);
        final int length = text.length();
        if (length == 0) {
            addSegment(text, TEXT);
            return segmentList;
        }
        if (formats.length < length) {
            final int size = Math.max(length, formats.length * 2);
            formats = new int[size];
            hidden = new boolean[size];
            bars = new boolean[size];
            items = new int[size];
        }
        set(0, length, TEXT);
        Arrays.fill(hidden, 0, length, false);
        Arrays.fill(bars, 0, length, false);
        Arrays.fill(items, 0, length, 0);
        itemCount = 0;
        concealMarkup = conceals("markup");
        concealHeadings = conceals("headings");
        final int flags = line.flags();
        switch (flags & BLOCK_MASK) {
            case IN_FRONT_MATTER:
                set(0, length, FRONT_MATTER);
                break;
            case IN_FENCE:
                if (FENCE_CLOSE.matcher(text).matches() && closesFence(text, flags))
                    fenceLine(length);
                else
                    formatCode(line, text, (flags & LANGUAGE_MASK) >> LANGUAGE_SHIFT);
                break;
            case IN_COMMENT: {
                final int end = text.indexOf("-->");
                if (end < 0) {
                    set(0, length, COMMENT);
                } else {
                    set(0, end + 3, COMMENT);
                    formatInline(text, end + 3, length, TEXT);
                }
                break;
            }
            default:
                formatBlock(line, text, flags);
                break;
        }
        int start = 0;
        for (int i = 1; i <= length; i++) {
            if (i == length || formats[i] != formats[start]
                || hidden[i] != hidden[start] || items[i] != items[start]
                || bars[i] != bars[start]) {
                addSegment(text, start, i, formats[start], hidden[start], items[start]);
                getLastSegment().setBar(bars[start]);
                start = i;
            }
        }
        return segmentList;
    }

    public boolean hidesMarkup()
    {
        return conceals("markup") || conceals("headings");
    }

    /**
     * A fence's lines, its markup shown with the caret anywhere in it; with
     * headings concealed, a setext heading's text and underline.
     */
    public Line[] getHiddenBlock(Line line)
    {
        if (conceals("markup")) {
            final Line[] fence = fenceBlock(line);
            if (fence != null)
                return fence;
        }
        if (conceals("headings")) {
            if (isSetextHeading(line) && line.next() != null)
                return new Line[] { line, line.next() };
            final Line previous = line.previous();
            if (previous != null && isSetextHeading(previous))
                return new Line[] { previous, line };
        }
        return null;
    }

    /**
     * The fence line is in, opens or closes: its opening line and its last,
     * the closing line or the buffer's end; or null.
     */
    static Line[] fenceBlock(Line line)
    {
        Line open;
        if (opensFence(line)) {
            open = line;
        } else if (isInFence(line)) {
            open = line.previous();
            while (open != null && isInFence(open))
                open = open.previous();
            if (open == null)
                return null;
        } else {
            return null;
        }
        Line last = open;
        while (last.next() != null && isInFence(last.next()))
            last = last.next();
        return new Line[] { open, last };
    }

    // A fence's opening or closing line, hidden whole unless the caret is in
    // the fence.
    private void fenceLine(int length)
    {
        set(0, length, FENCE);
        if (concealMarkup) {
            hide(0, length);
            item(0, length, LineSegment.BLOCK);
        }
    }

    // Marks begin to end as markup to hide.
    private void hide(int begin, int end)
    {
        if (concealMarkup)
            Arrays.fill(hidden, begin, end, true);
    }

    // Marks begin to end as part of an item, which the caret in shows.
    private void item(int begin, int end, int item)
    {
        if (concealMarkup || concealHeadings)
            Arrays.fill(items, begin, end, item);
    }

    // Heading markers, hidden as hide hides the rest.
    private void hideHeading(int begin, int end)
    {
        Arrays.fill(hidden, begin, end, true);
    }

    private int newItem(int begin, int end)
    {
        item(begin, end, ++itemCount);
        return itemCount;
    }

    // A fenced line, in its language if j has a mode for it. The language's
    // formatter gets a copy with no flags: what it would know from the
    // lines before, inside a comment that began on one, it does not.
    private void formatCode(Line line, String text, int slot)
    {
        final Formatter formatter = language(slot);
        if (formatter == null) {
            set(0, text.length(), CODE_BLOCK);
            return;
        }
        final LineSegmentList segments;
        try {
            segments = formatter.formatLine(new TextLine(line.getText()));
        }
        catch (RuntimeException e) {
            Log.debug(e);
            set(0, text.length(), CODE_BLOCK);
            return;
        }
        int pos = 0;
        for (int i = 0; i < segments.size() && pos < text.length(); i++) {
            final LineSegment segment = segments.getSegment(i);
            final int end = Math.min(text.length(), pos + segment.length());
            set(pos, end, EMBED | slot << EMBED_SLOT_SHIFT
                          | (segment.getFormat() & EMBED_FORMAT));
            pos = end;
        }
        set(pos, text.length(), CODE_BLOCK);
    }

    private Formatter language(int slot)
    {
        if (slot == 0)
            return null;
        if (languages[slot] == null) {
            final Mode mode = FenceLanguages.modeFor(slot);
            if (mode == null)
                return null;
            final Formatter formatter = mode.getFormatter(buffer);
            if (formatter == null)
                return null;
            formatter.setLanguageMode(mode);
            languages[slot] = formatter;
        }
        return languages[slot];
    }

    // The language's formatter for a format it gave, or null.
    private Formatter embedded(int format)
    {
        if ((format & EMBED) == 0)
            return null;
        return languages[(format & ~EMBED) >> EMBED_SLOT_SHIFT];
    }

    public Color getColor(int format)
    {
        final Formatter formatter = embedded(format);
        if (formatter != null)
            return formatter.getColor(format & EMBED_FORMAT);
        return super.getColor(format);
    }

    /**
     * A language's own style, italic too if the theme makes code blocks
     * italic, as Markdown's does.
     */
    public int getStyle(int format)
    {
        final Formatter formatter = embedded(format);
        if (formatter != null)
            return formatter.getStyle(format & EMBED_FORMAT)
                | (super.getStyle(CODE_BLOCK) & TextStyle.ITALIC);
        return super.getStyle(format);
    }

    public boolean getUnderline(int format)
    {
        final Formatter formatter = embedded(format);
        if (formatter != null)
            return formatter.getUnderline(format & EMBED_FORMAT);
        return super.getUnderline(format);
    }

    // The shade behind code, as Obsidian's.
    private Color codeBackground;

    private Color codeBackground()
    {
        if (codeBackground == null)
            codeBackground = getShade("MarkdownMode", "codeBackground");
        return codeBackground;
    }

    /** A fence's lines, and an indented code block's, shaded. */
    public Color getLineBackground(Line line)
    {
        if (isInFence(line) || opensFence(line) || isIndentedCodeBlock(line))
            return codeBackground();
        return null;
    }

    /** Inline code, shaded as a code block is. */
    public Color getRunBackground(int format)
    {
        if (format == CODE || format == CODE_MARKER)
            return codeBackground();
        return null;
    }

    public void reset()
    {
        super.reset();
        codeBackground = null;
        for (Formatter formatter : languages)
            if (formatter != null)
                formatter.reset();
    }

    private void set(int begin, int end, int format)
    {
        Arrays.fill(formats, begin, end, format);
    }

    private void formatBlock(Line line, String text, int flags)
    {
        final int length = text.length();
        if (line.previous() == null && text.equals("---")) {
            final Line next = line.next();
            if (next != null && (next.flags() & BLOCK_MASK) == IN_FRONT_MATTER) {
                set(0, length, FRONT_MATTER);
                return;
            }
        }
        if (FENCE_OPEN.matcher(text).matches()) {
            fenceLine(length);
            return;
        }
        Matcher m = ATX_HEADING.matcher(text);
        if (m.matches()) {
            final int level = m.group(1).length();
            set(0, length, HEADING_MARKER);
            if (m.group(2) != null) {
                set(m.start(2), m.end(2), HEADING_1 + level - 1);
                if (concealHeadings) {
                    newItem(0, length);
                    hideHeading(0, m.start(2));
                    hideHeading(m.end(2), length);
                }
            }
            return;
        }
        final int setext = (flags & HEADING_MASK) >> HEADING_SHIFT;
        if (setext > 0) {
            set(0, length, HEADING_1 + setext - 1);
            return;
        }
        final Line previous = line.previous();
        if (previous != null && SETEXT_UNDERLINE.matcher(text).matches()
            && (previous.flags() & BLOCK_MASK) == NORMAL
            && (previous.flags() & HEADING_MASK) != 0) {
            set(0, length, HEADING_MARKER);
            if (concealHeadings) {
                hideHeading(0, length);
                item(0, length, LineSegment.BLOCK);
            }
            return;
        }
        if (RULE_LINE.matcher(text).matches()) {
            set(0, length, RULE);
            return;
        }
        if (isIndentedCodeBlock(line)) {
            set(0, length, CODE_BLOCK);
            return;
        }

        int pos = 0;
        int base = TEXT;
        m = QUOTE_PREFIX.matcher(text);
        if (m.lookingAt()) {
            pos = m.end();
            base = QUOTE;
            set(pos, length, base);
            set(0, pos, QUOTE_MARKER);
            // Each '>' a bar, as Obsidian draws a quote, the space after it
            // the gap before the text; shown again with the caret on the
            // line. The item first, so that items in the quote are their own.
            newItem(0, length);
            for (int i = 0; i < pos; i++) {
                if (text.charAt(i) == '>') {
                    hide(i, i + 1);
                    if (concealMarkup)
                        bars[i] = true;
                }
            }
        }

        m = LIST_ITEM.matcher(text).region(pos, length);
        if (m.lookingAt()) {
            set(m.start(1), m.end(1), LIST_MARKER);
            pos = m.end();
            m = TASK_BOX.matcher(text).region(pos, length);
            if (m.lookingAt()) {
                pos = formatTask(text, m, base);
                if (pos < 0)
                    return;
            }
        }

        if (text.trim().startsWith("|")) {
            if (TABLE_DELIMITER_ROW.matcher(text).matches()) {
                set(pos, length, MARKUP);
                return;
            }
            formatInline(text, pos, length, base);
            for (int i = pos; i < length; i++)
                if (text.charAt(i) == '|' && formats[i] == base
                    && (i == 0 || text.charAt(i - 1) != '\\'))
                    formats[i] = MARKUP;
            return;
        }

        m = LINK_DEFINITION.matcher(text).region(pos, length);
        if (m.lookingAt()) {
            set(m.start(1), m.end(1), MARKUP);
            set(m.start(2), m.end(2), LINK_TEXT);
            set(m.start(3), m.end(3), MARKUP);
            set(m.start(4), m.end(4), URL);
            formatInline(text, m.end(4), length, base);
            return;
        }

        formatInline(text, pos, length, base);
    }

    // Colors a task box and the item's text. Returns where inline markup in
    // the text begins, or -1 if the text is all one format.
    private int formatTask(String text, Matcher box, int base)
    {
        final int open = box.start();
        final int mark = box.start(1);
        final int close = box.end() - 1;
        final int length = text.length();
        final int boxFormat;
        final int markFormat;
        final int textFormat;
        switch (text.charAt(mark)) {
            case '/':
                boxFormat = IN_PROGRESS;
                markFormat = IN_PROGRESS_MARKER;
                textFormat = -1;
                break;
            case 'x':
            case 'X':
                boxFormat = markFormat = DONE;
                textFormat = DONE_TEXT;
                break;
            case '-':
                boxFormat = markFormat = CANCELLED;
                textFormat = CANCELLED_TEXT;
                break;
            default:
                boxFormat = markFormat = TODO;
                textFormat = -1;
                break;
        }
        set(open, mark, boxFormat);
        set(mark, close, markFormat);
        set(close, close + 1, boxFormat);
        if (textFormat < 0)
            return close + 1;
        set(close + 1, length, textFormat);
        return -1;
    }

    // Indented four or more, after a blank line or more indented code, and
    // not in a list item: indented code cannot interrupt a paragraph.
    static boolean isIndentedCodeBlock(Line line)
    {
        if (!isIndentedCode(line.getText()) || isInList(line))
            return false;
        for (Line l = line.previous(); l != null; l = l.previous()) {
            final String text = l.getText();
            if (text.trim().isEmpty())
                return true;
            if (!isIndentedCode(text))
                return false;
        }
        return true;
    }

    // Whether line continues a list item: a list item, or an indented line
    // with one above it before a blank line.
    private static boolean isInList(Line line)
    {
        for (Line l = line; l != null; l = l.previous()) {
            final String text = l.getText();
            if (text.trim().isEmpty())
                return l != line && isListItem(l.previous());
            if (LIST_ITEM.matcher(text).lookingAt())
                return true;
            if (!isIndentedCode(text))
                return false;
        }
        return false;
    }

    private static boolean isListItem(Line line)
    {
        while (line != null && line.getText().trim().isEmpty())
            line = line.previous();
        for (; line != null; line = line.previous()) {
            final String text = line.getText();
            if (text.trim().isEmpty())
                return false;
            if (LIST_ITEM.matcher(text).lookingAt())
                return true;
            if (!isIndentedCode(text))
                return false;
        }
        return false;
    }

    // Colors the inline markup of text from begin to end, the rest of it in
    // base.
    private void formatInline(String text, int begin, int end, int base)
    {
        set(begin, end, base);
        int i = begin;
        while (i < end) {
            final char c = text.charAt(i);
            int next = -1;
            switch (c) {
                case '\\':
                    if (i + 1 < end && isAsciiPunctuation(text.charAt(i + 1))) {
                        set(i, i + 1, MARKUP);
                        newItem(i, i + 2);
                        hide(i, i + 1);
                        next = i + 2;
                    }
                    break;
                case '`':
                    next = formatCodeSpan(text, i, end);
                    break;
                case '<':
                    next = formatAngle(text, i, end);
                    break;
                case '!':
                    if (i + 1 < end && text.charAt(i + 1) == '[') {
                        next = formatLink(text, i + 1, end);
                        if (next > 0) {
                            set(i, i + 1, MARKUP);
                            item(i, i + 1, items[i + 1]);
                            hide(i, i + 1);
                        }
                    }
                    break;
                case '[':
                    next = formatLink(text, i, end);
                    break;
                case '*':
                case '_':
                case '~':
                    next = formatEmphasis(text, i, end);
                    if (next < 0)
                        next = i + runLength(text, i, end);
                    break;
                case 'h':
                    if (i == begin || !Character.isLetterOrDigit(text.charAt(i - 1))) {
                        final Matcher m = BARE_URL.matcher(text).region(i, end);
                        if (m.lookingAt()) {
                            set(i, m.end(), URL);
                            next = m.end();
                        }
                    }
                    break;
                default:
                    break;
            }
            i = next > i ? next : i + 1;
        }
    }

    private static boolean isAsciiPunctuation(char c)
    {
        return c < 128 && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    private static int runLength(String text, int i, int end)
    {
        final char c = text.charAt(i);
        int j = i;
        while (j < end && text.charAt(j) == c)
            ++j;
        return j - i;
    }

    // `code`: a run of backticks to the next run as long. Returns where the
    // code span ends, or past the backticks if nothing closes them.
    private int formatCodeSpan(String text, int i, int end)
    {
        final int run = runLength(text, i, end);
        final int close = findCodeSpanClose(text, i + run, end, run);
        if (close < 0)
            return i + run;
        set(i, i + run, CODE_MARKER);
        set(i + run, close, CODE);
        set(close, close + run, CODE_MARKER);
        newItem(i, close + run);
        hide(i, i + run);
        hide(close, close + run);
        return close + run;
    }

    private static int findCodeSpanClose(String text, int from, int end, int run)
    {
        int j = from;
        while (j < end) {
            if (text.charAt(j) == '`') {
                final int n = runLength(text, j, end);
                if (n == run)
                    return j;
                j += n;
            } else {
                ++j;
            }
        }
        return -1;
    }

    // <url>, <!-- comment -->, or an HTML tag.
    private int formatAngle(String text, int i, int end)
    {
        if (text.startsWith("<!--", i)) {
            final int close = text.indexOf("-->", i + 4);
            final int stop = close < 0 || close + 3 > end ? end : close + 3;
            set(i, stop, COMMENT);
            return stop;
        }
        Matcher m = AUTOLINK.matcher(text).region(i, end);
        if (m.lookingAt()) {
            set(i, i + 1, MARKUP);
            set(i + 1, m.end() - 1, URL);
            set(m.end() - 1, m.end(), MARKUP);
            newItem(i, m.end());
            hide(i, i + 1);
            hide(m.end() - 1, m.end());
            return m.end();
        }
        m = HTML_TAG_PATTERN.matcher(text).region(i, end);
        if (m.lookingAt()) {
            set(i, m.end(), HTML_TAG);
            return m.end();
        }
        return -1;
    }

    // [text](url), [text][ref] or [text][]. Returns where it ends, or -1 if
    // the bracket at open does not begin one.
    private int formatLink(String text, int open, int end)
    {
        final int close = findClose(text, open, end, '[', ']');
        if (close < 0 || close + 1 >= end)
            return -1;
        final char after = text.charAt(close + 1);
        final int stop;
        if (after == '(') {
            stop = findClose(text, close + 1, end, '(', ')');
            if (stop < 0)
                return -1;
        } else if (after == '[') {
            stop = text.indexOf(']', close + 2);
            if (stop < 0 || stop >= end)
                return -1;
        } else {
            return -1;
        }
        // The item first, so that items inside the link text are their own.
        newItem(open, stop + 1);
        set(open, open + 1, MARKUP);
        formatInline(text, open + 1, close, LINK_TEXT);
        set(close, close + 2, MARKUP);
        set(close + 2, stop, URL);
        set(stop, stop + 1, MARKUP);
        hide(open, open + 1);
        hide(close, stop + 1);
        return stop + 1;
    }

    // The bracket closing the one at open, minding nesting, backslashes and
    // code spans; -1 if none does.
    private static int findClose(String text, int open, int end, char left, char right)
    {
        int depth = 0;
        for (int j = open; j < end; j++) {
            final char c = text.charAt(j);
            if (c == '\\') {
                ++j;
            } else if (c == '`') {
                final int run = runLength(text, j, end);
                final int close = findCodeSpanClose(text, j + run, end, run);
                j = close < 0 ? j + run - 1 : close + run - 1;
            } else if (c == left) {
                ++depth;
            } else if (c == right && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    // *em*, **strong**, ***both***, the same with '_' between words, and
    // ~~struck~~. Returns where it ends, or -1 if the run at i opens none.
    private int formatEmphasis(String text, int i, int end)
    {
        final char c = text.charAt(i);
        final int run = runLength(text, i, end);
        if (c == '~' ? run > 2 : run > 3)
            return -1;
        if (!opens(text, i, run, end))
            return -1;
        final int close = findEmphasisClose(text, i + run, end, c, run);
        if (close < 0)
            return -1;
        final int format;
        if (c == '~')
            format = STRIKETHROUGH;
        else if (run == 1)
            format = EMPHASIS;
        else if (run == 2)
            format = STRONG;
        else
            format = STRONG_EMPHASIS;
        newItem(i, close + run);
        set(i, i + run, MARKUP);
        formatInline(text, i + run, close, format);
        set(close, close + run, MARKUP);
        hide(i, i + run);
        hide(close, close + run);
        return close + run;
    }

    // A run opens emphasis if what follows it is not white space, and for
    // '_', if it does not follow a letter or digit.
    private static boolean opens(String text, int i, int run, int end)
    {
        if (i + run >= end || Character.isWhitespace(text.charAt(i + run)))
            return false;
        return text.charAt(i) != '_' || i == 0
            || !Character.isLetterOrDigit(text.charAt(i - 1));
    }

    // A run of exactly run c's that closes: after something other than white
    // space, and for '_', not before a letter or digit. Skips code spans.
    private static int findEmphasisClose(String text, int from, int end,
                                         char c, int run)
    {
        int j = from;
        while (j < end) {
            final char ch = text.charAt(j);
            if (ch == '\\') {
                j += 2;
            } else if (ch == '`') {
                final int n = runLength(text, j, end);
                final int close = findCodeSpanClose(text, j + n, end, n);
                j = close < 0 ? j + n : close + n;
            } else if (ch == c) {
                final int n = runLength(text, j, end);
                if (n == run && !Character.isWhitespace(text.charAt(j - 1))
                    && (c != '_' || j + n >= end
                        || !Character.isLetterOrDigit(text.charAt(j + n))))
                    return j;
                j += n;
            } else {
                ++j;
            }
        }
        return -1;
    }

    public FormatTable getFormatTable()
    {
        if (formatTable == null) {
            formatTable = new FormatTable("MarkdownMode");
            formatTable.addEntryFromPrefs(TEXT, "text");
            formatTable.addEntryFromPrefs(HEADING_MARKER, "headingMarker");
            for (int level = 1; level <= 6; level++)
                formatTable.addEntryFromPrefs(HEADING_1 + level - 1, "heading" + level);
            formatTable.addEntryFromPrefs(CODE, "code");
            formatTable.addEntryFromPrefs(CODE_BLOCK, "codeBlock");
            formatTable.addEntryFromPrefs(FENCE, "fence");
            formatTable.addEntryFromPrefs(LINK_TEXT, "linkText");
            formatTable.addEntryFromPrefs(URL, "url");
            formatTable.addEntryFromPrefs(MARKUP, "markup");
            formatTable.addEntryFromPrefs(EMPHASIS, "emphasis");
            formatTable.addEntryFromPrefs(STRONG, "strong");
            formatTable.addEntryFromPrefs(STRONG_EMPHASIS, "strongEmphasis");
            formatTable.addEntryFromPrefs(STRIKETHROUGH, "strikethrough");
            formatTable.addEntryFromPrefs(QUOTE, "quote");
            formatTable.addEntryFromPrefs(QUOTE_MARKER, "quoteMarker");
            formatTable.addEntryFromPrefs(LIST_MARKER, "listMarker");
            formatTable.addEntryFromPrefs(RULE, "rule");
            formatTable.addEntryFromPrefs(TODO, "todo");
            formatTable.addEntryFromPrefs(IN_PROGRESS, "inProgress");
            formatTable.addEntryFromPrefs(IN_PROGRESS_MARKER, "inProgressMarker");
            formatTable.addEntryFromPrefs(DONE, "done");
            formatTable.addEntryFromPrefs(DONE_TEXT, "doneText");
            formatTable.addEntryFromPrefs(CANCELLED, "cancelled");
            formatTable.addEntryFromPrefs(CANCELLED_TEXT, "cancelledText");
            formatTable.addEntryFromPrefs(COMMENT, "comment");
            formatTable.addEntryFromPrefs(HTML_TAG, "htmlTag");
            formatTable.addEntryFromPrefs(FRONT_MATTER, "frontMatter");
            formatTable.addEntryFromPrefs(CODE_MARKER, "codeMarker");
        }
        return formatTable;
    }
}
