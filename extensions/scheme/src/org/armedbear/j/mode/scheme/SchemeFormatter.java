/*
 * SchemeFormatter.java
 *
 * Copyright (C) 1998-2002 Peter Graves
 * Copyright (C) 2012-2026 Kevin Krouse
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

package org.armedbear.j.mode.scheme;

import static org.armedbear.j.Constants.*;

import java.util.Optional;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.LexicalCursor;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.util.Utilities;

public final class SchemeFormatter extends Formatter {
    // Formats.
    private static final int SCHEME_FORMAT_TEXT = 0;
    static final int SCHEME_FORMAT_COMMENT = 1;
    static final int SCHEME_FORMAT_STRING = 2;
    private static final int SCHEME_FORMAT_KEYWORD = 3;
    private static final int SCHEME_FORMAT_FUNCTION = 4;
    private static final int SCHEME_FORMAT_NUMBER = 5;

    private StringBuilder sb = new StringBuilder();
    private int tokStart;

    public SchemeFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    private void endToken(int state) {
        if (sb.length() > 0) {
            int format = -1;
            switch (state) {
                case STATE_NEUTRAL:
                    break;
                case STATE_QUOTE:
                    format = SCHEME_FORMAT_STRING;
                    break;
                case STATE_IDENTIFIER:
                    break;
                case STATE_COMMENT:
                    format = SCHEME_FORMAT_COMMENT;
                    break;
                case STATE_NUMBER:
                case STATE_HEXNUMBER:
                    format = SCHEME_FORMAT_NUMBER;
                    break;
            }
            addSegment(sb.toString(), format);
            tokStart += sb.length();
            sb.setLength(0);
        }
    }

    private void parseLine(String text, int state) {
        if (Editor.tabsAreVisible())
            text = Utilities.makeTabsVisible(text, buffer.getTabWidth());
        else
            text = Utilities.detab(text, buffer.getTabWidth());
        clearSegmentList();
        sb.setLength(0);
        int i = 0;
        tokStart = 0;
        final int limit = text.length();
        // Skip whitespace at start of line.
        while (i < limit) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
                ++i;
            } else {
                endToken(state);
                break;
            }
        }
        while (i < limit) {
            char c = text.charAt(i);
            // Comment first. A Scheme block comment has no escapes, so
            // a backslash inside one is literal and must not hide the
            // |# closer: #| \|# closes at the |#.
            if (state == STATE_COMMENT) {
                if (c == '|' && i < limit - 1 && text.charAt(i + 1) == '#') {
                    sb.append("|#");
                    endToken(state);
                    state = STATE_NEUTRAL;
                    i += 2;
                    continue;
                }
                sb.append(c);
                ++i;
                continue;
            }
            // Outside a comment, a backslash escapes the next character.
            if (c == '\\' && i < limit - 1) {
                sb.append(c);
                sb.append(text.charAt(++i));
                ++i;
                continue;
            }
            if (state == STATE_QUOTE) {
                sb.append(c);
                if (c == '"') {
                    endToken(state);
                    state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            // Reaching here, we're not in a quoted string.
            if (c == '"') {
                endToken(state);
                sb.append(c);
                state = STATE_QUOTE;
                ++i;
                continue;
            }
            if (c == ';') {
                endToken(state);
                state = STATE_COMMENT;
                sb.append(text.substring(i));
                endToken(state);
                return;
            }
            if (c == '#' && i < limit - 1) {
                if (text.charAt(i + 1) == '|') {
                    endToken(state);
                    state = STATE_COMMENT;
                    sb.append("#|");
                    i += 2;
                    continue;
                }
            }
            if (state == STATE_IDENTIFIER) {
                if (getLanguageMode().isIdentifierPart(c))
                    sb.append(c);
                else {
                    endToken(state);
                    sb.append(c);
                    state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_NUMBER) {
                if (Character.isDigit(c))
                    sb.append(c);
                else if (c == 'u' || c == 'U' || c == 'l' || c == 'L')
                    sb.append(c); // Valid suffix.
                else if (sb.length() == 1 && (c == 'x' || c == 'X')) {
                    sb.append(c);
                    state = STATE_HEXNUMBER;
                } else {
                    endToken(state);
                    sb.append(c);
                    if (Character.isJavaIdentifierStart(c))
                        state = STATE_IDENTIFIER;
                    else
                        state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_HEXNUMBER) {
                if (Character.isDigit(c))
                    sb.append(c);
                else if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
                    sb.append(c);
                else if (c == 'u' || c == 'U' || c == 'l' || c == 'L')
                    sb.append(c); // Valid suffix.
                else {
                    endToken(state);
                    sb.append(c);
                    if (Character.isJavaIdentifierStart(c))
                        state = STATE_IDENTIFIER;
                    else
                        state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_NEUTRAL) {
                if (getLanguageMode().isIdentifierStart(c)) {
                    endToken(state);
                    sb.append(c);
                    state = STATE_IDENTIFIER;
                } else if (Character.isDigit(c)) {
                    endToken(state);
                    sb.append(c);
                    state = STATE_NUMBER;
                } else // Still neutral...
                    sb.append(c);
            }
            ++i;
        }
        endToken(state);
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        if (line == null) {
            clearSegmentList();
            addSegment("", SCHEME_FORMAT_TEXT);
            return segmentList;
        }
        parseLine(line.getText(), line.flags());
        final int size = segmentList.size();
        for (int i = 0; i < size; i++) {
            LineSegment segment = segmentList.getSegment(i);
            if (segment.getFormat() >= 0)
                continue;
            String token = segment.getText();
            if (isKeyword(token))
                segment.setFormat(SCHEME_FORMAT_KEYWORD);
            else {
                boolean isFunction = false;
                if (i >= 2) {
                    LineSegment prevSegment = segmentList.getSegment(i - 2);
                    String prevToken = prevSegment.getText();
                    String trim = prevToken.trim();
                    if (trim.equals("define"))
                        isFunction = true;
                }
                segment.setFormat(isFunction ? SCHEME_FORMAT_FUNCTION : SCHEME_FORMAT_TEXT);
            }
        }
        return segmentList;
    }

    @Override
    public boolean parseBuffer() {
        int state = STATE_NEUTRAL;
        Line line = buffer.getFirstLine();
        boolean changed = false;
        while (line != null) {
            int oldflags = line.flags();
            if (state != oldflags) {
                line.setFlags(state);
                changed = true;
            }
            // The cursor is the single source of the comment, quote and
            // block transitions. parseBuffer takes the carried comment
            // and quote bits from its end state instead of re-walking
            // the line with its own copy of the rules. A Scheme string
            // and a block comment both carry across lines, so neither
            // bit is cleared between lines.
            Cursor cursor = new Cursor(line, state, line.length(), null);
            cursor.run();
            state = cursor.endState;
            line = line.next();
        }
        buffer.setNeedsParsing(false);
        return changed;
    }

    // Total characters the lexical queries have scanned, across every
    // cursor run since the last reset. The scan guard reads this through
    // the real mode.lexicalContexts / lexicalContext entry points: a
    // bulk query that secretly looped point queries would accumulate
    // O(len^2) here and blow the linear bound, which a hand-built
    // single cursor cannot show.
    long scanSteps;

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

    /**
     * The single source of Scheme lexical transitions. Walks the line
     * from an explicit entering state to {@code upto}, tracking the
     * comment and quote bits. A block comment and a quoted string both
     * carry across lines through the entering state. The comment state
     * is checked before the escape, because a Scheme block comment has
     * no escapes: a backslash inside one is literal and must not hide
     * the {@code |#} closer. Outside a comment a backslash escapes the
     * next character, so {@code \"} does not open a string and
     * {@code \;} does not start a comment. A {@code ;} line comment
     * makes the rest of the line COMMENT but does not carry to the next
     * line. With an output array it records the context at every offset
     * (bulk); without one it stops at {@code upto} (point).
     *
     * <p>{@code endState} is the full state after the walk, so
     * {@link #parseBuffer} takes its carried comment and quote bits
     * from here rather than re-implementing the transitions.
     */
    static final class Cursor extends LexicalCursor {
        private int state;
        int endState;
        long steps;

        Cursor(Line line, int enteringState, int upto, LexicalContext[] out) {
            super(line, upto, out);
            this.state = enteringState;
        }

        LexicalContext run() {
            while (i <= upto) {
                final LexicalContext ctx = toContext(state);
                if (out != null)
                    out[i] = ctx;
                if (i == upto) {
                    endState = state;
                    return ctx;
                }
                steps++;
                final char c = text.charAt(i);
                // Comment first. A Scheme block comment has no escapes,
                // so a backslash inside one is literal and must not
                // hide the |# closer: #| \|# closes at the |#.
                if (state == STATE_COMMENT) {
                    if (c == '|' && i < limit - 1 && text.charAt(i + 1) == '#') {
                        if (inside(2, ctx)) {
                            endState = state;
                            return ctx;
                        }
                        i += 2;
                        state = STATE_NEUTRAL;
                    } else
                        ++i;
                    continue;
                }
                // Outside a comment, a backslash escapes the next
                // character, so \" does not open a string and \; does
                // not start a comment.
                if (c == '\\' && i < limit - 1) {
                    if (inside(2, ctx)) {
                        endState = state;
                        return ctx;
                    }
                    i += 2;
                    continue;
                }
                if (state == STATE_QUOTE) {
                    if (c == '"')
                        state = STATE_NEUTRAL;
                    ++i;
                    continue;
                }
                // Neutral.
                if (c == '"') {
                    state = STATE_QUOTE;
                    ++i;
                    continue;
                }
                if (c == ';') {
                    // A line comment: the caret on the ';' is before
                    // it, everything after is COMMENT. It does not
                    // carry to the next line, so endState stays NEUTRAL.
                    if (out != null)
                        for (int j = i + 1; j <= upto; j++)
                            out[j] = LexicalContext.COMMENT;
                    endState = STATE_NEUTRAL;
                    return LexicalContext.COMMENT;
                }
                if (c == '#' && i < limit - 1 && text.charAt(i + 1) == '|') {
                    if (inside(2, ctx)) {
                        endState = state;
                        return ctx;
                    }
                    state = STATE_COMMENT;
                    i += 2;
                    continue;
                }
                ++i;
            }
            endState = state;
            return toContext(state);
        }
    }

    private static LexicalContext toContext(int state) {
        if (state == STATE_COMMENT)
            return LexicalContext.COMMENT;
        if (state == STATE_QUOTE)
            return LexicalContext.STRING;
        return LexicalContext.NEUTRAL;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("SchemeMode");
            formatTable.addEntryFromPrefs(SCHEME_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(SCHEME_FORMAT_COMMENT, "comment");
            formatTable.addEntryFromPrefs(SCHEME_FORMAT_STRING, "string");
            formatTable.addEntryFromPrefs(SCHEME_FORMAT_KEYWORD, "keyword");
            formatTable.addEntryFromPrefs(SCHEME_FORMAT_FUNCTION, "function");
            formatTable.addEntryFromPrefs(SCHEME_FORMAT_NUMBER, "number");
        }
        return formatTable;
    }
}
