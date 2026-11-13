/*
 * CSSFormatter.java
 *
 * Copyright (C) 2002 Peter Graves
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

package org.armedbear.j.mode.css;

import java.util.Optional;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.LexicalCursor;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.util.Utilities;

public final class CSSFormatter extends Formatter {
    // States (used in line flags).
    static final int CSS_STATE_QUOTE = 0x0001;
    static final int CSS_STATE_SINGLEQUOTE = 0x0002;
    static final int CSS_STATE_COMMENT = 0x0004;
    private static final int CSS_STATE_BRACE = 0x0008;
    private static final int CSS_STATE_IN_BLOCK = 0x0010;
    private static final int CSS_STATE_NUMBER = 0x0020;
    private static final int CSS_STATE_PROPERTY = 0x0040;
    private static final int CSS_STATE_VALUE = 0x0080;
    private static final int CSS_STATE_SELECTOR = 0x0100;

    // Formats.
    private static final int CSS_FORMAT_TEXT = 0;
    static final int CSS_FORMAT_COMMENT = 1;
    static final int CSS_FORMAT_STRING = 2;
    private static final int CSS_FORMAT_PROPERTY = 3;
    private static final int CSS_FORMAT_BRACE = 4;
    private static final int CSS_FORMAT_NUMBER = 5;
    private static final int CSS_FORMAT_SELECTOR = 6;

    private static final CSSMode mode = CSSMode.getMode();

    public CSSFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    private int tokenBegin = 0;

    private void endToken(String text, int tokenEnd, int state) {
        if (tokenEnd - tokenBegin > 0) {
            int format = CSS_FORMAT_TEXT;
            if ((state & (CSS_STATE_QUOTE | CSS_STATE_SINGLEQUOTE)) != 0)
                format = CSS_FORMAT_STRING;
            else if ((state & CSS_STATE_COMMENT) != 0)
                format = CSS_FORMAT_COMMENT;
            else if ((state & CSS_STATE_BRACE) != 0)
                format = CSS_FORMAT_BRACE;
            else if ((state & CSS_STATE_NUMBER) != 0)
                format = CSS_FORMAT_NUMBER;
            else if ((state & CSS_STATE_PROPERTY) != 0)
                format = CSS_FORMAT_PROPERTY;
            else if ((state & CSS_STATE_VALUE) != 0)
                format = CSS_FORMAT_TEXT;
            else if ((state & CSS_STATE_SELECTOR) != 0)
                format = CSS_FORMAT_SELECTOR;
            addSegment(text, tokenBegin, tokenEnd, format);
            tokenBegin = tokenEnd;
        }
    }

    private void parseLine(Line line) {
        final String text;
        if (Editor.tabsAreVisible())
            text = Utilities.makeTabsVisible(line.getText(), buffer.getTabWidth());
        else
            text = Utilities.detab(line.getText(), buffer.getTabWidth());
        tokenBegin = 0;
        int state = line.flags();
        int i = 0;
        final int limit = text.length();
        // Skip whitespace at start of line.
        while (i < limit) {
            if (Character.isWhitespace(text.charAt(i)))
                ++i;
            else {
                endToken(text, i, state);
                break;
            }
        }
        while (i < limit) {
            final char c = text.charAt(i);
            // Comment first. A CSS comment has no escapes, so a
            // backslash inside one is literal and must not hide the
            // */ closer: /* C:\dir\*/ closes at the */.
            if ((state & CSS_STATE_COMMENT) != 0) {
                if (i < limit - 1 && c == '*' && text.charAt(i + 1) == '/') {
                    endToken(text, i + 2, state);
                    state &= ~CSS_STATE_COMMENT;
                    i += 2;
                } else
                    ++i;
                continue;
            }
            // Outside a comment, a backslash escapes the next character.
            if (c == '\\' && i < limit - 1) {
                i += 2;
                continue;
            }
            if ((state & CSS_STATE_QUOTE) != 0) {
                if (c == '"') {
                    endToken(text, i + 1, state);
                    state &= ~CSS_STATE_QUOTE;
                }
                ++i;
                continue;
            }
            if ((state & CSS_STATE_SINGLEQUOTE) != 0) {
                if (c == '\'') {
                    endToken(text, i + 1, state);
                    state &= ~CSS_STATE_SINGLEQUOTE;
                }
                ++i;
                continue;
            }
            // Reaching here, we're not in a comment or a quoted string.
            if (c == '"') {
                endToken(text, i, state);
                state |= CSS_STATE_QUOTE;
                ++i;
                continue;
            }
            if (c == '\'') {
                endToken(text, i, state);
                state |= CSS_STATE_SINGLEQUOTE;
                ++i;
                continue;
            }
            if (c == '/') {
                if (i < limit - 1) {
                    if (text.charAt(i + 1) == '*') {
                        endToken(text, i, state);
                        state |= CSS_STATE_COMMENT;
                        i += 2;
                    } else
                        ++i;
                } else
                    ++i;
                continue;
            }
            if (c == '{') {
                endToken(text, i, state);
                endToken(text, ++i, CSS_STATE_BRACE);
                state |= CSS_STATE_IN_BLOCK;
                continue;
            }
            if (c == '}') {
                endToken(text, i, state);
                endToken(text, ++i, CSS_STATE_BRACE);
                state &= ~CSS_STATE_IN_BLOCK;
                continue;
            }
            if ((state & CSS_STATE_IN_BLOCK) != 0) {
                if ((state & CSS_STATE_NUMBER) != 0) {
                    boolean isNumeric;
                    if ("0123456789".indexOf(c) >= 0) {
                        // Definitely a number.
                        isNumeric = true;
                    } else if ("abcdefABCDEF".indexOf(c) >= 0) {
                        if (c == 'e' && i < limit - 1 && text.charAt(i + 1) == 'm') {
                            // Not a number ("em").
                            isNumeric = false;
                        } else {
                            // Hex digit.
                            isNumeric = true;
                        }
                    } else
                        isNumeric = false;
                    if (!isNumeric) {
                        // Not a number.
                        endToken(text, i, state);
                        state &= ~CSS_STATE_NUMBER;
                    }
                    ++i;
                    continue;
                }
                if ((state & CSS_STATE_VALUE) != 0) {
                    if (c == '#' || Character.isDigit(c)) {
                        endToken(text, i, state);
                        state |= CSS_STATE_NUMBER;
                        ++i;
                        continue;
                    }
                    if (c == '-' && i < limit - 1 && Character.isDigit(text.charAt(i + 1))) {
                        endToken(text, i, state);
                        state |= CSS_STATE_NUMBER;
                        i += 2;
                        continue;
                    }
                    if (c == ';') {
                        // End of value.
                        endToken(text, i, state);
                        state &= ~CSS_STATE_VALUE;
                        ++i;
                        continue;
                    }
                    ++i;
                    continue;
                }
                if (c == ':') {
                    endToken(text, i, CSS_STATE_PROPERTY);
                    state |= CSS_STATE_VALUE;
                    ++i;
                    continue;
                }
            }
            if ((state & CSS_STATE_SELECTOR) != 0) {
                if (!mode.isIdentifierPart(c)) {
                    endToken(text, i, state);
                    state &= ~CSS_STATE_SELECTOR;
                }
                ++i;
                continue;
            }
            if (state == 0) {
                if (mode.isIdentifierStart(c)) {
                    endToken(text, i, state);
                    state |= CSS_STATE_SELECTOR;
                }
            }
            ++i;
        }
        // Reached end of line.
        endToken(text, i, state);
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        if (line == null) {
            addSegment("", CSS_FORMAT_TEXT);
            return segmentList;
        }
        parseLine(line);
        return segmentList;
    }

    @Override
    public boolean parseBuffer() {
        int state = 0;
        Line line = buffer.getFirstLine();
        boolean changed = false;
        while (line != null) {
            int oldflags = line.flags();
            // Quoted strings can't span lines.
            state &= ~(CSS_STATE_QUOTE | CSS_STATE_SINGLEQUOTE);
            if (state != oldflags) {
                line.setFlags(state);
                changed = true;
            }
            // The cursor is the single source of the comment, quote and
            // block transitions. parseBuffer takes the carried comment
            // and block bits from its end state instead of re-walking
            // the line with its own copy of the rules, which is how the
            // two drifted apart before.
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
    // bulk query that secretly loops point queries would accumulate
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
     * The single source of CSS lexical transitions. Walks the line from
     * an explicit entering state to {@code upto}, tracking the comment,
     * quote and block bits. A block comment carries across lines; a
     * quoted string does not, so the caller clears the quote bits from
     * the entering state between lines. Both quote kinds and escapes are
     * handled, and the walk resumes NEUTRAL after a complete closer.
     * With an output array it records the context at every offset
     * (bulk); without one it stops at {@code upto} (point).
     *
     * <p>{@code endState} is the full state after the walk, so
     * {@link #parseBuffer} takes its carried comment and block bits
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
                // Comment first. A CSS comment has no escapes, so a
                // backslash inside one is literal and must not hide the
                // */ closer: /* C:\dir\*/ closes at the */.
                if ((state & CSS_STATE_COMMENT) != 0) {
                    if (c == '*' && i < limit - 1 && text.charAt(i + 1) == '/') {
                        if (inside(2, ctx)) {
                            endState = state;
                            return ctx;
                        }
                        i += 2;
                        state &= ~CSS_STATE_COMMENT;
                    } else
                        ++i;
                    continue;
                }
                // Outside a comment, a backslash escapes the next
                // character, so \" does not close a string.
                if (c == '\\' && i < limit - 1) {
                    if (inside(2, ctx)) {
                        endState = state;
                        return ctx;
                    }
                    i += 2;
                    continue;
                }
                if ((state & CSS_STATE_QUOTE) != 0) {
                    if (c == '"')
                        state &= ~CSS_STATE_QUOTE;
                    ++i;
                    continue;
                }
                if ((state & CSS_STATE_SINGLEQUOTE) != 0) {
                    if (c == '\'')
                        state &= ~CSS_STATE_SINGLEQUOTE;
                    ++i;
                    continue;
                }
                // Neutral.
                if (c == '/' && i < limit - 1 && text.charAt(i + 1) == '*') {
                    if (inside(2, ctx)) {
                        endState = state;
                        return ctx;
                    }
                    state |= CSS_STATE_COMMENT;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    state |= CSS_STATE_QUOTE;
                    ++i;
                    continue;
                }
                if (c == '\'') {
                    state |= CSS_STATE_SINGLEQUOTE;
                    ++i;
                    continue;
                }
                if (c == '{') {
                    state |= CSS_STATE_IN_BLOCK;
                    ++i;
                    continue;
                }
                if (c == '}') {
                    state &= ~CSS_STATE_IN_BLOCK;
                    ++i;
                    continue;
                }
                ++i;
            }
            endState = state;
            return toContext(state);
        }
    }

    private static LexicalContext toContext(int state) {
        if ((state & CSS_STATE_COMMENT) != 0)
            return LexicalContext.COMMENT;
        if ((state & (CSS_STATE_QUOTE | CSS_STATE_SINGLEQUOTE)) != 0)
            return LexicalContext.STRING;
        return LexicalContext.NEUTRAL;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("CSSMode");
            formatTable.addEntryFromPrefs(CSS_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(CSS_FORMAT_COMMENT, "comment");
            formatTable.addEntryFromPrefs(CSS_FORMAT_STRING, "string");
            formatTable.addEntryFromPrefs(CSS_FORMAT_PROPERTY, "property", "keyword");
            formatTable.addEntryFromPrefs(CSS_FORMAT_BRACE, "brace");
            formatTable.addEntryFromPrefs(CSS_FORMAT_NUMBER, "number");
            formatTable.addEntryFromPrefs(CSS_FORMAT_SELECTOR, "selector", "function");
        }
        return formatTable;
    }
}
