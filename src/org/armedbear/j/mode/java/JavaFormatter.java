/*
 * JavaFormatter.java
 *
 * Copyright (C) 1998-2004 Peter Graves
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

package org.armedbear.j.mode.java;

import static org.armedbear.j.Constants.*;

import org.armedbear.j.Buffer;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSegmentList;

/**
 * Java's and JavaScript's formatter, and the C family's base: CFormatter adds
 * preprocessor lines and #if 0 blocks.
 */
public class JavaFormatter extends Formatter {
    protected static final int JAVA_FORMAT_TEXT = 0;
    protected static final int JAVA_FORMAT_COMMENT = 1;
    protected static final int JAVA_FORMAT_STRING = 2;
    protected static final int JAVA_FORMAT_IDENTIFIER = 3;
    protected static final int JAVA_FORMAT_KEYWORD = 4;
    protected static final int JAVA_FORMAT_FUNCTION = 5;
    protected static final int JAVA_FORMAT_OPERATOR = 6;
    protected static final int JAVA_FORMAT_BRACE = 7;
    protected static final int JAVA_FORMAT_NUMBER = 8;

    /** The last format JavaFormatter uses; CFormatter and HtmlFormatter number theirs after it. */
    public static final int JAVA_FORMAT_LAST = 8;

    private final int language;

    public JavaFormatter(Buffer buffer) {
        this(buffer, LANGUAGE_JAVA);
    }

    public JavaFormatter(Buffer buffer, int language) {
        this.buffer = buffer;
        this.language = language;
    }

    /** Whether lines starting with '#' are preprocessor directives, as in C. */
    protected boolean hasPreprocessor() {
        return false;
    }

    /** Whether a string open at the end of a line continues on the next. */
    protected boolean quoteContinues(boolean backslashAtEnd) {
        return language != LANGUAGE_JAVA;
    }

    protected boolean isOperatorChar(char c) {
        return "!&|<>=+/*-^".indexOf(c) >= 0;
    }

    /** The format for a token in state; a subclass maps states of its own. */
    protected int format(int state) {
        switch (state) {
            case STATE_QUOTE:
                return JAVA_FORMAT_STRING;
            case STATE_IDENTIFIER:
                return JAVA_FORMAT_IDENTIFIER;
            case STATE_COMMENT:
                return JAVA_FORMAT_COMMENT;
            case STATE_OPERATOR:
                return JAVA_FORMAT_OPERATOR;
            case STATE_BRACE:
                return JAVA_FORMAT_BRACE;
            case STATE_NUMBER:
            case STATE_HEXNUMBER:
                return JAVA_FORMAT_NUMBER;
            default:
                return JAVA_FORMAT_TEXT;
        }
    }

    private int tokenBegin = 0;

    private void endToken(String text, int tokenEnd, int state) {
        if (tokenEnd - tokenBegin > 0) {
            addSegment(text, tokenBegin, tokenEnd, format(state));
            tokenBegin = tokenEnd;
        }
    }

    private boolean isIdentifierStart(char c) {
        return getLanguageMode().isIdentifierStart(c);
    }

    private boolean isIdentifierPart(char c) {
        return getLanguageMode().isIdentifierPart(c);
    }

    protected void parseLine(Line line) {
        final String text = getDetabbedText(line);
        tokenBegin = 0;
        boolean isPreprocessorLine = false;
        char quoteChar = '\0';
        int state = line.flags();
        if (state == STATE_QUOTE)
            quoteChar = '"';
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
        char c;
        // A preprocessor directive's '#' is the first non-whitespace character.
        if (hasPreprocessor() && i < limit && state == STATE_NEUTRAL && text.charAt(i) == '#') {
            state = STATE_PREPROCESSOR;
            isPreprocessorLine = true;
            ++i;
            while (i < limit && (Character.isWhitespace(c = text.charAt(i)) || c == '#'))
                ++i;
            while (i < limit && (c = text.charAt(i)) >= 'a' && c <= 'z')
                ++i;
            endToken(text, i, state);
            state = STATE_NEUTRAL;
        }
        if (state == STATE_SCRIPT)
            state = STATE_NEUTRAL;
        while (i < limit) {
            c = text.charAt(i);
            if (state == STATE_COMMENT) {
                if (i < limit - 1 && c == '*' && text.charAt(i + 1) == '/') {
                    endToken(text, i + 2, state);
                    state = STATE_NEUTRAL;
                    i += 2;
                } else
                    ++i;
                continue;
            }
            if (state == STATE_QUOTE) {
                if (c == quoteChar) {
                    endToken(text, i + 1, state);
                    state = STATE_NEUTRAL;
                } else if (c == '\\' && i < limit - 1) {
                    // Escape char.
                    ++i;
                }
                ++i;
                continue;
            }
            // Reaching here, we're not in a comment or a quoted string.
            if (c == '"' || c == '\'') {
                endToken(text, i, state);
                state = STATE_QUOTE;
                quoteChar = c;
                ++i;
                continue;
            }
            if (c == '/') {
                if (i < limit - 1) {
                    if (text.charAt(i + 1) == '*') {
                        endToken(text, i, state);
                        state = STATE_COMMENT;
                        i += 2;
                    } else if (text.charAt(i + 1) == '/') {
                        endToken(text, i, state);
                        endToken(text, limit, STATE_COMMENT);
                        return;
                    } else
                        ++i;
                } else
                    ++i;
                continue;
            }
            if (!isPreprocessorLine && isOperatorChar(c)) {
                if (state != STATE_OPERATOR) {
                    endToken(text, i, state);
                    // Check for keyword (as in e.g. "char*").
                    LineSegment segment = getLastSegment();
                    if (segment != null && isKeyword(segment.getText()))
                        segment.setFormat(JAVA_FORMAT_KEYWORD);
                    state = STATE_OPERATOR;
                }
                ++i;
                continue;
            }
            if (c == '{' || c == '}') {
                if (state != STATE_BRACE) {
                    endToken(text, i, state);
                    // Check for keyword (e.g. "try").
                    LineSegment segment = getLastSegment();
                    if (!isPreprocessorLine && segment != null && isKeyword(segment.getText()))
                        segment.setFormat(JAVA_FORMAT_KEYWORD);
                    state = STATE_BRACE;
                }
                ++i;
                continue;
            }
            if (state == STATE_OPERATOR || state == STATE_BRACE) {
                endToken(text, i, state);
                if (isIdentifierStart(c))
                    state = STATE_IDENTIFIER;
                else if (Character.isDigit(c))
                    state = STATE_NUMBER;
                else
                    state = STATE_NEUTRAL;
                ++i;
                continue;
            }
            if (state == STATE_IDENTIFIER) {
                if (!isIdentifierPart(c)) {
                    endToken(text, i, state);
                    // Check for keyword or function.
                    LineSegment segment = getLastSegment();
                    if (segment != null) {
                        String segmentText = segment.getText();
                        if (!isPreprocessorLine && isKeyword(segmentText))
                            segment.setFormat(JAVA_FORMAT_KEYWORD);
                        else if (c == '(')
                            segment.setFormat(JAVA_FORMAT_FUNCTION);
                        else if (Character.isWhitespace(c)) {
                            // Look ahead to see if next non-whitespace char is '('.
                            int j = i + 1;
                            while (j < limit && Character.isWhitespace(c = text.charAt(j)))
                                ++j;
                            if (c == '(')
                                segment.setFormat(JAVA_FORMAT_FUNCTION);
                        }
                    }
                    state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_NUMBER) {
                if (Character.isDigit(c))
                    ;
                else if (c == 'u' || c == 'U' || c == 'l' || c == 'L')
                    ;
                else if (i - tokenBegin == 1 && (c == 'x' || c == 'X'))
                    state = STATE_HEXNUMBER;
                else {
                    endToken(text, i, state);
                    state = isIdentifierStart(c) ? STATE_IDENTIFIER : STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_HEXNUMBER) {
                if (Character.isDigit(c))
                    ;
                else if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
                    ;
                else if (c == 'u' || c == 'U' || c == 'l' || c == 'L')
                    ;
                else {
                    endToken(text, i, state);
                    state = isIdentifierStart(c) ? STATE_IDENTIFIER : STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_NEUTRAL) {
                if (isIdentifierStart(c)) {
                    endToken(text, i, state);
                    state = STATE_IDENTIFIER;
                } else if (Character.isDigit(c)) {
                    endToken(text, i, state);
                    state = STATE_NUMBER;
                }
            }
            ++i;
        }
        // Reached end of line.
        endToken(text, i, state);
        if (state == STATE_IDENTIFIER && !isPreprocessorLine) {
            // Last token might be a keyword.
            LineSegment segment = getLastSegment();
            if (segment != null && isKeyword(segment.getText()))
                segment.setFormat(JAVA_FORMAT_KEYWORD);
        }
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        if (line == null)
            addSegment("", JAVA_FORMAT_TEXT);
        else
            parseLine(line);
        return segmentList;
    }

    /**
     * If line starts a block the language disables, as C's #if 0, the first
     * line past it (null at the end of the buffer); otherwise line itself.
     */
    protected Line endOfDisabledBlock(Line line) {
        return line;
    }

    @Override
    public boolean parseBuffer() {
        int state = STATE_NEUTRAL;
        boolean backslashAtEnd = false;
        Line line = buffer.getFirstLine();
        boolean changed = false;
        while (line != null) {
            if (state == STATE_QUOTE && !quoteContinues(backslashAtEnd))
                state = STATE_NEUTRAL;
            if (state == STATE_NEUTRAL) {
                Line end = endOfDisabledBlock(line);
                if (end != line) {
                    for (; line != end; line = line.next()) {
                        if (line.flags() != STATE_DISABLED) {
                            line.setFlags(STATE_DISABLED);
                            changed = true;
                        }
                    }
                    continue;
                }
            }
            if (state != line.flags()) {
                line.setFlags(state);
                changed = true;
            }
            char quoteChar = state == STATE_QUOTE ? '"' : '\0';
            final int limit = line.length();
            char c = '\0';
            for (int i = 0; i < limit; i++) {
                c = line.charAt(i);
                if (c == '\\' && i < limit - 1) {
                    // Escape.
                    ++i;
                    continue;
                }
                if (state == STATE_COMMENT) {
                    if (c == '*' && i < limit - 1) {
                        c = line.charAt(i + 1);
                        if (c == '/') {
                            ++i;
                            state = STATE_NEUTRAL;
                        }
                    }
                    continue;
                }
                if (state == STATE_QUOTE) {
                    if (c == quoteChar) {
                        state = STATE_NEUTRAL;
                        quoteChar = '\0';
                    }
                    continue;
                }
                // Not in comment or quoted string.
                if (c == '/' && i < limit - 1) {
                    c = line.charAt(++i);
                    if (c == '/') {
                        // Single-line comment beginning.
                        // Ignore rest of line.
                        break;
                    } else if (c == '*')
                        state = STATE_COMMENT;
                } else if (c == '"' || c == '\'') {
                    state = STATE_QUOTE;
                    quoteChar = c;
                }
            }
            backslashAtEnd = c == '\\';
            line = line.next();
        }
        buffer.setNeedsParsing(false);
        return changed;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            // Shared with JavaScript: JavaMode.color.* colors both.
            formatTable = new FormatTable("JavaMode");
            addEntries(formatTable);
        }
        return formatTable;
    }

    protected static void addEntries(FormatTable table) {
        table.addEntryFromPrefs(JAVA_FORMAT_TEXT, "text");
        table.addEntryFromPrefs(JAVA_FORMAT_COMMENT, "comment");
        table.addEntryFromPrefs(JAVA_FORMAT_STRING, "string");
        table.addEntryFromPrefs(JAVA_FORMAT_IDENTIFIER, "identifier", "text");
        table.addEntryFromPrefs(JAVA_FORMAT_KEYWORD, "keyword");
        table.addEntryFromPrefs(JAVA_FORMAT_FUNCTION, "function");
        table.addEntryFromPrefs(JAVA_FORMAT_OPERATOR, "operator");
        table.addEntryFromPrefs(JAVA_FORMAT_BRACE, "brace");
        table.addEntryFromPrefs(JAVA_FORMAT_NUMBER, "number");
    }
}
