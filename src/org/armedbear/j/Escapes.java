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
 * The scanner behind escape-sequence highlighting. It reports the escape
 * spans inside a literal's content, as the language profile says which
 * spellings that language accepts, without decoding any of them.
 *
 * <p>A scan takes a bounded content range of a formatted line, a
 * {@link Syntax} profile, and whether the range ends at a physical line
 * break. It returns ordered, nonempty, nonoverlapping {@link Span}s in
 * UTF-16 offsets of the text it was given — the detabbed line text the
 * formatter already segments, so the offsets line up with existing
 * segments. Delimiters and prefixes are outside the content range: the
 * caller keeps them in the literal's base format.
 *
 * <p>Recognition is a small deterministic left-to-right scan, not a
 * regex: digit counts, maximal matches, and error extents are the point,
 * and a backtracking pattern over a long malformed suffix is exactly the
 * cost to avoid. Work is linear in the scanned range plus the spans
 * emitted. See dev-docs/ESCAPE-SEQUENCES.md for the profiles and the
 * rules each implements.
 */
public final class Escapes {

    /**
     * One recognized escape or malformed candidate, in the scanned
     * text's UTF-16 offsets. {@code end} is exclusive; {@code end >
     * begin} always.
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
     * A language's escape-spelling profile. Each constant answers what a
     * backslash introduces in that language's ordinary (non-raw) string
     * content: which forms are escapes, which are errors, and what an
     * unknown backslash means.
     */
    public enum Syntax {
        /** Java strings and character literals: strict; unknown is an error. */
        JAVA,
        /** Java text blocks: Java's forms plus a line-continuation backslash. */
        JAVA_TEXT_BLOCK,
        /** C, C++ and Objective-C ordinary literals: classic forms plus \e. */
        C,
        /** JavaScript quoted strings: permissive identity escapes. */
        JAVASCRIPT
    }

    private Escapes() {
    }

    /**
     * The escape spans in {@code text} from {@code begin} up to but not
     * including {@code end}.
     *
     * @param continued whether the range ends at a physical line break,
     *                  so a trailing continuation backslash can be
     *                  recognized; a segment boundary is not a line
     *                  break, and the buffer's last line has none
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

    // What the scanner makes of the backslash at `i`: where its span
    // ends, whether that span is an error, or no span at all when the
    // backslash introduces an accepted literal fallback.
    private static final class Result {
        final int stop;
        final boolean error;

        Result(int stop, boolean error) {
            this.stop = stop;
            this.error = error;
        }

        static final Result NONE = new Result(-1, false);
    }

    // Scans the escape starting at the backslash at `i`, within the
    // content range. The caller resumes at the returned stop, so an
    // escape never restarts inside itself: in "\\n" the `\\` consumes
    // both backslashes and the `n` is ordinary text.
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
        }
        return Result.NONE;
    }

    // --- Java ----------------------------------------------------------

    // Java's simple escapes: the JLS EscapeSequence minus the numeric
    // forms. \s is Java's own.
    private static boolean javaSimpleEscape(char c) {
        return "btnfr\"'\\s".indexOf(c) >= 0;
    }

    private static Result scanJava(CharSequence text, int i, int end, boolean textBlock, boolean continued) {
        // i is at the backslash.
        if (i + 1 >= end) {
            // A trailing backslash. In a text block it continues the
            // line when a physical break follows; otherwise it is the
            // dangling-backslash error. Either way the span is just
            // the backslash itself.
            return textBlock && continued ? escape(i + 1) : error(i + 1);
        }
        final char c = text.charAt(i + 1);
        if (javaSimpleEscape(c))
            return escape(i + 2);
        if (c == 'u')
            return scanJavaUnicode(text, i, end);
        if (c >= '0' && c <= '7')
            return scanOctal(text, i, end);
        // Unknown: the strict profile's error. Consume the backslash
        // and the following Unicode code point, keeping a surrogate
        // pair together.
        return error(consumeCodePoint(text, i, end));
    }

    // A backslash, one or more u's, then exactly four hex digits. The
    // whole u-run is one introducer: \uuuu0041 is one escape. A
    // malformed payload colors the introducer and the hex digits found,
    // stopping at the first character that is not a hex digit.
    private static Result scanJavaUnicode(CharSequence text, int i, int end) {
        int j = i + 1;
        while (j < end && text.charAt(j) == 'u')
            ++j;
        // j is one past the u-run.
        int digits = 0;
        while (digits < 4 && j < end && isHex(text.charAt(j))) {
            ++j;
            ++digits;
        }
        if (digits == 4)
            return escape(j);
        // Malformed: the digits found are already included; stop
        // before a whitespace or backslash boundary.
        return error(j);
    }

    // Octal: one or two digits, or three with the first in 0-3.
    // Maximal: \377 is one escape; \400 is \40 plus an ordinary '0'.
    private static Result scanOctal(CharSequence text, int i, int end) {
        int j = i + 2; // past \ and the first octal digit
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
        if (i + 1 >= end) {
            // A trailing backslash continues the line when a physical
            // break follows; else it is a dangling backslash,
            // suspicious under the classic profile.
            return continued ? escape(i + 1) : error(i + 1);
        }
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
            // At least one hex digit; consumes all of them. \x with no
            // digits is malformed: color the introducer.
            int j = i + 2;
            while (j < end && isHex(text.charAt(j)))
                ++j;
            if (j > i + 2)
                return escape(j);
            return error(j);
        }
        if (c == 'u')
            return scanFixedHex(text, i, end, 4);
        if (c == 'U')
            return scanFixedHex(text, i, end, 8);
        // Unknown form: suspicious under the classic profile. Consume
        // the backslash and the following code point.
        return error(consumeCodePoint(text, i, end));
    }

    // --- JavaScript ----------------------------------------------------

    private static Result scanJavaScript(CharSequence text, int i, int end, boolean continued) {
        if (i + 1 >= end) {
            // A trailing backslash with a physical break to continue
            // onto is the line continuation; without one it is an
            // error.
            return continued ? escape(i + 1) : error(i + 1);
        }
        final char c = text.charAt(i + 1);
        if (c == 'x') {
            // Exactly two hex digits.
            int j = i + 2;
            int digits = 0;
            while (digits < 2 && j < end && isHex(text.charAt(j))) {
                ++j;
                ++digits;
            }
            if (digits == 2)
                return escape(j);
            return error(j);
        }
        if (c == 'u') {
            // A braced form with one or more hex digits, or a backslash-u plus exactly 4.
            if (i + 2 < end && text.charAt(i + 2) == '{')
                return scanJsBracedUnicode(text, i, end);
            return scanFixedHex(text, i, end, 4);
        }
        if (c >= '0' && c <= '9') {
            // \0, legacy octal and \8/\9, whether or not strict mode
            // allows them. Octal as Java's: \012 is one escape, \1234
            // is \123 and a plain 4.
            return c <= '7' ? scanOctal(text, i, end) : escape(i + 2);
        }
        // Identity escapes: \q, \' and every other character following
        // a backslash is the escape of that character.
        return escape(consumeCodePoint(text, i, end));
    }

    // A braced form: one or more hex digits inside. A malformed candidate
    // colors through its closing brace if present, else to the content
    // boundary or next backslash, and is consumed once.
    private static Result scanJsBracedUnicode(CharSequence text, int i, int end) {
        int j = i + 3; // past the opening brace
        int digits = 0;
        while (j < end && text.charAt(j) != '}' && text.charAt(j) != '\\') {
            if (isHex(text.charAt(j)))
                ++digits;
            ++j;
        }
        if (j < end && text.charAt(j) == '}') {
            ++j; // include the closing brace
            return digits >= 1 ? escape(j) : error(j);
        }
        // No closing brace before the boundary or a backslash: the
        // candidate is malformed; consume what was scanned.
        return error(j);
    }

    // --- shared helpers ------------------------------------------------

    // A fixed-width hex escape: exactly `width` digits after the
    // introducer. A malformed payload colors the introducer and the
    // hex digits found, stopping at the first character that is not a
    // hex digit.
    private static Result scanFixedHex(CharSequence text, int i, int end, int width) {
        int j = i + 2;
        int digits = 0;
        while (digits < width && j < end && isHex(text.charAt(j))) {
            ++j;
            ++digits;
        }
        if (digits == width)
            return escape(j);
        return error(j);
    }

    // The backslash plus the following Unicode code point, keeping a
    // surrogate pair together.
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
}
