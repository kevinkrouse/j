/*
 * XmlPairMatcher.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.xml;

import static org.armedbear.j.Constants.*;

import java.util.Locale;
import java.util.Set;
import java.util.function.IntPredicate;
import org.armedbear.j.BracketPairMatcher;
import org.armedbear.j.Buffer;
import org.armedbear.j.DelimiterDepths;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * XML's and HTML's pairs: an element's start and end tags, whose delimiters
 * are their names, and a comment's {@code <!--} and {@code -->}, with the
 * brackets and quotes of {@link BracketPairMatcher} under them. Only an
 * element of the same name nests, so an HTML element left open does not
 * throw the count off.
 */
public final class XmlPairMatcher extends BracketPairMatcher {
    static final String COMMENT_START = "<!--";
    static final String COMMENT_END = "-->";
    static final String CDATA_START = "<![CDATA[";
    static final String CDATA_END = "]]>";

    public static final XmlPairMatcher XML = new XmlPairMatcher(false,
            Set.of(),
            Set.of(),
            Set.of(),
            STATE_COMMENT,
            flags -> flags == STATE_TAG
                    || flags == XmlFormatter.STATE_ATTRIBUTE
                    || flags == STATE_QUOTE
                    || flags == STATE_SINGLEQUOTE);

    public static final XmlPairMatcher HTML = new XmlPairMatcher(true,
            // Elements with no end tag.
            Set.of(
                "area",
                "base",
                "br",
                "col",
                "embed",
                "hr",
                "img",
                "input",
                "link",
                "meta",
                "param",
                "source",
                "track",
                "wbr"),
            // Elements whose content is text, not tags.
            Set.of("script", "style"),
            // Elements whose end tag is often left out, which rainbowDelimiters leaves alone.
            Set.of("p", "li", "dt", "dd", "tr", "td", "th", "option"),
            STATE_HTML_COMMENT,
            flags -> flags == STATE_TAG || flags == STATE_SCRIPT_TAG);

    private final boolean html;
    private final Set<String> voidElements;
    private final Set<String> rawTextElements;
    private final Set<String> unnested;
    private final int commentState;
    private final IntPredicate inTag;

    private XmlPairMatcher(
            boolean html,
            Set<String> voidElements,
            Set<String> rawTextElements,
            Set<String> unnested,
            int commentState,
            IntPredicate inTag) {
        this.html = html;
        this.voidElements = voidElements;
        this.rawTextElements = rawTextElements;
        this.unnested = unnested;
        this.commentState = commentState;
        this.inTag = inTag;
    }

    private enum Kind {
        START, END, COMMENT, OTHER
    }

    /**
     * A tag: where its '<' is, its name and where that starts, the position
     * just past its '>', and whether it is an element with no content.
     */
    private record Tag(Position lt, Kind kind, String name, int nameOffset, Position end, boolean empty) {
        Position namePos() {
            return new Position(lt.getLine(), nameOffset);
        }
    }

    @Override
    public Pair pairAt(Editor editor, Position pos, int numLines) {
        final Pair pair = super.pairAt(editor, pos, numLines);
        if (pair != null)
            return pair;
        final Position delimiter = commentDelimiter(pos);
        if (delimiter != null) {
            final Position match = commentMatch(delimiter, numLines);
            if (match == null)
                return null;
            return new Pair(new Delimiter(delimiter, commentDelimiterLength(delimiter)),
                    new Delimiter(match, commentDelimiterLength(match)));
        }
        if (isInComment(pos) || isInCDataSection(pos))
            return null;
        final Position lt = tagStart(pos);
        if (lt == null)
            return null;
        final Tag tag = tagAt(lt);
        final Position match = tag != null ? elementMatch(tag, numLines) : null;
        if (match == null)
            return null;
        return new Pair(new Delimiter(tag.namePos(), tag.name().length()), new Delimiter(match, tag.name().length()));
    }

    /**
     * From the tag the caret is in, or else the first tag after it on its
     * line, the first character of the other tag's name. A bracket under
     * the caret, or one before any tag, goes to its own match.
     */
    @Override
    public Position findMatch(Editor editor, Position pos) {
        // A bar caret at the end of a line is still in the tag it follows.
        if (pos.getOffset() > 0 && pos.getOffset() == pos.getLine().length())
            pos = new Position(pos.getLine(), pos.getOffset() - 1);
        if (isBracket(pos.getChar()))
            return super.findMatch(editor, pos);
        final Position delimiter = commentDelimiter(pos);
        if (delimiter != null)
            return commentMatch(delimiter, 0);
        if (isInComment(pos) || isInCDataSection(pos))
            return super.findMatch(editor, pos);
        Position lt = tagStart(pos);
        if (lt == null) {
            final Line line = pos.getLine();
            for (int i = pos.getOffset(); i < line.length() && lt == null; i++) {
                final char c = line.charAt(i);
                if (isBracket(c))
                    return super.findMatch(editor, new Position(line, i));
                if (c == '<')
                    lt = new Position(line, i);
            }
            if (lt == null)
                return null;
            if (lt.lookingAt(COMMENT_START))
                return commentMatch(lt, 0);
        }
        final Tag tag = tagAt(lt);
        return tag != null ? elementMatch(tag, 0) : null;
    }

    @Override
    public int scan(Buffer buffer, Line line, int depth, int[] levels) {
        if (levels != null)
            java.util.Arrays.fill(levels, DelimiterDepths.NONE);
        final String text = line.getText();
        if (text == null)
            return depth;
        final int limit = text.length();
        int i = 0;
        final int flags = line.flags();
        if (flags == commentState)
            i = skipPast(text, 0, COMMENT_END);
        else if (!html && flags == STATE_CDATA)
            i = skipPast(text, 0, CDATA_END);
        else if (html && (flags == STATE_SCRIPT || flags == STATE_COMMENT))
            i = rawTextEnd(text, 0);
        else if (inTag.test(flags))
            i = tagEnd(text, 0, flags == STATE_QUOTE ? '"' : flags == STATE_SINGLEQUOTE ? '\'' : 0);
        while (i < limit) {
            if (text.charAt(i) != '<') {
                ++i;
                continue;
            }
            if (text.startsWith(COMMENT_START, i)) {
                i = skipPast(text, i + COMMENT_START.length(), COMMENT_END);
                continue;
            }
            if (!html && text.startsWith(CDATA_START, i)) {
                i = skipPast(text, i + CDATA_START.length(), CDATA_END);
                continue;
            }
            final Tag tag = tagAt(new Position(line, i));
            if (tag == null) {
                ++i;
                continue;
            }
            final boolean nests = !unnested.contains(key(tag.name()));
            if (tag.kind() == Kind.END && nests)
                mark(levels, tag, depth > 0 ? depth-- : DelimiterDepths.UNMATCHED);
            else if (tag.kind() == Kind.START && !tag.empty() && nests)
                mark(levels, tag, ++depth);
            if (tag.end().getLine() != line)
                break;
            i = tag.end().getOffset();
            if (tag.kind() == Kind.START && !tag.empty() && rawTextElements.contains(key(tag.name())))
                i = rawTextEnd(text, i);
        }
        return depth;
    }

    private static void mark(int[] levels, Tag tag, int level) {
        if (levels == null)
            return;
        for (int k = 0; k < tag.name().length(); k++)
            levels[tag.nameOffset() + k] = level;
    }

    // Past the end of the string looked for, or the line's end.
    private static int skipPast(String text, int from, String end) {
        final int i = text.indexOf(end, from);
        return i < 0 ? text.length() : i + end.length();
    }

    // Past the '>' that ends a tag the line starts inside of, or the line's end.
    private static int tagEnd(String text, int from, char quote) {
        for (int i = from; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote)
                    quote = 0;
            } else if (c == '"' || c == '\'')
                quote = c;
            else if (c == '>')
                return i + 1;
        }
        return text.length();
    }

    // Where a script or style element's text ends on the line: at its end tag, or the line's end.
    private int rawTextEnd(String text, int from) {
        final String lower = text.toLowerCase(Locale.ROOT);
        int end = text.length();
        for (String name : rawTextElements) {
            final int i = lower.indexOf("</" + name, from);
            if (i >= 0 && i < end)
                end = i;
        }
        return end;
    }

    // ---------------------------------------------------------- elements

    private String key(String name) {
        return html ? name.toLowerCase(Locale.ROOT) : name;
    }

    private boolean sameName(String a, String b) {
        return html ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    /** The tag whose '<' is at lt, or null if that '<' starts no tag. */
    private Tag tagAt(Position lt) {
        final Line line = lt.getLine();
        final int offset = lt.getOffset();
        if (lt.lookingAt(COMMENT_START)) {
            final Position end = lt.copy();
            return new Tag(lt.copy(), Kind.COMMENT, "", offset, end, false);
        }
        final int next = offset + 1;
        final char c = next < line.length() ? line.charAt(next) : 0;
        final Kind kind;
        final int nameOffset;
        if (c == '!' || c == '?') {
            kind = Kind.OTHER;
            nameOffset = next;
        } else if (c == '/') {
            kind = Kind.END;
            nameOffset = next + 1;
        } else {
            kind = Kind.START;
            nameOffset = next;
        }
        int i = nameOffset;
        while (i < line.length() && isNameChar(line.charAt(i)))
            ++i;
        final String name = line.substring(nameOffset, i);
        if (kind != Kind.OTHER && (name.isEmpty() || !isNameStart(name.charAt(0))))
            return null;
        final Position end = lt.copy();
        final String tag = getTag(end);
        final boolean empty = kind == Kind.START && (tag.endsWith("/>") || voidElements.contains(key(name)));
        return new Tag(lt.copy(), kind, name, nameOffset, end, empty);
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isNameChar(char c) {
        return c > ' ' && c != '>' && c != '/' && c != '<' && c != '=' && c != '"' && c != '\'';
    }

    /** The name of the tag at the other end of tag's element, or null. */
    private Position elementMatch(Tag tag, int numLines) {
        final int line = tag.lt().lineNumber();
        if (tag.kind() == Kind.START && !tag.empty())
            return findEndTag(tag.name(), tag.end(), numLines == 0 ? -1 : line + numLines);
        if (tag.kind() == Kind.END)
            return findStartTag(tag.name(), tag.lt(), numLines == 0 ? -1 : line - numLines);
        if (tag.kind() == Kind.COMMENT)
            return commentMatch(tag.lt(), numLines);
        return null;
    }

    /** The name in the end tag that closes the element whose start tag ends at from. */
    private Position findEndTag(String name, Position from, int stopLine) {
        final Position p = from.copy();
        // A script's text is not tags: its end tag is the first one.
        if (rawTextElements.contains(key(name))) {
            if (!skipForwardIgnoreCase(p, "</" + name))
                return null;
            if (stopLine >= 0 && p.lineNumber() > stopLine)
                return null;
            return new Position(p.getLine(), p.getOffset() + 2);
        }
        int depth = 1;
        while (true) {
            if (stopLine >= 0 && p.lineNumber() > stopLine)
                return null;
            if (p.getChar() != '<') {
                if (!p.next())
                    return null;
                continue;
            }
            if (p.lookingAt(COMMENT_START)) {
                if (!skipForward(p, COMMENT_END))
                    return null;
                continue;
            }
            if (!html && p.lookingAt(CDATA_START)) {
                if (!skipForward(p, CDATA_END))
                    return null;
                continue;
            }
            final Tag tag = tagAt(p);
            if (tag == null) {
                p.next();
                continue;
            }
            if (tag.kind() == Kind.END && sameName(tag.name(), name)) {
                if (--depth == 0)
                    return tag.namePos();
            } else if (tag.kind() == Kind.START && !tag.empty()) {
                if (sameName(tag.name(), name))
                    ++depth;
                else if (rawTextElements.contains(key(tag.name()))) {
                    p.moveTo(tag.end());
                    if (!skipForwardIgnoreCase(p, "</" + tag.name()))
                        return null;
                    continue;
                }
            }
            p.moveTo(tag.end());
        }
    }

    /** The name in the start tag of the element whose end tag starts at from. */
    private Position findStartTag(String name, Position from, int stopLine) {
        final Position p = from.copy();
        if (rawTextElements.contains(key(name))) {
            if (!skipBackwardIgnoreCase(p, "<" + name))
                return null;
            if (stopLine >= 0 && p.lineNumber() < stopLine)
                return null;
            return new Position(p.getLine(), p.getOffset() + 1);
        }
        int depth = 1;
        while (p.prev()) {
            if (stopLine >= 0 && p.lineNumber() < stopLine)
                return null;
            final char c = p.getChar();
            if (c == '>') {
                if (endsWith(p, COMMENT_END)) {
                    if (!skipBackward(p, COMMENT_START))
                        return null;
                } else if (!html && endsWith(p, CDATA_END)) {
                    if (!skipBackward(p, CDATA_START))
                        return null;
                }
                continue;
            }
            if (c != '<')
                continue;
            final Tag tag = tagAt(p);
            if (tag == null)
                continue;
            if (tag.kind() == Kind.END) {
                if (sameName(tag.name(), name))
                    ++depth;
                else if (rawTextElements.contains(key(tag.name()))) {
                    if (!skipBackwardIgnoreCase(p, "<" + tag.name()))
                        return null;
                }
            } else if (tag.kind() == Kind.START && !tag.empty() && sameName(tag.name(), name)) {
                if (--depth == 0)
                    return tag.namePos();
            }
        }
        return null;
    }

    // Moves p past the next s; false if there is none.
    private static boolean skipForward(Position p, String s) {
        while (p.next()) {
            if (p.lookingAt(s)) {
                p.skip(s.length());
                return true;
            }
        }
        return false;
    }

    // Moves p to the next s, case aside; false if there is none.
    private static boolean skipForwardIgnoreCase(Position p, String s) {
        do {
            if (p.lookingAtIgnoreCase(s))
                return true;
        } while (p.next());
        return false;
    }

    // Moves p back to the s before it; false if there is none.
    private static boolean skipBackward(Position p, String s) {
        while (p.prev()) {
            if (p.lookingAt(s))
                return true;
        }
        return false;
    }

    private static boolean skipBackwardIgnoreCase(Position p, String s) {
        while (p.prev()) {
            if (p.lookingAtIgnoreCase(s))
                return true;
        }
        return false;
    }

    // Whether p is on the last character of s.
    private static boolean endsWith(Position p, String s) {
        final int start = p.getOffset() - s.length() + 1;
        return start >= 0 && p.getLine().getText().startsWith(s, start);
    }

    /**
     * The '<' of the tag pos is in, from its '<' to its '>', or null. A line
     * that starts inside a tag looks back to the line the tag starts on.
     * No attribute value holds a '<', but one may hold a '>'.
     */
    private Position tagStart(Position pos) {
        final Line line = pos.getLine();
        final int lt = line.getText().lastIndexOf('<', pos.getOffset());
        Position start = null;
        if (lt >= 0)
            start = new Position(line, lt);
        else if (inTag.test(line.flags())) {
            final Position p = new Position(line, 0);
            while (p.prev()) {
                if (p.getChar() == '<') {
                    start = p;
                    break;
                }
            }
        }
        if (start == null)
            return null;
        final Position end = start.copy();
        getTag(end);
        return pos.isBefore(end) ? start : null;
    }

    // ---------------------------------------------------------- comments

    /** The start of the <!-- or --> pos is on, or null. */
    private static Position commentDelimiter(Position pos) {
        final Line line = pos.getLine();
        final String text = line.getText();
        final int offset = pos.getOffset();
        for (int k = 0; k < COMMENT_START.length(); k++) {
            if (offset - k >= 0 && text.startsWith(COMMENT_START, offset - k))
                return new Position(line, offset - k);
        }
        for (int k = 0; k < COMMENT_END.length(); k++) {
            if (offset - k >= 0 && text.startsWith(COMMENT_END, offset - k))
                return new Position(line, offset - k);
        }
        return null;
    }

    private static int commentDelimiterLength(Position delimiter) {
        return delimiter.lookingAt(COMMENT_START) ? COMMENT_START.length() : COMMENT_END.length();
    }

    // The other end of the comment that delimiter starts or ends.
    private static Position commentMatch(Position delimiter, int numLines) {
        final Position p = delimiter.copy();
        final boolean forward = p.lookingAt(COMMENT_START);
        if (forward)
            p.skip(COMMENT_START.length());
        final String other = forward ? COMMENT_END : COMMENT_START;
        // Forward, the --> may come straight after the <!--, as in <!---->.
        if (forward || p.prev()) {
            do {
                if (numLines != 0 && Math.abs(p.lineNumber() - delimiter.lineNumber()) > numLines)
                    return null;
                if (p.lookingAt(other))
                    return p;
            } while (forward ? p.next() : p.prev());
        }
        return null;
    }

    // ----------------------------------------------- shared with XmlMode

    /** Whether position is inside a comment, from its line's start and flags. */
    boolean isInComment(Position position) {
        final Position pos = position.copy();
        boolean inComment = pos.getLine().flags() == commentState;
        pos.setOffset(0);
        final int limit = position.getOffset();
        while (pos.getOffset() < limit) {
            if (inComment) {
                if (pos.lookingAt(COMMENT_END)) {
                    pos.skip(COMMENT_END.length());
                    if (pos.getOffset() > limit)
                        break;
                    inComment = false;
                    continue;
                }
            } else if (pos.lookingAt(COMMENT_START)) {
                inComment = true;
                pos.skip(COMMENT_START.length());
                continue;
            }
            pos.next();
        }
        return inComment;
    }

    /** Whether position is inside a CDATA section; never in HTML. */
    boolean isInCDataSection(Position position) {
        if (html)
            return false;
        final Position pos = position.copy();
        boolean inCDataSection = pos.getLine().flags() == STATE_CDATA;
        pos.setOffset(0);
        final int limit = position.getOffset();
        while (pos.getOffset() < limit) {
            if (inCDataSection) {
                if (pos.lookingAt(CDATA_END)) {
                    pos.skip(CDATA_END.length());
                    if (pos.getOffset() > limit)
                        break;
                    inCDataSection = false;
                    continue;
                }
            } else if (pos.lookingAt(CDATA_START)) {
                inCDataSection = true;
                pos.skip(CDATA_START.length());
                continue;
            }
            pos.next();
        }
        return inCDataSection;
    }

    /** The name in the start tag the end tag at pos closes, or null. */
    Position findStartTag(String name, Position pos) {
        return findStartTag(name, pos, -1);
    }

    /**
     * The tag whose '<' is at pos, quoted values and all, over as many lines
     * as it takes; pos is left just past its '>'.
     */
    static String getTag(Position pos) {
        if (pos == null || pos.getChar() != '<')
            return null;
        final StringBuilder sb = new StringBuilder();
        sb.append('<');
        char quoteChar = 0;
        while (pos.next()) {
            final char c = pos.getChar();
            sb.append(c);
            if (quoteChar != 0) {
                if (c == quoteChar)
                    quoteChar = 0;
            } else {
                if (c == '\'' || c == '"')
                    quoteChar = c;
                else if (c == '>') {
                    pos.next();
                    break;
                }
            }
        }
        return sb.toString();
    }
}
