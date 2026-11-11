/*
 * JavaSyntaxIterator.java
 *
 * Copyright (C) 1998-2004 Peter Graves
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

package org.armedbear.j.mode.java;

import static org.armedbear.j.Constants.*;

import org.armedbear.j.DefaultSyntaxIterator;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

// Supports movement through the syntactically important text of a buffer, i.e.
// skipping whitespace and comments.
public final class JavaSyntaxIterator extends DefaultSyntaxIterator {
    public JavaSyntaxIterator(Position pos) {
        super(pos);
    }

    // Caller must make sure parseBuffer() has been called so flags will be
    // correct.
    @Override
    public char[] hideSyntacticWhitespace(Line line) {
        if (line.flags() == STATE_COMMENT)
            return hideSyntacticWhitespace(line.getText(), STATE_COMMENT);
        if (line.flags() == STATE_QUOTE)
            return hideSyntacticWhitespace(line.getText(), STATE_QUOTE);
        if (line.flags() == STATE_TEXT_BLOCK)
            return hideTextBlock(line.getText());
        return hideSyntacticWhitespace(line.getText(), STATE_NEUTRAL);
    }

    // A text block line is string content up to its closing """. Everything
    // through the closing delimiter becomes 'X', so a brace or quote inside
    // the block is invisible to brace matching; whatever follows the close
    // is ordinary code.
    private char[] hideTextBlock(String s) {
        final char[] chars = s.toCharArray();
        final int length = chars.length;
        for (int i = 0; i < length; i++) {
            if (chars[i] == '\\' && i + 1 < length) {
                chars[i] = 'X';
                chars[i + 1] = 'X';
                ++i;
                continue;
            }
            if (chars[i] == '"' && i + 2 < length
                    && chars[i + 1] == '"' && chars[i + 2] == '"') {
                chars[i] = 'X';
                chars[i + 1] = 'X';
                chars[i + 2] = 'X';
                // Past the close: ordinary code.
                final char[] rest = hideSyntacticWhitespace(
                    s.substring(i + 3), STATE_NEUTRAL);
                System.arraycopy(rest, 0, chars, i + 3, rest.length);
                return chars;
            }
            chars[i] = 'X';
        }
        return chars;
    }

    @Override
    public char[] hideSyntacticWhitespace(String s) {
        return hideSyntacticWhitespace(s, STATE_NEUTRAL);
    }

    // Replaces comments with space characters and double-quoted strings with
    // 'X' characters.
    private char[] hideSyntacticWhitespace(String s, int initialState) {
        final char[] chars = s.toCharArray();
        final int length = chars.length;
        if (length > 0 && chars[0] == '#') {
            // Preprocessor line.
            for (int i = length; i-- > 0;)
                chars[i] = ' ';
            return chars;
        }
        int state = initialState;
        for (int i = 0; i < length; i++) {
            char c = chars[i];
            if (c == '\\' && i < length - 1) {
                // Escape character.
                chars[i++] = ' ';
                chars[i] = ' ';
                continue;
            }
            if (state == STATE_QUOTE) {
                chars[i] = 'X';
                if (c == '"')
                    state = STATE_NEUTRAL;
                continue;
            }
            if (state == STATE_SINGLEQUOTE) {
                chars[i] = ' ';
                if (c == '\'')
                    state = STATE_NEUTRAL;
                continue;
            }
            if (state == STATE_COMMENT) {
                if (c == '*' && i < length - 1 && chars[i + 1] == '/') {
                    // /* */ comment ending
                    chars[i++] = ' ';
                    chars[i] = ' ';
                    state = STATE_NEUTRAL;
                } else
                    chars[i] = ' ';
                continue;
            }

            // Reaching here, STATE_NEUTRAL...
            if (c == '"') {
                chars[i] = ' ';
                state = STATE_QUOTE;
                continue;
            }
            if (c == '\'') {
                chars[i] = ' ';
                state = STATE_SINGLEQUOTE;
                continue;
            }
            if (c == '/') {
                if (i < length - 1) {
                    if (chars[i + 1] == '*') {
                        // /* */ comment starting
                        chars[i++] = ' ';
                        chars[i] = ' ';
                        state = STATE_COMMENT;
                        continue;
                    }
                    if (chars[i + 1] == '/') {
                        // "//" comment starting
                        for (int j = i; j < length; j++)
                            chars[j] = ' ';
                        return chars;
                    }
                }
            }
        }
        return chars;
    }
}
