/*
 * XmlFormatter.java
 *
 * Copyright (C) 1998-2003 Peter Graves
 * Copyright (C) 2009-2026 Kevin Krouse
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

package org.armedbear.j.mode.xml;

import static org.armedbear.j.Constants.*;

import org.armedbear.j.Buffer;
import org.armedbear.j.Escapes;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.LexicalCursor;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Position;
import java.util.Optional;

public final class XmlFormatter extends Formatter {
    private static final byte XML_FORMAT_TEXT = 0;
    static final byte XML_FORMAT_COMMENT = 1;
    private static final byte XML_FORMAT_DELIMITER = 2;
    private static final byte XML_FORMAT_NAMESPACE = 3;
    private static final byte XML_FORMAT_TAG = 4;
    private static final byte XML_FORMAT_ATTRIBUTE = 5;
    private static final byte XML_FORMAT_EQUALS = 6;
    static final byte XML_FORMAT_QUOTE = 7;
    private static final byte XML_FORMAT_CDATA = 8;
    private static final byte XML_FORMAT_ESCAPE = 9;
    private static final byte XML_FORMAT_ESCAPE_ERROR = 10;

    // The carried markup state is the low byte; the high bits mark a line
    // that starts inside a construct whose text takes no references: a
    // processing instruction or a DOCTYPE internal subset.
    static final int XML_STATE_MASK = 0xff;
    private static final int XML_PI = 0x100;
    private static final int XML_DTD = 0x200;

    private static final byte STATE_NAMESPACE = STATE_LAST + 1;
    private static final byte STATE_TAG_STARTING = STATE_LAST + 2;
    private static final byte STATE_TAG_ENDING = STATE_LAST + 3;
    // In a tag after its name; XmlMode indents a line that starts here as an attribute.
    static final byte STATE_ATTRIBUTE = STATE_LAST + 4;
    private static final byte STATE_EQUALS = STATE_LAST + 5;
    private static final byte STATE_DOCTYPE = STATE_LAST + 6;
    private static final byte STATE_DOCTYPE_QUOTE = STATE_LAST + 7;
    private static final byte STATE_DOCTYPE_SINGLEQUOTE = STATE_LAST + 8;

    private StringBuilder sb = new StringBuilder();

    // The line being formatted, and how much of it the segments so far
    // cover. Tokens are emitted in order and together span the line, so
    // this is where the next one begins.
    private String text = "";
    private int emitted = 0;

    // Nonzero while the token being built is inside a construct that
    // takes no references: a DOCTYPE internal subset or a processing
    // instruction.
    private int suppressed = 0;

    public XmlFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    private void endToken(int state) {
        if (sb.length() > 0) {
            final int begin = emitted;
            final int end = begin + sb.length();
            emitted = end;
            byte format;
            switch (state) {
                case STATE_COMMENT:
                    format = XML_FORMAT_COMMENT;
                    break;
                case STATE_TAG_STARTING:
                case STATE_TAG_ENDING:
                    format = XML_FORMAT_DELIMITER;
                    break;
                case STATE_EQUALS:
                    format = XML_FORMAT_EQUALS;
                    break;
                case STATE_NAMESPACE:
                    format = XML_FORMAT_NAMESPACE;
                    break;
                case STATE_TAG:
                    format = XML_FORMAT_TAG;
                    break;
                case STATE_ATTRIBUTE:
                    format = XML_FORMAT_ATTRIBUTE;
                    break;
                case STATE_QUOTE:
                case STATE_SINGLEQUOTE:
                case STATE_DOCTYPE_QUOTE:
                case STATE_DOCTYPE_SINGLEQUOTE:
                    format = XML_FORMAT_QUOTE;
                    break;
                case STATE_CDATA:
                    format = XML_FORMAT_CDATA;
                    break;
                case STATE_NEUTRAL:
                default:
                    format = XML_FORMAT_TEXT;
                    break;
            }
            // Only character data and attribute values take references.
            // Comments, CDATA, processing instructions and DOCTYPE
            // contents are skipped: a DOCTYPE's text is declaration, not
            // character data, even though it shares the text format.
            if (suppressed != 0)
                addSegment(text, begin, end, format);
            else if (state == STATE_NEUTRAL)
                addReferences(begin, end, begin, end, format);
            else if (state == STATE_QUOTE || state == STATE_SINGLEQUOTE) {
                // The quotes keep the base format; only the value splits.
                // A token that opens on this line starts with its quote;
                // one carried from the previous line starts with content.
                final char first = sb.charAt(0);
                final char last = sb.charAt(sb.length() - 1);
                final boolean opensHere = first == '"' || first == '\'';
                final boolean closesHere = (last == '"' || last == '\'')
                        && !(opensHere && sb.length() == 1);
                addReferences(begin, end,
                        opensHere ? begin + 1 : begin,
                        closesHere ? end - 1 : end,
                        format);
            }
            else
                addSegment(text, begin, end, format);
            sb.setLength(0);
        }
    }

    // The token [emitBegin, emitEnd) in base format, with the references
    // in [scanBegin, scanEnd) colored escape or escapeError. A scan range
    // with no ampersand leaves the token whole.
    private void addReferences(int emitBegin, int emitEnd, int scanBegin, int scanEnd, int base) {
        // Bound the precheck to the token: scanning the whole remaining
        // line for every token is quadratic on reference-free documents.
        if (!hasAmpersand(text, scanBegin, scanEnd)) {
            addSegment(text, emitBegin, emitEnd, base);
            return;
        }
        addEscapedSegment(text, emitBegin, emitEnd,
            Escapes.scanReferences(text, scanBegin, scanEnd, Escapes.Reference.XML),
            base, XML_FORMAT_ESCAPE, XML_FORMAT_ESCAPE_ERROR);
    }

    private static boolean hasAmpersand(String s, int from, int to) {
        for (int i = from; i < to; i++)
            if (s.charAt(i) == '&')
                return true;
        return false;
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        if (line != null)
            parseLine(line);
        else
            addSegment("", XML_FORMAT_TEXT);
        return segmentList;
    }

    private void parseLine(Line line) {
        text = getDetabbedText(line);
        emitted = 0;
        final int flags = line.flags();
        int state = flags & XML_STATE_MASK;
        suppressed = flags & ~XML_STATE_MASK;
        sb.setLength(0);
        int i = 0;
        final int limit = text.length();
        while (i < limit) {
            char c = text.charAt(i);
            if (state == STATE_COMMENT) {
                if (i < limit - 2 && text.substring(i, i + 3).equals("-->")) {
                    sb.append("-->");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    i += 3;
                } else {
                    sb.append(c);
                    ++i;
                }
                continue;
            }
            if (state == STATE_CDATA) {
                if (c == ']') {
                    if (text.regionMatches(i, "]]>", 0, 3)) {
                        endToken(state);
                        sb.append("]]");
                        endToken(STATE_TAG);
                        sb.append('>');
                        endToken(STATE_TAG_ENDING);
                        state = STATE_NEUTRAL;
                        i += 3;
                        continue;
                    }
                }
                sb.append(c);
                ++i;
                continue;
            }
            if (isDoctypeState(state)) {
                // Only an unquoted '[' opens the internal subset. A '['
                // inside a quoted system identifier is literal text.
                if (c == '[' && state == STATE_DOCTYPE)
                    suppressed |= XML_DTD;
                final int nextState = doctypeStateAfter(state, c);
                if (nextState != state && state == STATE_DOCTYPE)
                    endToken(state);
                sb.append(c);
                if (nextState != state && state != STATE_DOCTYPE)
                    endToken(state);
                state = nextState;
                ++i;
                continue;
            }
            // The closer of a suppressed DOCTYPE subset ends the
            // suppression for the rest of the line. Flush the text
            // accumulated before the closer while still suppressed, so a
            // reference in the same token is not picked up.
            if ((suppressed & XML_DTD) != 0 && (suppressed & XML_PI) == 0
                    && c == ']'
                    && state != STATE_QUOTE && state != STATE_SINGLEQUOTE
                    && text.regionMatches(i, "]>", 0, 2)) {
                endToken(state);
                suppressed &= ~XML_DTD;
            }
            // While a processing instruction is open, `>` is ordinary
            // content and only `?>` ends it. A PI that spans lines
            // re-enters in NEUTRAL state (the `>` of a prior line ended
            // the tag), where the tag branches below never see the
            // terminator. Recognize `?>` here so the suppression ends
            // with the PI instead of leaking over the rest of the
            // document. A bare `>` stays content, keeping the tag state
            // so the `?>` handlers below remain reachable.
            if ((suppressed & XML_PI) != 0
                    && state != STATE_QUOTE && state != STATE_SINGLEQUOTE) {
                if (state == STATE_NEUTRAL && c == '?'
                        && text.regionMatches(i, "?>", 0, 2)) {
                    endToken(state);
                    suppressed &= ~XML_PI;
                    sb.append("?>");
                    endToken(STATE_TAG_ENDING);
                    i += 2;
                    continue;
                }
                if (c == '>') {
                    sb.append(c);
                    ++i;
                    continue;
                }
            }
            if (state == STATE_TAG_STARTING) {
                if (c == '/' || c == '?') {
                    if (c == '?')
                        suppressed |= XML_PI;
                    sb.append(c);
                    endToken(state);
                    state = STATE_NAMESPACE;
                    ++i;
                    continue;
                }
                if (c == '!') {
                    if (text.regionMatches(i, "![CDATA[", 0, 8)) {
                        sb.append(c);
                        endToken(state);
                        sb.append("[CDATA[");
                        endToken(STATE_TAG);
                        state = STATE_CDATA;
                        i += 8;
                        continue;
                    }
                    if (text.regionMatches(i, "!DOCTYPE", 0, 8)) {
                        sb.append(c);
                        endToken(state);
                        sb.append("DOCTYPE");
                        endToken(STATE_TAG);
                        state = STATE_DOCTYPE;
                        i += 8;
                        continue;
                    }
                    sb.append(c);
                    endToken(state);
                    state = STATE_TAG;
                    ++i;
                    continue;
                }
                endToken(state);
                state = STATE_NAMESPACE;
                sb.append(c);
                ++i;
                continue;
            }
            if (state == STATE_NAMESPACE) {
                if (c == '/' && text.regionMatches(i, "/>", 0, 2)) {
                    // It wasn't really a namespace.
                    endToken(STATE_TAG);
                    state = STATE_TAG_ENDING;
                    sb.append("/>");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    i += 2;
                    continue;
                }
                if (c == '?' && text.regionMatches(i, "?>", 0, 2)) {
                    // Processing instruction.
                    endToken(STATE_TAG);
                    state = STATE_TAG_ENDING;
                    sb.append("?>");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    suppressed &= ~XML_PI;
                    i += 2;
                    continue;
                }
                if (c == ':') {
                    sb.append(c);
                    endToken(state);
                    state = STATE_TAG;
                } else if (isWhitespace(c)) {
                    // It wasn't really a namespace.
                    endToken(STATE_TAG);
                    state = STATE_ATTRIBUTE;
                    sb.append(c);
                } else if (c == '>') {
                    // It wasn't really a namespace.
                    endToken(STATE_TAG);
                    state = STATE_TAG_ENDING;
                    sb.append(c);
                    endToken(state);
                    state = STATE_NEUTRAL;
                } else
                    sb.append(c);
                ++i;
                continue;
            }
            if (state == STATE_TAG) {
                if (c == '/' && text.regionMatches(i, "/>", 0, 2)) {
                    endToken(state);
                    state = STATE_TAG_ENDING;
                    sb.append("/>");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    i += 2;
                    continue;
                }
                if (c == '?' && text.regionMatches(i, "?>", 0, 2)) {
                    // Processing instruction.
                    endToken(STATE_TAG);
                    state = STATE_TAG_ENDING;
                    sb.append("?>");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    suppressed &= ~XML_PI;
                    i += 2;
                    continue;
                }
                if (isWhitespace(c)) {
                    endToken(state);
                    state = STATE_ATTRIBUTE;
                    sb.append(c);
                } else if (c == '>') {
                    endToken(state);
                    state = STATE_TAG_ENDING;
                    sb.append(c);
                    endToken(state);
                    state = STATE_NEUTRAL;
                } else
                    sb.append(c);
                ++i;
                continue;
            }
            if (state == STATE_ATTRIBUTE) {
                if (c == '/' && text.regionMatches(i, "/>", 0, 2)) {
                    endToken(state);
                    state = STATE_TAG_ENDING;
                    sb.append("/>");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    i += 2;
                    continue;
                }
                if (c == '?' && text.regionMatches(i, "?>", 0, 2)) {
                    // Processing instruction.
                    endToken(state);
                    state = STATE_TAG_ENDING;
                    sb.append("?>");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    suppressed &= ~XML_PI;
                    i += 2;
                    continue;
                }
                if (c == '>') {
                    endToken(state);
                    state = STATE_TAG_ENDING;
                    sb.append(c);
                    endToken(state);
                    state = STATE_NEUTRAL;
                    ++i;
                    continue;
                }
                if (c == '=') {
                    endToken(state);
                    state = STATE_EQUALS;
                    sb.append(c);
                    endToken(state);
                    state = STATE_ATTRIBUTE;
                    ++i;
                    continue;
                }
                if (c == '"') {
                    endToken(state);
                    state = STATE_QUOTE;
                    sb.append(c);
                    ++i;
                    continue;
                }
                if (c == '\'') {
                    endToken(state);
                    state = STATE_SINGLEQUOTE;
                    sb.append(c);
                    ++i;
                    continue;
                }
                sb.append(c);
                ++i;
                continue;
            }
            if (state == STATE_QUOTE) {
                sb.append(c);
                if (c == '"') {
                    endToken(state);
                    state = STATE_ATTRIBUTE;
                }
                ++i;
                continue;
            }
            if (state == STATE_SINGLEQUOTE) {
                sb.append(c);
                if (c == '\'') {
                    endToken(state);
                    state = STATE_ATTRIBUTE;
                }
                ++i;
                continue;
            }
            // Not in comment or tag.
            if (c == '<') {
                endToken(state);
                if (text.regionMatches(i, "<!--", 0, 4)) {
                    state = STATE_COMMENT;
                    sb.append("<!--");
                    i += 4;
                    continue;
                }
                state = STATE_TAG_STARTING;
                sb.append(c);
            } else
                sb.append(c);
            ++i;
        }
        // Reached end of line.
        if (state == STATE_NAMESPACE)
            // It wasn't really a namespace.
            endToken(STATE_TAG);
        else
            endToken(state);
    }

    @Override
    public boolean parseBuffer() {
        int state = STATE_NEUTRAL;
        int marker = 0;
        Line line = buffer.getFirstLine();
        Position pos = new Position(line, 0);
        boolean changed = false;
        while (line != null) {
            int oldflags = line.flags();
            if ((state | marker) != oldflags) {
                line.setFlags(state | marker);
                changed = true;
            }
            final int limit = line.length();
            for (int i = 0; i < limit; i++) {
                char c = line.charAt(i);
                if (state == STATE_COMMENT) {
                    if (c == '-') {
                        pos.moveTo(line, i);
                        if (pos.lookingAt("-->")) {
                            state = STATE_NEUTRAL;
                            i += 2;
                            continue;
                        }
                    }
                    continue;
                }
                if (state == STATE_CDATA) {
                    if (c == ']') {
                        pos.moveTo(line, i);
                        if (pos.lookingAt("]]>")) {
                            state = STATE_NEUTRAL;
                            i += 2;
                            continue;
                        }
                    }
                    continue;
                }
                // A DOCTYPE internal subset or a processing instruction
                // carries a marker that suppresses references on its
                // lines, while its inner markup still colors as tags.
                // The marker is a bitmask: a PI inside a subset sets
                // both bits, and each closer clears only its own.
                if ((marker & XML_DTD) != 0 && (marker & XML_PI) == 0
                        && c == ']'
                        && state != STATE_QUOTE && state != STATE_SINGLEQUOTE) {
                    pos.moveTo(line, i);
                    if (pos.lookingAt("]>"))
                        marker &= ~XML_DTD;
                }
                if ((marker & XML_PI) != 0 && c == '?'
                        && state != STATE_QUOTE && state != STATE_SINGLEQUOTE) {
                    pos.moveTo(line, i);
                    if (pos.lookingAt("?>"))
                        marker &= ~XML_PI;
                }
                if (isDoctypeState(state)) {
                    // Only an unquoted '[' opens the internal subset.
                    if (c == '[' && state == STATE_DOCTYPE)
                        marker |= XML_DTD;
                    state = doctypeStateAfter(state, c);
                    continue;
                }
                if (state == STATE_TAG) {
                    if (!isWhitespace(c)) {
                        // OK, we shouldn't really be in STATE_ATTRIBUTE just
                        // because we've seen one non-whitespace character
                        // after the opening '<'. But if the line ends before
                        // the '>', we don't want the next line to start in
                        // STATE_TAG.
                        state = STATE_ATTRIBUTE;
                        continue;
                    }
                }
                if (state == STATE_ATTRIBUTE) {
                    if (c == '>')
                        state = STATE_NEUTRAL;
                    else if (c == '"')
                        state = STATE_QUOTE;
                    else if (c == '\'')
                        state = STATE_SINGLEQUOTE;
                    continue;
                }
                if (state == STATE_QUOTE) {
                    if (c == '"')
                        state = STATE_ATTRIBUTE;
                    continue;
                }
                if (state == STATE_SINGLEQUOTE) {
                    if (c == '\'')
                        state = STATE_ATTRIBUTE;
                    continue;
                }
                // Neutral state.
                if (c == '<') {
                    pos.moveTo(line, i);
                    if (pos.lookingAt("<!--")) {
                        state = STATE_COMMENT;
                        i += 3;
                        continue;
                    }
                    if (pos.lookingAt("<![CDATA[")) {
                        state = STATE_CDATA;
                        i += 8;
                        continue;
                    }
                    if (pos.lookingAt("<!DOCTYPE")) {
                        state = STATE_DOCTYPE;
                        i += 8;
                        continue;
                    }
                    if (pos.lookingAt("<?")) {
                        marker |= XML_PI;
                        state = STATE_TAG;
                        i += 1;
                        continue;
                    }
                    state = STATE_TAG;
                    continue;
                }
            }
            line = line.next();
        }
        buffer.setNeedsParsing(false);
        return changed;
    }

    private static final boolean isWhitespace(char c) {
        return c <= ' ';
    }

    private static boolean isDoctypeState(int state) {
        return state == STATE_DOCTYPE || state == STATE_DOCTYPE_QUOTE
                || state == STATE_DOCTYPE_SINGLEQUOTE;
    }

    // DOCTYPE external identifiers are quoted literals: quotes protect '>'
    // and '[' inside them. An unquoted '[' starts the internal subset,
    // handled by the ordinary markup states.
    private static int doctypeStateAfter(int state, char c) {
        if (state == STATE_DOCTYPE_QUOTE)
            return c == '"' ? STATE_DOCTYPE : state;
        if (state == STATE_DOCTYPE_SINGLEQUOTE)
            return c == '\'' ? STATE_DOCTYPE : state;
        if (c == '"')
            return STATE_DOCTYPE_QUOTE;
        if (c == '\'')
            return STATE_DOCTYPE_SINGLEQUOTE;
        return c == '[' || c == '>' ? STATE_NEUTRAL : state;
    }

    @Override
    public Optional<LexicalContext> lexicalContextAt(Line line, int offset) {
        final int upto = Math.min(offset, line.length());
        Cursor cursor = new Cursor(line, line.flags(), upto, null);
        LexicalContext ctx = cursor.run();
        scanSteps += cursor.steps;
        return Optional.of(ctx);
    }

    @Override
    public Optional<LexicalContext[]> lexicalContexts(Line line, int end) {
        final int upto = Math.min(end, line.length());
        final LexicalContext[] out = new LexicalContext[upto + 1];
        Cursor cursor = new Cursor(line, line.flags(), upto, out);
        cursor.run();
        scanSteps += cursor.steps;
        return Optional.of(out);
    }

    // Total characters the lexical queries have scanned, across every
    // cursor run since the last reset. The scan guard reads this.
    long scanSteps;

    /**
     * The single source of XML lexical context for the pairing query,
     * mirroring {@link #parseBuffer}'s carried-state machine so cursor and
     * highlighting cannot drift. Mapping: comment → COMMENT; attribute value
     * or DOCTYPE literal → STRING; CDATA → OPAQUE; tag, attribute name,
     * ordinary text → NEUTRAL.
     *
     * <p>A prose apostrophe in text is NEUTRAL: only a quote reached from
     * inside a tag or DOCTYPE opens a string. Multi-character markers use
     * {@code covers} so the caret on a marker reads as inside the construct
     * and the caret just past a complete closer ({@code -->}, {@code ]]>)
     * reads as outside. With an output array it records every offset
     * (bulk); without one it stops at {@code upto} (point).
     */
    static final class Cursor extends LexicalCursor {
        private int state;
        long steps;

        Cursor(Line line, int enteringState, int upto, LexicalContext[] out) {
            super(line, upto, out);
            this.state = enteringState & XML_STATE_MASK;
        }

        LexicalContext run() {
            while (i <= upto) {
                final LexicalContext ctx = toContext(state);
                if (out != null)
                    out[i] = ctx;
                if (i == upto)
                    return ctx;
                steps++;
                final char c = text.charAt(i);
                if (state == STATE_COMMENT) {
                    if (c == '-' && text.regionMatches(i, "-->", 0, 3)) {
                        if (covers(i, i + 2, LexicalContext.COMMENT))
                            return LexicalContext.COMMENT;
                        state = STATE_NEUTRAL;
                        i += 3;
                    } else
                        ++i;
                    continue;
                }
                if (state == STATE_CDATA) {
                    if (c == ']' && text.regionMatches(i, "]]>", 0, 3)) {
                        if (covers(i, i + 2, LexicalContext.OPAQUE))
                            return LexicalContext.OPAQUE;
                        state = STATE_NEUTRAL;
                        i += 3;
                    } else
                        ++i;
                    continue;
                }
                if (isDoctypeState(state)) {
                    state = doctypeStateAfter(state, c);
                    ++i;
                    continue;
                }
                if (state == STATE_QUOTE) {
                    if (c == '"')
                        state = STATE_ATTRIBUTE;
                    ++i;
                    continue;
                }
                if (state == STATE_SINGLEQUOTE) {
                    if (c == '\'')
                        state = STATE_ATTRIBUTE;
                    ++i;
                    continue;
                }
                if (state == STATE_TAG) {
                    if (!isWhitespace(c))
                        state = STATE_ATTRIBUTE;
                    ++i;
                    continue;
                }
                if (state == STATE_ATTRIBUTE) {
                    if (c == '>')
                        state = STATE_NEUTRAL;
                    else if (c == '"')
                        state = STATE_QUOTE;
                    else if (c == '\'')
                        state = STATE_SINGLEQUOTE;
                    ++i;
                    continue;
                }
                // Neutral.
                if (c == '<') {
                    if (text.regionMatches(i, "<!--", 0, 4)) {
                        if (covers(i + 1, i + 3, LexicalContext.COMMENT))
                            return LexicalContext.COMMENT;
                        state = STATE_COMMENT;
                        i += 4;
                        continue;
                    }
                    if (text.regionMatches(i, "<![CDATA[", 0, 9)) {
                        if (covers(i + 1, i + 8, LexicalContext.OPAQUE))
                            return LexicalContext.OPAQUE;
                        state = STATE_CDATA;
                        i += 9;
                        continue;
                    }
                    if (text.startsWith("<!DOCTYPE", i)) {
                        if (inside(9, LexicalContext.NEUTRAL))
                            return LexicalContext.NEUTRAL;
                        state = STATE_DOCTYPE;
                        i += 9;
                        continue;
                    }
                    state = STATE_TAG;
                    ++i;
                    continue;
                }
                ++i;
            }
            return toContext(state);
        }

        private static LexicalContext toContext(int state) {
            if (state == STATE_COMMENT)
                return LexicalContext.COMMENT;
            if (state == STATE_CDATA)
                return LexicalContext.OPAQUE;
            if (state == STATE_QUOTE || state == STATE_SINGLEQUOTE
                    || state == STATE_DOCTYPE_QUOTE || state == STATE_DOCTYPE_SINGLEQUOTE)
                return LexicalContext.STRING;
            return LexicalContext.NEUTRAL;
        }
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("XmlMode");
            formatTable.addEntryFromPrefs(XML_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(XML_FORMAT_COMMENT, "comment");
            formatTable.addEntryFromPrefs(XML_FORMAT_DELIMITER, "delimiter");
            formatTable.addEntryFromPrefs(XML_FORMAT_NAMESPACE, "namespace");
            formatTable.addEntryFromPrefs(XML_FORMAT_TAG, "tag");
            formatTable.addEntryFromPrefs(XML_FORMAT_ATTRIBUTE, "attribute");
            formatTable.addEntryFromPrefs(XML_FORMAT_EQUALS, "equals", "delimiter");
            formatTable.addEntryFromPrefs(XML_FORMAT_QUOTE, "string");
            formatTable.addEntryFromPrefs(XML_FORMAT_CDATA, "cdata", "text");
            formatTable.addEntryFromPrefs(XML_FORMAT_ESCAPE, "escape");
            formatTable.addEntryFromPrefs(XML_FORMAT_ESCAPE_ERROR, "escapeError");
        }
        return formatTable;
    }
}
