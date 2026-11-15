/*
 * Escapes.java
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

package org.armedbear.j;

import java.util.ArrayList;
import java.util.List;

/**
 * The scanner behind escape-sequence highlighting: the escape spans in a
 * literal's content, classified by a language {@link Syntax} profile,
 * without decoding anything.
 *
 * <p>Spans are UTF-16 offsets into the text given — the detabbed line
 * text the formatter segments, so they line up with existing segments.
 * Delimiters and prefixes sit outside the content range; the caller
 * keeps them in the literal's base format.
 *
 * <p>A deterministic left-to-right scan, not a regex: digit counts,
 * maximal matches and error extents are the point, and backtracking over
 * a long malformed suffix is the cost to avoid. Linear in the range
 * scanned plus the spans emitted. See dev-docs/ESCAPE-SEQUENCES.md.
 */
public final class Escapes {

    /**
     * One recognized escape or malformed candidate. {@code end} is
     * exclusive; {@code end > begin} always.
     */
    public static final class Span {
        public final int begin;
        public final int end;
        public final boolean error;

        Span(int begin, int end, boolean error) {
            this.begin = begin;
            this.end = end;
            this.error = error;
        }
    }

    /**
     * A language's escape-spelling profile: what a backslash introduces
     * in that language's ordinary (non-raw) string content.
     */
    public enum Syntax {
        /** Java strings and character literals: strict; unknown is an error. */
        JAVA,
        /** Java text blocks: Java's forms plus a line-continuation backslash. */
        JAVA_TEXT_BLOCK,
        /** C, C++ and Objective-C ordinary literals: classic forms plus \e. */
        C,
        /** JavaScript quoted strings: permissive identity escapes. */
        JAVASCRIPT,
        /** Python ordinary strings: strict; unknown is an error. */
        PYTHON,
        /** Python bytes literals: PYTHON without the Unicode forms. */
        PYTHON_BYTES,
        /** Ruby double-quoted strings: structured forms plus identity escapes. */
        RUBY,
        /** Perl double-quoted strings: recognized forms only; others stay neutral. */
        PERL,
        /** PHP double-quoted strings: recognized forms only; others stay literal. */
        PHP,
        /**
         * Ordinary single-quoted Ruby, Perl and PHP strings: only {@code \\}
         * and {@code \'} are escapes; every other backslash is literal.
         */
        SINGLE_QUOTED
    }

    private Escapes() {
    }

    /**
     * The escape spans in {@code text} from {@code begin} up to but not
     * including {@code end}.
     *
     * @param continued whether the range ends at a physical line break,
     *                  so a trailing continuation backslash can be
     *                  recognized
     */
    public static List<Span> scan(CharSequence text, int begin, int end, Syntax syntax, boolean continued) {
        final List<Span> spans = new ArrayList<>();
        int i = begin;
        while (i < end) {
            if (text.charAt(i) == '\\') {
                final Result r = scanEscape(text, i, end, syntax, continued);
                if (r.stop > i) {
                    spans.add(new Span(i, r.stop, r.error));
                    i = r.stop;
                    continue;
                }
            }
            ++i;
        }
        return spans;
    }

    // What the scanner makes of a backslash: where its span ends, and
    // whether that span is an error.
    private static final class Result {
        final int stop;
        final boolean error;

        Result(int stop, boolean error) {
            this.stop = stop;
            this.error = error;
        }

        static final Result NONE = new Result(-1, false);
    }

    // Scans the escape starting at the backslash at `i`. The caller
    // resumes at the returned stop, so an escape never restarts inside
    // itself: in "\\n" the `\\` consumes both backslashes and the `n`
    // is ordinary text.
    private static Result scanEscape(CharSequence text, int i, int end, Syntax syntax, boolean continued) {
        switch (syntax) {
            case JAVA:
                return scanJava(text, i, end, false, continued);
            case JAVA_TEXT_BLOCK:
                return scanJava(text, i, end, true, continued);
            case C:
                return scanC(text, i, end, continued);
            case JAVASCRIPT:
                return scanJavaScript(text, i, end, continued);
            case PYTHON:
                return scanPython(text, i, end, continued, true);
            case PYTHON_BYTES:
                return scanPython(text, i, end, continued, false);
            case RUBY:
                return scanRuby(text, i, end, continued);
            case PERL:
                return scanPerl(text, i, end, continued);
            case PHP:
                return scanPhp(text, i, end, continued);
            case SINGLE_QUOTED:
                return scanSingleQuoted(text, i, end);
        }
        return Result.NONE;
    }

    // --- Java ----------------------------------------------------------

    // The JLS EscapeSequence minus the numeric forms.
    private static boolean javaSimpleEscape(char c) {
        return "btnfr\"'\\s".indexOf(c) >= 0;
    }

    // `i` is at the backslash.
    private static Result scanJava(CharSequence text, int i, int end, boolean textBlock, boolean continued) {
        if (i + 1 >= end) {
            // A trailing backslash continues a text-block line when a
            // physical break follows; otherwise it is an error.
            return textBlock && continued ? escape(i + 1) : error(i + 1);
        }
        final char c = text.charAt(i + 1);
        if (javaSimpleEscape(c))
            return escape(i + 2);
        if (c == 'u')
            return scanJavaUnicode(text, i, end);
        if (c >= '0' && c <= '7')
            return scanOctal(text, i, end);
        // Unknown: the strict profile's error.
        return error(consumeCodePoint(text, i, end));
    }

    // A backslash, one or more u's, then exactly four hex digits:
    // \uuuu0041 is one escape. A malformed payload colors the
    // introducer and the hex digits found.
    private static Result scanJavaUnicode(CharSequence text, int i, int end) {
        int j = i + 1;
        while (j < end && text.charAt(j) == 'u')
            ++j;
        int digits = 0;
        while (digits < 4 && j < end && isHex(text.charAt(j))) {
            ++j;
            ++digits;
        }
        return digits == 4 ? escape(j) : error(j);
    }

    // One or two octal digits, or three with the first in 0-3.
    // Maximal: \377 is one escape; \400 is \40 plus an ordinary '0'.
    private static Result scanOctal(CharSequence text, int i, int end) {
        int j = i + 2;
        if (j < end && isOctal(text.charAt(j))) {
            ++j;
            if (text.charAt(i + 1) <= '3' && j < end && isOctal(text.charAt(j)))
                ++j;
        }
        return escape(j);
    }

    // --- C family ------------------------------------------------------

    private static boolean cSimpleEscape(char c) {
        return "abfnrtv\"'\\?".indexOf(c) >= 0;
    }

    private static Result scanC(CharSequence text, int i, int end, boolean continued) {
        if (i + 1 >= end)
            return continued ? escape(i + 1) : error(i + 1);
        final char c = text.charAt(i + 1);
        if (cSimpleEscape(c) || c == 'e') // \e: the common extension
            return escape(i + 2);
        if (c >= '0' && c <= '7') {
            // One to three octal digits, maximal.
            int j = i + 2;
            while (j < end && isOctal(text.charAt(j)) && j - (i + 1) < 3)
                ++j;
            return escape(j);
        }
        if (c == 'x') {
            // At least one hex digit; \x with none is malformed.
            int j = i + 2;
            while (j < end && isHex(text.charAt(j)))
                ++j;
            return j > i + 2 ? escape(j) : error(j);
        }
        if (c == 'u')
            return scanFixedHex(text, i, end, 4);
        if (c == 'U')
            return scanFixedHex(text, i, end, 8);
        // Unknown: suspicious under the classic profile.
        return error(consumeCodePoint(text, i, end));
    }

    // --- JavaScript ----------------------------------------------------

    private static Result scanJavaScript(CharSequence text, int i, int end, boolean continued) {
        if (i + 1 >= end)
            return continued ? escape(i + 1) : error(i + 1);
        final char c = text.charAt(i + 1);
        if (c == 'x') {
            // Exactly two hex digits.
            int j = i + 2;
            int digits = 0;
            while (digits < 2 && j < end && isHex(text.charAt(j))) {
                ++j;
                ++digits;
            }
            return digits == 2 ? escape(j) : error(j);
        }
        if (c == 'u') {
            if (i + 2 < end && text.charAt(i + 2) == '{')
                return scanJsBracedUnicode(text, i, end);
            return scanFixedHex(text, i, end, 4);
        }
        if (c >= '0' && c <= '9') {
            // \0, legacy octal and \8/\9, whether or not strict mode
            // allows them.
            return c <= '7' ? scanOctal(text, i, end) : escape(i + 2);
        }
        // Identity escapes: \q, \' and everything else.
        return escape(consumeCodePoint(text, i, end));
    }

    // One or more hex digits inside braces. A malformed candidate is
    // consumed once, through its closing brace if present.
    private static Result scanJsBracedUnicode(CharSequence text, int i, int end) {
        int j = i + 3;
        int digits = 0;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\') {
            if (isHex(text.charAt(j)))
                ++digits;
            ++j;
        }
        if (j < end && text.charAt(j) == '}') {
            ++j;
            return digits >= 1 ? escape(j) : error(j);
        }
        return error(j);
    }

    // --- Python --------------------------------------------------------

    private static boolean pythonSimpleEscape(char c) {
        return "abfnrtv\\'\"".indexOf(c) >= 0;
    }

    // `unicode` is false for bytes literals, which lack the backslash-u
    // forms.
    private static Result scanPython(CharSequence text, int i, int end,
                                   boolean continued, boolean unicode) {
        if (i + 1 >= end)
            return continued ? escape(i + 1) : error(i + 1);
        final char c = text.charAt(i + 1);
        if (pythonSimpleEscape(c))
            return escape(i + 2);
        if (c >= '0' && c <= '7') {
            // Up to three octal digits, any first digit: \400 is one
            // escape, unlike Java.
            int j = i + 2;
            while (j < end && isOctal(text.charAt(j)) && j - (i + 1) < 3)
                ++j;
            return escape(j);
        }
        if (c == 'x')
            return scanFixedHex(text, i, end, 2);
        if (unicode && c == 'u')
            return scanFixedHex(text, i, end, 4);
        if (unicode && c == 'U')
            return scanFixedHex(text, i, end, 8);
        if (unicode && c == 'N')
            return scanPythonNamed(text, i, end);
        // Unknown: Python warns about these; suspicious here.
        return error(consumeCodePoint(text, i, end));
    }

    // \N{NAME}: the braces must be present and nonempty. An unclosed
    // candidate stops at the next backslash so a following escape is
    // still recognized, and is consumed once.
    private static Result scanPythonNamed(CharSequence text, int i, int end) {
        if (i + 2 >= end || text.charAt(i + 2) != '{')
            return error(i + 2);
        int j = i + 3;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\')
            ++j;
        if (j < end && text.charAt(j) == '}')
            return j > i + 3 ? escape(j + 1) : error(j + 1);
        return error(j);
    }

    // --- Ruby --------------------------------------------------------

    // The EscapeSequence set minus the numeric, control and braced forms.
    private static boolean rubySimpleEscape(char c) {
        return "abtnvfre\\\"'s".indexOf(c) >= 0;
    }

    // A printable ASCII character: the base of a control or meta escape.
    private static boolean printableAscii(char c) {
        return c >= 0x20 && c <= 0x7e;
    }

    // `i` is at the backslash. Ruby's double-quoted escapes: the simple
    // set, octal, backslash-x (1-2 hex), backslash-u (4 hex or a braced
    // list), control and meta forms, and identity for anything else. A
    // malformed structured form is an error; identity escapes are
    // ordinary escapes.
    private static Result scanRuby(CharSequence text, int i, int end, boolean continued) {
        if (i + 1 >= end)
            return continued ? escape(i + 1) : error(i + 1);
        final char c = text.charAt(i + 1);
        if (rubySimpleEscape(c))
            return escape(i + 2);
        if (c >= '0' && c <= '7')
            return scanOctalAny(text, i, end);
        if (c == 'x') {
            // One or two hex digits; none is malformed.
            int j = i + 2;
            int digits = 0;
            while (digits < 2 && j < end && isHex(text.charAt(j))) {
                ++j;
                ++digits;
            }
            return digits >= 1 ? escape(j) : error(j);
        }
        if (c == 'u') {
            if (i + 2 < end && text.charAt(i + 2) == '{')
                return scanRubyBracedUnicode(text, i, end);
            return scanFixedHex(text, i, end, 4);
        }
        if (c == 'c' || c == 'C' || c == 'M')
            return scanRubyControl(text, i, end);
        // Identity escape: the backslash and the character itself.
        return escape(consumeCodePoint(text, i, end));
    }

    // backslash-u{nnnn ...}: one or more values of 1-6 hex digits,
    // separated by blanks. Empty braces or a non-hex byte is an error;
    // the candidate is consumed once, through its closing brace if
    // present. An unclosed candidate stops at the next backslash so a
    // following escape is still recognized.
    private static Result scanRubyBracedUnicode(CharSequence text, int i, int end) {
        int j = i + 3;
        int values = 0;
        boolean bad = false;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\') {
            final char d = text.charAt(j);
            if (d == ' ' || d == '\t') {
                ++j;
                continue;
            }
            if (!isHex(d)) {
                bad = true;
                ++j;
                continue;
            }
            int digits = 0;
            while (j < end && isHex(text.charAt(j)) && digits < 6) {
                ++j;
                ++digits;
            }
            if (j < end && isHex(text.charAt(j)))
                bad = true; // more than six hex digits in one value
            ++values;
        }
        if (j >= end || text.charAt(j) != '}')
            return error(j); // unclosed: stops before the next backslash
        ++j; // consume the closing brace into the span
        return !bad && values >= 1 ? escape(j) : error(j);
    }

    // The control and meta forms, each ending in a printable ASCII base:
    //   \cx  \C-x  \M-x  \M-\C-x  \M-\cx  \c\M-x
    // A form whose base is missing is an error, consumed through the
    // structural prefix it matched. A backslash after \M- or \C- means a
    // combined form was intended, so it is never a simple base.
    private static Result scanRubyControl(CharSequence text, int i, int end) {
        final char m = text.charAt(i + 1);
        if (m == 'M') {
            if (at(text, end, i + 2, '-')) {
                if (at(text, end, i + 3, '\\')) {
                    // \M-\C-x
                    if (at(text, end, i + 4, 'C')) {
                        if (at(text, end, i + 5, '-'))
                            return printableAt(text, end, i + 6) ? escape(i + 7) : error(i + 6);
                        return error(i + 5); // \M-\C with no dash
                    }
                    // \M-\cx
                    if (at(text, end, i + 4, 'c'))
                        return printableAt(text, end, i + 5) ? escape(i + 6) : error(i + 5);
                    return error(i + 4); // \M-\ with unknown
                }
                // \M-x
                return printableAt(text, end, i + 3) ? escape(i + 4) : error(i + 3);
            }
            return error(consumeCodePoint(text, i, end)); // \M with nothing
        }
        if (m == 'c') {
            if (at(text, end, i + 2, '\\')) {
                // \c\M-x
                if (at(text, end, i + 3, 'M') && at(text, end, i + 4, '-'))
                    return printableAt(text, end, i + 5) ? escape(i + 6) : error(i + 5);
                return error(i + 3); // \c\ with unknown
            }
            // \cx
            return printableAt(text, end, i + 2) ? escape(i + 3) : error(i + 2);
        }
        // \C-x, or the combined \C-\M-x (same value as \M-\C-x). A
        // backslash base recurses as control, so \C-\C-x and \C-\cx are
        // double-control and invalid.
        if (at(text, end, i + 2, '-')) {
            if (at(text, end, i + 3, '\\')) {
                if (at(text, end, i + 4, 'M') && at(text, end, i + 5, '-'))
                    return printableAt(text, end, i + 6) ? escape(i + 7) : error(i + 6);
                return error(i + 4); // \C-\ with no valid meta base
            }
            return printableAt(text, end, i + 3) ? escape(i + 4) : error(i + 3);
        }
        return error(consumeCodePoint(text, i, end)); // \C with nothing
    }

    // The character at `k` equals `c`, with a bounds check.
    private static boolean at(CharSequence text, int end, int k, char c) {
        return k < end && text.charAt(k) == c;
    }

    private static boolean printableAt(CharSequence text, int end, int k) {
        return k < end && printableAscii(text.charAt(k));
    }

    // --- Perl --------------------------------------------------------

    // The perlop simple escapes. Perl has no \v or \s in strings.
    private static boolean perlSimpleEscape(char c) {
        return "tnrbfae\\\"".indexOf(c) >= 0;
    }

    // The case and quoting operators: two-character escapes.
    private static boolean perlCaseOperator(char c) {
        return "luLUFQE".indexOf(c) >= 0;
    }

    // `i` is at the backslash. Perl recognizes the simple set, octal,
    // \x (0-2 hex or a braced list), \o{...}, \N{...}, \c control and
    // the case/quoting operators. Anything else stays neutral: no span,
    // so the backslash and the following character keep the string's
    // base format.
    private static Result scanPerl(CharSequence text, int i, int end, boolean continued) {
        if (i + 1 >= end)
            return continued ? escape(i + 1) : error(i + 1);
        final char c = text.charAt(i + 1);
        if (perlSimpleEscape(c) || perlCaseOperator(c))
            return escape(i + 2);
        if (c >= '0' && c <= '7')
            return scanOctalAny(text, i, end);
        if (c == 'x') {
            if (i + 2 < end && text.charAt(i + 2) == '{')
                return scanPerlBraced(text, i, end, true);
            // Up to two hex digits; zero is accepted (\x is \x00).
            int j = i + 2;
            int digits = 0;
            while (digits < 2 && j < end && isHex(text.charAt(j))) {
                ++j;
                ++digits;
            }
            return escape(j);
        }
        if (c == 'o') {
            if (i + 2 < end && text.charAt(i + 2) == '{')
                return scanPerlBraced(text, i, end, false);
            return Result.NONE;
        }
        if (c == 'N')
            return scanPerlNamed(text, i, end);
        if (c == 'c') {
            // \cX: X printable ASCII except '{'.
            if (i + 2 < end && printableAscii(text.charAt(i + 2)) && text.charAt(i + 2) != '{')
                return escape(i + 3);
            return error(consumeCodePoint(text, i, end));
        }
        // Unrecognized: neutral, not highlighted.
        return Result.NONE;
    }

    // \x{...} or \o{...}: blanks and underscores allowed inside as
    // separators. A hex escape accepts an empty payload (\x{} is
    // \x00); an octal escape requires at least one digit, since Perl
    // rejects an empty \o{} outright. A byte outside the digit set is
    // an error, consumed once through the closing brace if present.
    // An unclosed candidate stops at the next backslash so a following
    // escape is still recognized.
    private static Result scanPerlBraced(CharSequence text, int i, int end, boolean hex) {
        int j = i + 3;
        int digits = 0;
        boolean bad = false;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\') {
            final char d = text.charAt(j);
            if (d == ' ' || d == '\t' || d == '_') {
                ++j;
                continue;
            }
            if (hex ? isHex(d) : isOctal(d)) {
                ++j;
                ++digits;
                continue;
            }
            bad = true;
            ++j;
        }
        if (j >= end || text.charAt(j) != '}')
            return error(j); // unclosed
        ++j; // consume the closing brace into the span
        if (bad || (!hex && digits == 0))
            return error(j);
        return escape(j);
    }

    // \N{name} or \N{U+hex}: the braces must be present and nonempty.
    private static Result scanPerlNamed(CharSequence text, int i, int end) {
        if (i + 2 >= end || text.charAt(i + 2) != '{')
            return error(i + 2);
        int j = i + 3;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\')
            ++j;
        if (j < end && text.charAt(j) == '}')
            return j > i + 3 ? escape(j + 1) : error(j + 1);
        return error(j);
    }

    // --- PHP ---------------------------------------------------------

    // The PHP double-quoted simple escapes.
    private static boolean phpSimpleEscape(char c) {
        return "nrtvef\\$\"".indexOf(c) >= 0;
    }

    // `i` is at the backslash. PHP recognizes the simple set, 1-3 octal
    // digits, backslash-x (1-2 hex) and backslash-u{...}. Unknown forms
    // stay literal: no span. A malformed braced Unicode is an error.
    private static Result scanPhp(CharSequence text, int i, int end, boolean continued) {
        if (i + 1 >= end)
            return continued ? escape(i + 1) : error(i + 1);
        final char c = text.charAt(i + 1);
        if (phpSimpleEscape(c))
            return escape(i + 2);
        if (c >= '0' && c <= '7')
            return scanOctalAny(text, i, end); // 1-3 digits; values overflow the byte
        if (c == 'x') {
            int j = i + 2;
            int digits = 0;
            while (digits < 2 && j < end && isHex(text.charAt(j))) {
                ++j;
                ++digits;
            }
            return digits >= 1 ? escape(j) : Result.NONE;
        }
        if (c == 'u') {
            if (i + 2 < end && text.charAt(i + 2) == '{')
                return scanPhpBracedUnicode(text, i, end);
            return Result.NONE; // backslash-u without a brace is literal
        }
        // Unknown: literal, not highlighted.
        return Result.NONE;
    }

    // backslash-u{...}: one or more hex digits. Empty or a non-hex byte
    // is an error, consumed once through the closing brace if present.
    // An unclosed candidate stops at the next backslash so a following
    // escape is still recognized.
    private static Result scanPhpBracedUnicode(CharSequence text, int i, int end) {
        int j = i + 3;
        int digits = 0;
        boolean bad = false;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\') {
            if (isHex(text.charAt(j))) {
                ++j;
                ++digits;
                continue;
            }
            bad = true;
            ++j;
        }
        if (j >= end || text.charAt(j) != '}')
            return error(j); // unclosed: stops before the next backslash
        ++j; // consume the closing brace into the span
        return !bad && digits >= 1 ? escape(j) : error(j);
    }

    // --- single-quoted -----------------------------------------------

    // Ordinary single-quoted Ruby, Perl and PHP strings: only \\ and \'
    // are escapes; every other backslash is literal.
    private static Result scanSingleQuoted(CharSequence text, int i, int end) {
        if (i + 1 >= end)
            return Result.NONE;
        final char c = text.charAt(i + 1);
        if (c == '\\' || c == '\'')
            return escape(i + 2);
        return Result.NONE;
    }

    // --- shared helpers ------------------------------------------------

    // Up to three octal digits, any first digit: \777 is one escape.
    // Unlike Java's scanOctal, which caps the third digit when the
    // first is 4-7.
    private static Result scanOctalAny(CharSequence text, int i, int end) {
        int j = i + 2;
        while (j < end && isOctal(text.charAt(j)) && j - (i + 1) < 3)
            ++j;
        return escape(j);
    }

    // Exactly `width` hex digits after the introducer.
    private static Result scanFixedHex(CharSequence text, int i, int end, int width) {
        int j = i + 2;
        int digits = 0;
        while (digits < width && j < end && isHex(text.charAt(j))) {
            ++j;
            ++digits;
        }
        return digits == width ? escape(j) : error(j);
    }

    // The backslash plus the following code point, keeping a surrogate
    // pair together.
    private static int consumeCodePoint(CharSequence text, int i, int end) {
        int j = i + 1;
        if (j >= end)
            return j;
        final char c = text.charAt(j);
        ++j;
        if (Character.isHighSurrogate(c) && j < end && Character.isLowSurrogate(text.charAt(j)))
            ++j;
        return j;
    }

    private static Result escape(int stop) {
        return new Result(stop, false);
    }

    private static Result error(int stop) {
        return new Result(stop, true);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }

    // --- XML and HTML references -------------------------------------

    /** The reference dialect: XML is strict; HTML keeps legacy plain forms. */
    public enum Reference {
        XML,
        HTML
    }

    /**
     * The character-reference spans in {@code text} from {@code begin}
     * up to but not including {@code end}. A backslash has no meaning
     * here; only {@code &} introduces a reference.
     */
    public static List<Span> scanReferences(CharSequence text, int begin, int end, Reference syntax) {
        final List<Span> spans = new ArrayList<>();
        int i = begin;
        while (i < end) {
            if (text.charAt(i) == '&') {
                final Result r = syntax == Reference.XML
                    ? scanXmlReference(text, i, end)
                    : scanHtmlReference(text, i, end);
                if (r.stop > i) {
                    spans.add(new Span(i, r.stop, r.error));
                    i = r.stop;
                    continue;
                }
            }
            ++i;
        }
        return spans;
    }

    // XML: &Name;, &#dec;, &#xhex;. Any other ampersand is an error.
    private static Result scanXmlReference(CharSequence text, int i, int end) {
        if (i + 1 >= end)
            return error(i + 1);
        final char c = text.charAt(i + 1);
        if (c == '#') {
            int j = i + 2;
            final boolean hex = j < end && text.charAt(j) == 'x';
            if (hex)
                ++j;
            final int digits = j;
            while (j < end && (hex ? isHex(text.charAt(j)) : isDecimal(text.charAt(j))))
                ++j;
            if (j > digits && j < end && text.charAt(j) == ';')
                return escape(j + 1);
            return error(candidateEnd(text, j, end));
        }
        final int cp = Character.codePointAt(text, i + 1);
        if (isXmlNameStart(cp)) {
            int j = i + 1 + Character.charCount(cp);
            while (j < end) {
                final int nc = Character.codePointAt(text, j);
                if (!isXmlNameChar(nc))
                    break;
                j += Character.charCount(nc);
            }
            if (j < end && text.charAt(j) == ';')
                return escape(j + 1);
            return error(candidateEnd(text, j, end));
        }
        // Not a name start: a bare ampersand, or a malformed candidate
        // such as &123;. Either way the candidate runs to its semicolon.
        return error(candidateEnd(text, i + 1, end));
    }

    // HTML: complete named and numeric references are escapes; a
    // semicolonless named candidate and a bare ampersand stay plain.
    private static Result scanHtmlReference(CharSequence text, int i, int end) {
        if (i + 1 >= end)
            return Result.NONE;
        final char c = text.charAt(i + 1);
        if (c == '#') {
            int j = i + 2;
            final boolean hex = j < end && (text.charAt(j) == 'x' || text.charAt(j) == 'X');
            if (hex)
                ++j;
            final int digits = j;
            while (j < end && (hex ? isHex(text.charAt(j)) : isDecimal(text.charAt(j))))
                ++j;
            if (j > digits && j < end && text.charAt(j) == ';')
                return escape(j + 1);
            return error(candidateEnd(text, j, end));
        }
        if (isAsciiAlnum(c)) {
            int j = i + 1;
            while (j < end && isAsciiAlnum(text.charAt(j)))
                ++j;
            return j < end && text.charAt(j) == ';' ? escape(j + 1) : Result.NONE;
        }
        return Result.NONE;
    }

    // A malformed candidate runs to its semicolon if one comes before a
    // hard stop: whitespace, a tag or ampersand, a quote, or the end.
    private static int candidateEnd(CharSequence text, int j, int end) {
        while (j < end && !isCandidateStop(text.charAt(j)))
            ++j;
        return j < end && text.charAt(j) == ';' ? j + 1 : j;
    }

    private static boolean isCandidateStop(char c) {
        return Character.isWhitespace(c) || c == '<' || c == '&' || c == '"' || c == '\'' || c == ';';
    }

    private static boolean isDecimal(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // XML 1.0 NameStartChar: colon, underscore, ASCII letters, and the
    // non-ASCII ranges, including supplementary code points.
    private static boolean isXmlNameStart(int cp) {
        if (cp == ':' || cp == '_' || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'))
            return true;
        return (cp >= 0xC0 && cp <= 0xD6) || (cp >= 0xD8 && cp <= 0xF6)
            || (cp >= 0xF8 && cp <= 0x2FF) || (cp >= 0x370 && cp <= 0x37D)
            || (cp >= 0x37F && cp <= 0x1FFF) || (cp >= 0x200C && cp <= 0x200D)
            || (cp >= 0x2070 && cp <= 0x218F) || (cp >= 0x2C00 && cp <= 0x2FEF)
            || (cp >= 0x3001 && cp <= 0xD7FF) || (cp >= 0xF900 && cp <= 0xFDCF)
            || (cp >= 0xFDF0 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0xEFFFF);
    }

    // NameChar: NameStartChar plus digits, hyphen, dot, and the combining
    // and connector ranges.
    private static boolean isXmlNameChar(int cp) {
        return isXmlNameStart(cp) || cp == '-' || cp == '.' || (cp >= '0' && cp <= '9')
            || cp == 0xB7 || (cp >= 0x300 && cp <= 0x36F)
            || (cp >= 0x203F && cp <= 0x2040);
    }
}
