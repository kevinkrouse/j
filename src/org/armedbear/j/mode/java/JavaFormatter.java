/*
 * JavaFormatter.java
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

import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.armedbear.j.Buffer;
import org.armedbear.j.Escapes;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.LexicalCursor;
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
    protected static final int JAVA_FORMAT_ANNOTATION = 9;
    protected static final int JAVA_FORMAT_ESCAPE = 10;
    protected static final int JAVA_FORMAT_ESCAPE_ERROR = 11;

    /** The last format JavaFormatter uses; CFormatter and HtmlFormatter number theirs after it. */
    public static final int JAVA_FORMAT_LAST = 11;

    private final int language;

    public JavaFormatter(Buffer buffer) {
        this(buffer, LANGUAGE_JAVA);
    }

    public JavaFormatter(Buffer buffer, int language) {
        this.buffer = buffer;
        this.language = language;
    }

    /**
     * The context at {@code offset}, consuming the line from its
     * entering flags with the same transitions {@link #parseBuffer}
     * uses to write those flags. The offset is checked before each
     * character is consumed, so a closing quote or comment marker
     * consumed before the caret yields NEUTRAL and the caret inside a
     * string or comment yields STRING or COMMENT. A disabled block
     * reads as OPAQUE: pairing has no business inside {@code #if 0}.
     * Raw text, real offsets.
     *
     * <p>The point query stops at the boundary without building a
     * prefix-sized array; the bulk query fills every offset. Both run
     * the same {@link Cursor} transitions, so the two cannot drift.
     */
    @Override
    public Optional<LexicalContext> lexicalContextAt(Line line, int offset) {
        final int upto = Math.min(offset, line.length());
        return Optional.of(new Cursor(line, upto, null, supportsTextBlocks()).run());
    }

    /**
     * One forward pass recording the context at every offset to
     * {@code end}, so a caller that needs many positions on the line
     * pays for one scan. The transitions are the same as
     * {@link #parseBuffer}'s; the state is recorded before each token
     * is consumed, and the position between a two-character token's
     * characters carries the state before the token, exactly as the
     * per-offset query answers.
     */
    @Override
    public Optional<LexicalContext[]> lexicalContexts(Line line, int end) {
        final int upto = Math.min(end, line.length());
        final LexicalContext[] out = new LexicalContext[upto + 1];
        new Cursor(line, upto, out, supportsTextBlocks()).run();
        return Optional.of(out);
    }

    /**
     * The single source of Java lexical transitions. Walks the line
     * from its entering flags to {@code upto}. With an output array it
     * records the context at every offset (bulk); without one it stops
     * at {@code upto} and returns the context there (point), never
     * allocating the prefix. {@code steps} counts token advances so a
     * test can assert the walk stays linear in the line's length.
     */
    static final class Cursor extends LexicalCursor {
        private int state;
        private char quoteChar;
        private final boolean textBlocks;
        long steps;

        Cursor(Line line, int upto, LexicalContext[] out, boolean textBlocks) {
            super(line, upto, out);
            this.state = line.flags();
            this.quoteChar = state == STATE_QUOTE ? '"' : '\0';
            this.textBlocks = textBlocks;
        }

        LexicalContext run() {
            // The cursor reports the raw comment/string membership of
            // the line, walking its tokens from the entering flag. A
            // disabled line enters as STATE_DISABLED, which is none of
            // the comment or quote states, so the walk treats it as
            // ordinary text and still finds a /* */ or "..." on it.
            // That is what isInComment needs: a bracket inside a
            // comment in an #if 0 block must still be skipped by %
            // matching. The pairing view collapses the whole disabled
            // line to OPAQUE at Mode.lexicalContext, not here.
            while (i <= upto) {
                final LexicalContext ctx = toContext(state);
                if (out != null)
                    out[i] = ctx;
                if (i == upto)
                    return ctx;
                steps++;
                final char c = text.charAt(i);
                // A two-character token (an escape, or a comment
                // marker) with the caret between its characters: the
                // caret is still in the state before the token.
                if (c == '\\' && i < limit - 1) {
                    if (inside(2, ctx))
                        return ctx;
                    i += 2;
                    continue;
                }
                if (state == STATE_COMMENT) {
                    if (c == '*' && i < limit - 1 && text.charAt(i + 1) == '/') {
                        if (inside(2, ctx))
                            return ctx;
                        i += 2;
                        state = STATE_NEUTRAL;
                    } else
                        ++i;
                    continue;
                }
                if (state == STATE_QUOTE) {
                    if (c == quoteChar) {
                        state = STATE_NEUTRAL;
                        quoteChar = '\0';
                    }
                    ++i;
                    continue;
                }
                if (state == STATE_TEXT_BLOCK) {
                    if (c == '"' && text.regionMatches(i, "\"\"\"", 0, 3)) {
                        if (inside(3, LexicalContext.STRING))
                            return LexicalContext.STRING;
                        i += 3;
                        state = STATE_NEUTRAL;
                    } else
                        ++i;
                    continue;
                }
                // Not in comment or quoted string.
                if (c == '/' && i < limit - 1) {
                    final char next = text.charAt(i + 1);
                    if (next == '/') {
                        // A line comment: the caret between the
                        // slashes is still before it, everything after
                        // is COMMENT.
                        if (inside(2, ctx))
                            return ctx;
                        if (out != null) {
                            for (int j = i + 2; j <= upto; j++)
                                out[j] = LexicalContext.COMMENT;
                            return null;
                        }
                        return LexicalContext.COMMENT;
                    }
                    if (next == '*') {
                        if (inside(2, ctx))
                            return ctx;
                        state = STATE_COMMENT;
                        i += 2;
                        continue;
                    }
                } else if (c == '"' && textBlocks && text.regionMatches(i, "\"\"\"", 0, 3)) {
                    if (inside(3, LexicalContext.STRING))
                        return LexicalContext.STRING;
                    i += 3;
                    state = STATE_TEXT_BLOCK;
                    continue;
                } else if (c == '"' || c == '\'') {
                    state = STATE_QUOTE;
                    quoteChar = c;
                }
                ++i;
            }
            return toContext(state);
        }
    }

    // A disabled line enters as STATE_DISABLED, which is none of the
    // comment or quote states, so it maps to NEUTRAL here and the walk
    // continues over the line's tokens as ordinary text. The pairing
    // collapse to OPAQUE happens in Mode.lexicalContext, not in the
    // cursor, so isInComment can still find a comment inside disabled
    // code for % matching.
    private static LexicalContext toContext(int state) {
        switch (state) {
            case STATE_QUOTE:
            case STATE_TEXT_BLOCK:
                return LexicalContext.STRING;
            case STATE_COMMENT:
                return LexicalContext.COMMENT;
            default:
                return LexicalContext.NEUTRAL;
        }
    }

    /** Whether lines starting with '#' are preprocessor directives, as in C. */
    protected boolean hasPreprocessor() {
        return false;
    }

    /** Whether '@Name' is an annotation. Java and its decorators; a subclass may opt out. */
    protected boolean supportsAnnotations() {
        return true;
    }

    /** Whether a string open at the end of a line continues on the next. */
    protected boolean quoteContinues(boolean backslashAtEnd) {
        return language != LANGUAGE_JAVA;
    }

    /** The escapes this language's ordinary strings take. */
    protected Escapes.Syntax escapeProfile() {
        switch (language) {
            case LANGUAGE_JAVASCRIPT:
                return Escapes.Syntax.JAVASCRIPT;
            case LANGUAGE_C:
                return Escapes.Syntax.C;
            default:
                return Escapes.Syntax.JAVA;
        }
    }

    /** Whether """ opens a text block, as in Java; elsewhere it is two strings, or one and a quote. */
    protected boolean supportsTextBlocks() {
        return language == LANGUAGE_JAVA;
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
            case STATE_TEXT_BLOCK:
                return JAVA_FORMAT_STRING;
            default:
                return JAVA_FORMAT_TEXT;
        }
    }

    private int tokenBegin = 0;

    // The closing delimiter the parser just consumed: 1 for a quote, 3
    // for a text block, 0 when the literal was still open.
    private int closingDelim = 0;

    // Whether the line being formatted ends at a physical line break
    // the literal continues across.
    private boolean lineEndContinues = false;

    /** Whether the string whose opening quote is at quote is raw, its body free of escapes, as C++'s R"(...)". */
    protected boolean isRawLiteralAt(String text, int quote) {
        return false;
    }

    private void endToken(String text, int tokenEnd, int state) {
        final int delim = closingDelim;
        closingDelim = 0;
        if (tokenEnd - tokenBegin <= 0)
            return;
        final int format = format(state);
        if ((state != STATE_QUOTE && state != STATE_TEXT_BLOCK) || isRawLiteralAt(text, tokenBegin)) {
            addSegment(text, tokenBegin, tokenEnd, format);
            tokenBegin = tokenEnd;
            return;
        }
        // Escapes are found in the content, the token without the closing
        // delimiter it consumed; the delimiter joins the last plain piece.
        final Escapes.Syntax syntax =
            state == STATE_TEXT_BLOCK ? Escapes.Syntax.JAVA_TEXT_BLOCK : escapeProfile();
        final List<Escapes.Span> spans =
            Escapes.scan(text, tokenBegin, tokenEnd - delim, syntax, lineContinues(state));
        addEscapedSegment(text, tokenBegin, tokenEnd, spans,
            format, JAVA_FORMAT_ESCAPE, JAVA_FORMAT_ESCAPE_ERROR);
        tokenBegin = tokenEnd;
    }

    // Whether the content ends at a physical line break the literal
    // continues across.
    private boolean lineContinues(int state) {
        if (state == STATE_TEXT_BLOCK)
            return lineEndContinues;
        return lineEndContinues && quoteContinues(true);
    }

    private boolean isIdentifierStart(char c) {
        return getLanguageMode().isIdentifierStart(c);
    }

    private boolean isIdentifierPart(char c) {
        return getLanguageMode().isIdentifierPart(c);
    }

    // A number's end: the offset one past the last character of the literal
    // starting at `start`. Handles hex (0x, with p-exponent), binary (0b),
    // decimal with a fraction and an e-exponent, underscores between digits,
    // and the suffixes: one of f/F/d/D, or a run of l/L/u/U for C's "10UL".
    private static int scanNumber(String text, int start, int limit) {
        int i = start;
        if (text.charAt(i) == '0' && i + 1 < limit && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
            i += 2;
            i = scanDigits(text, i, limit, "0123456789abcdefABCDEF_");
            if (i < limit && text.charAt(i) == '.') {
                ++i;
                i = scanDigits(text, i, limit, "0123456789abcdefABCDEF_");
            }
            if (i < limit && (text.charAt(i) == 'p' || text.charAt(i) == 'P')) {
                ++i;
                if (i < limit && (text.charAt(i) == '+' || text.charAt(i) == '-'))
                    ++i;
                i = scanDigits(text, i, limit, "0123456789_");
            }
            return scanSuffix(text, i, limit);
        }
        if (text.charAt(i) == '0' && i + 1 < limit && (text.charAt(i + 1) == 'b' || text.charAt(i + 1) == 'B')) {
            i += 2;
            i = scanDigits(text, i, limit, "01_");
            return scanSuffix(text, i, limit);
        }
        i = scanDigits(text, i, limit, "0123456789_");
        if (i < limit && text.charAt(i) == '.' && isFractionStart(text, i + 1, limit)) {
            ++i;
            i = scanDigits(text, i, limit, "0123456789_");
        }
        if (i < limit && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int save = i;
            ++i;
            if (i < limit && (text.charAt(i) == '+' || text.charAt(i) == '-'))
                ++i;
            if (i < limit && Character.isDigit(text.charAt(i)))
                i = scanDigits(text, i, limit, "0123456789_");
            else
                i = save;
        }
        return scanSuffix(text, i, limit);
    }

    // Whether the '.' before `i` belongs to the number: "1.5", "1.", "1.e5"
    // and "1.f" are numbers, but "1..2" and "1.foo" are not.
    private static boolean isFractionStart(String text, int i, int limit) {
        if (i >= limit)
            return true;
        final char c = text.charAt(i);
        final char after = i + 1 < limit ? text.charAt(i + 1) : '\0';
        if (Character.isDigit(c))
            return true;
        if (c == 'e' || c == 'E')
            return Character.isDigit(after) || after == '+' || after == '-';
        if ("fFdD".indexOf(c) >= 0)
            return !Character.isJavaIdentifierPart(after);
        return c != '.' && !Character.isJavaIdentifierStart(c);
    }

    private static int scanSuffix(String text, int i, int limit) {
        if (i < limit && "fFdD".indexOf(text.charAt(i)) >= 0)
            return i + 1;
        while (i < limit && "lLuU".indexOf(text.charAt(i)) >= 0)
            ++i;
        return i;
    }

    // Digits from `i`, with '_' allowed only between them, never trailing.
    private static int scanDigits(String text, int i, int limit, String allowed) {
        while (i < limit && allowed.indexOf(text.charAt(i)) >= 0) {
            if (text.charAt(i) == '_' && !(i + 1 < limit && allowed.indexOf(text.charAt(i + 1)) >= 0))
                break;
            ++i;
        }
        return i;
    }

    protected void parseLine(Line line) {
        final String text = getDetabbedText(line);
        lineEndContinues = line.next() != null;
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
                    closingDelim = 1;
                    endToken(text, i + 1, state);
                    state = STATE_NEUTRAL;
                } else if (c == '\\' && i < limit - 1) {
                    // Escape char.
                    ++i;
                }
                ++i;
                continue;
            }
            if (state == STATE_TEXT_BLOCK) {
                // A text block's content is all string until the closing """.
                if (c == '"' && i + 2 < limit && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                    closingDelim = 3;
                    endToken(text, i + 3, state);
                    state = STATE_NEUTRAL;
                    i += 3;
                } else if (c == '\\' && i < limit - 1) {
                    // An escape, as \" or \\, never closes the block.
                    i += 2;
                } else {
                    ++i;
                }
                continue;
            }
            // Reaching here, we're not in a comment or a quoted string.
            // After an operator or brace too: "List<@NonNull String>".
            if ((state == STATE_NEUTRAL || state == STATE_OPERATOR || state == STATE_BRACE)
                    && supportsAnnotations()
                    && !isPreprocessorLine
                    && c == '@'
                    && i + 1 < limit
                    && isIdentifierStart(text.charAt(i + 1))) {
                endToken(text, i, state);
                int j = i + 1;
                while (j < limit
                        && (isIdentifierPart(text.charAt(j))
                                || (text.charAt(j) == '.' && j + 1 < limit && isIdentifierStart(text.charAt(j + 1)))))
                    ++j;
                addSegment(text, i, j, JAVA_FORMAT_ANNOTATION);
                tokenBegin = j;
                i = j;
                state = STATE_NEUTRAL;
                continue;
            }
            // In any state left here, as after an operator: x +""".
            if (supportsTextBlocks()
                    && c == '"'
                    && i + 2 < limit
                    && text.charAt(i + 1) == '"'
                    && text.charAt(i + 2) == '"') {
                // A text block opening.
                endToken(text, i, state);
                state = STATE_TEXT_BLOCK;
                i += 3;
                continue;
            }
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
                    if (segment != null && isKeywordAt(text, segment))
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
                    if (!isPreprocessorLine && segment != null && isKeywordAt(text, segment))
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
                else if (Character.isDigit(c)) {
                    int end = scanNumber(text, i, limit);
                    addSegment(text, i, end, JAVA_FORMAT_NUMBER);
                    tokenBegin = end;
                    state = STATE_NEUTRAL;
                    i = end;
                    continue;
                } else
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
                        if (!isPreprocessorLine && isKeywordAt(text, segment))
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
            if (state == STATE_NEUTRAL) {
                if (isIdentifierStart(c)) {
                    endToken(text, i, state);
                    state = STATE_IDENTIFIER;
                } else if (Character.isDigit(c)) {
                    endToken(text, i, state);
                    int end = scanNumber(text, i, limit);
                    addSegment(text, i, end, JAVA_FORMAT_NUMBER);
                    tokenBegin = end;
                    i = end;
                    continue;
                }
            }
            ++i;
        }
        // Reached end of line.
        endToken(text, i, state);
        if (state == STATE_IDENTIFIER && !isPreprocessorLine) {
            // Last token might be a keyword.
            LineSegment segment = getLastSegment();
            if (segment != null && isKeywordAt(text, segment))
                segment.setFormat(JAVA_FORMAT_KEYWORD);
        }
    }

    // Java's contextual keywords: keywords only where the grammar expects
    // one, ordinary names elsewhere, as in "int record = 0" or
    // "Thread.yield()".
    private static final Set<String> CONTEXTUAL_KEYWORDS = Set.of("permits", "record", "sealed", "var", "yield");

    // Whether segment, the last one on the line so far and so ending at
    // tokenBegin, is a keyword where it stands in text.
    private boolean isKeywordAt(String text, LineSegment segment) {
        final String word = segment.getText();
        if (!isKeyword(word))
            return false;
        if (language != LANGUAGE_JAVA || !CONTEXTUAL_KEYWORDS.contains(word))
            return true;
        final int end = tokenBegin;
        int before = end - word.length() - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before)))
            --before;
        if (before >= 0 && text.charAt(before) == '.')
            return false; // A member: "Thread.yield".
        final int limit = text.length();
        int j = end;
        while (j < limit && Character.isWhitespace(text.charAt(j)))
            ++j;
        if (j == limit)
            return true; // As "permits", its names on the next line.
        final char next = text.charAt(j);
        if (word.equals("yield")) {
            // "yield x;", "yield -1;" and "yield (a + b);" yield a value;
            // "yield(" is a call, and "yield = 1" or "yield;" a name.
            if (next == '(' && j == end)
                return false;
            return "=;,.)]}?:".indexOf(next) < 0;
        }
        // The others are followed by a name: "record Point", "var x",
        // "sealed interface", "permits A".
        return isIdentifierStart(next);
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
                if (state == STATE_TEXT_BLOCK) {
                    if (c == '"' && i + 2 < limit && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                        i += 2;
                        state = STATE_NEUTRAL;
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
                } else if (c == '"'
                        && supportsTextBlocks()
                        && i + 2 < limit
                        && line.charAt(i + 1) == '"'
                        && line.charAt(i + 2) == '"') {
                    // A text block opening.
                    i += 2;
                    state = STATE_TEXT_BLOCK;
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
        table.addEntryFromPrefs(JAVA_FORMAT_ANNOTATION, "annotation", "keyword");
        table.addEntryFromPrefs(JAVA_FORMAT_ESCAPE, "escape");
        table.addEntryFromPrefs(JAVA_FORMAT_ESCAPE_ERROR, "escapeError");
    }
}
