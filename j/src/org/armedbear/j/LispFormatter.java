/*
 * LispFormatter.java
 *
 * Copyright (C) 1998-2002 Peter Graves
 * $Id: LispFormatter.java,v 1.2 2002-10-15 00:25:48 piso Exp $
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

package org.armedbear.j;

public final class LispFormatter extends Formatter
{
    // Formats.
    private static final int LISP_FORMAT_TEXT     = 0;
    private static final int LISP_FORMAT_COMMENT  = 1;
    private static final int LISP_FORMAT_STRING   = 2;
    private static final int LISP_FORMAT_KEYWORD  = 3;
    private static final int LISP_FORMAT_FUNCTION = 4;
    private static final int LISP_FORMAT_NUMBER   = 5;

    private final Mode mode;

    public LispFormatter(Buffer buffer)
    {
        this.buffer = buffer;
        this.mode = buffer.getMode();
    }

    private int tokenBegin = 0;

    private void endToken(String text, int tokenEnd, int state)
    {
        if (tokenEnd - tokenBegin > 0) {
            int format = -1;
            switch (state) {
                case STATE_NEUTRAL:
                    break;
                case STATE_QUOTE:
                    format = LISP_FORMAT_STRING;
                    break;
                case STATE_IDENTIFIER:
                    break;
                case STATE_COMMENT:
                    format = LISP_FORMAT_COMMENT;
                    break;
                case STATE_NUMBER:
                case STATE_HEXNUMBER:
                    format = LISP_FORMAT_NUMBER;
                    break;
            }
            addSegment(text, tokenBegin, tokenEnd, format);
            tokenBegin = tokenEnd;
        }
    }

    private void parseLine(Line line)
    {
        final String text = getDetabbedText(line);
        tokenBegin = 0;
        int state = line.flags();
        clearSegmentList();
        final int limit = text.length();
        int i = 0;
        // Skip whitespace at start of line.
        while (i < limit) {
            if (Character.isWhitespace(text.charAt(i))) {
                ++i;
            } else {
                endToken(text, i, state);
                break;
            }
        }
        while (i < limit) {
            char c = text.charAt(i);
            if (c == '\\' && i < limit-1) {
                i += 2;
                continue;
            }
            if (state == STATE_COMMENT) {
                if (c == '|' && i < limit-1) {
                    c = text.charAt(i+1);
                    if (c == '#') {
                        endToken(text, i, state);
                        state = STATE_NEUTRAL;
                        i += 2;
                        continue;
                    }
                }
                ++i;
                continue;
            }
            if (state == STATE_QUOTE) {
                if (c == '"') {
                    endToken(text, i, state);
                    state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            // Reaching here, we're not in a comment or quoted string.
            if (c == '"') {
                endToken(text, i, state);
                state = STATE_QUOTE;
                ++i;
                continue;
            }
            if (c == ';') {
                endToken(text, i, state);
                endToken(text, limit, STATE_COMMENT);
                return;
            }
            if (c == '#' && i < limit-1) {
                if (text.charAt(i+1) == '|') {
                    endToken(text, i, state);
                    state = STATE_COMMENT;
                    i += 2;
                    continue;
                }
            }
            if (state == STATE_IDENTIFIER) {
                if (!mode.isIdentifierPart(c)) {
                    endToken(text, i, state);
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
                else if (i - tokenBegin == 1 && c == 'x' || c == 'X')
                    state = STATE_HEXNUMBER;
                else {
                    endToken(text, i, state);
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
                    ;
                else if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
                    ;
                else if (c == 'u' || c == 'U' || c == 'l' || c == 'L')
                    ;
                else {
                    endToken(text, i, state);
                    if (Character.isJavaIdentifierStart(c))
                        state = STATE_IDENTIFIER;
                    else
                        state = STATE_NEUTRAL;
                }
                ++i;
                continue;
            }
            if (state == STATE_NEUTRAL) {
                if (mode.isIdentifierStart(c)) {
                    endToken(text, i, state);
                    state = STATE_IDENTIFIER;
                } else if (Character.isDigit(c)) {
                    endToken(text, i, state);
                    state = STATE_NUMBER;
                } else // Still neutral...
                    ;
            }
            ++i;
        }
        endToken(text, i, state);
    }

    public LineSegmentList formatLine(Line line)
    {
        if (line == null) {
            clearSegmentList();
            addSegment("", LISP_FORMAT_TEXT);
            return segmentList;
        }
        parseLine(line);
        final int size = segmentList.size();
        for (int i = 0; i < size; i++) {
            LineSegment segment = segmentList.getSegment(i);
            if (segment.getFormat() >= 0)
                continue;
            String token = segment.getText();
            if (isKeyword(token))
                segment.setFormat(LISP_FORMAT_KEYWORD);
            else {
                boolean isFunction = false;
                if (i >= 2) {
                    LineSegment prevSegment = segmentList.getSegment(i-2);
                    String prevToken = prevSegment.getText();
                    String trim = prevToken.trim();
                    if (trim.startsWith("def")) {
                        if (trim.equals("defun") ||
                            trim.equals("defvar") ||
                            trim.equals("defmacro") ||
                            trim.equals("defcustom") ||
                            trim.equals("defgroup")) {
                            isFunction = true;
                        }
                    }
                }
                segment.setFormat(isFunction ? LISP_FORMAT_FUNCTION : LISP_FORMAT_TEXT);
            }
        }
        return segmentList;
    }

    public boolean parseBuffer()
    {
        int state = STATE_NEUTRAL;
        boolean changed = false;
        Line line = buffer.getFirstLine();
        while (line != null) {
            int oldflags = line.flags();
            if (state != oldflags) {
                line.setFlags(state);
                changed = true;
            }
            int limit = line.length();
            for (int i = 0; i < limit; i++) {
                char c = line.charAt(i);
                if (c == '\\' && i < limit-1) {
                    // Escape.
                    ++i;
                    continue;
                }
                if (state == STATE_COMMENT) {
                    if (c == '|' && i < limit-1 && line.charAt(i+1) == '#') {
                        ++i;
                        state = STATE_NEUTRAL;
                    }
                    continue;
                }
                if (state == STATE_QUOTE) {
                    if (c == '"')
                        state = STATE_NEUTRAL;
                    continue;
                }
                // Not in comment or quoted string.
                if (c == ';') {
                    // Single-line comment beginning. Ignore rest of line.
                    break;
                }
                if (c == '#') {
                    if (i < limit-1 && line.charAt(i+1) == '|') {
                        state = STATE_COMMENT;
                        ++i;
                    }
                    continue;
                }
                if (c == '"')
                    state = STATE_QUOTE;
            }
            line = line.next();
        }
        buffer.setNeedsParsing(false);
        return changed;
    }

    public FormatTable getFormatTable()
    {
        if (formatTable == null) {
            formatTable = new FormatTable("LispMode");
            formatTable.addEntryFromPrefs(LISP_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(LISP_FORMAT_COMMENT, "comment");
            formatTable.addEntryFromPrefs(LISP_FORMAT_STRING, "string");
            formatTable.addEntryFromPrefs(LISP_FORMAT_KEYWORD, "keyword");
            formatTable.addEntryFromPrefs(LISP_FORMAT_FUNCTION, "function");
            formatTable.addEntryFromPrefs(LISP_FORMAT_NUMBER, "number");
        }
        return formatTable;
    }
}
