/*
 * EscapesTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Escapes.Syntax.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.armedbear.j.Escapes.Span;
import org.junit.jupiter.api.Test;

/**
 * The escape scanner's span classification. Fixtures are written with
 * explicit character values so no backslash-u sequence in this file can
 * be processed by javac's own Unicode-escape pass: BS is '\\' built
 * from a char, and the u-sequences are assembled at runtime.
 */
public class EscapesTest {
    private static final char B = '\\';

    // "..." content with the backslash spelled as B.
    private static String s(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts)
            sb.append(p);
        return sb.toString();
    }

    private static String spans(String text, Escapes.Syntax syntax, boolean continued) {
        List<Span> list = Escapes.scan(text, 0, text.length(), syntax, continued);
        StringBuilder sb = new StringBuilder();
        for (Span span : list) {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(span.error ? "E(" : "O(")
              .append(text, span.begin, span.end)
              .append(')');
        }
        return sb.toString();
    }

    private static String spans(String text, Escapes.Syntax syntax) {
        return spans(text, syntax, false);
    }

    // --- Java ---------------------------------------------------------

    @Test
    public void javaSimpleEscapes() {
        assertEquals("O(\\n) O(\\t) O(\\\\)", spans(s(B + "n", B + "t", B + "\\"), JAVA));
        assertEquals("O(\\\") O(\\\') O(\\s) O(\\b) O(\\f) O(\\r)",
            spans(s(B + "\"", B + "'", B + "s", B + "b", B + "f", B + "r"), JAVA));
    }

    @Test
    public void javaOctalIsMaximal() {
        assertEquals("O(\\377)", spans(s(B + "377"), JAVA));
        // \400 is \40 plus an ordinary '0'.
        assertEquals("O(\\40)", spans(s(B + "400"), JAVA));
        assertEquals("O(\\7)", spans(s(B + "7"), JAVA));
        assertEquals("O(\\12)", spans(s(B + "12"), JAVA));
    }

    @Test
    public void javaUnicodeNeedsFourHexDigits() {
        assertEquals("O(\\u0041)", spans(s(B + "u0041"), JAVA));
        assertEquals("E(\\u12)", spans(s(B + "u12"), JAVA));
        assertEquals("E(\\u12)", spans(s(B + "u12G"), JAVA));
    }

    @Test
    public void javaMalformedStopsBeforeBackslash() {
        // The malformed span stops at the non-hex; the next escape is
        // still recognized.
        assertEquals("E(\\u12) O(\\n)", spans(s(B + "u12G4", B + "n"), JAVA));
        assertEquals("E(\\u12) O(\\n)", spans(s(B + "u12", B + "n"), JAVA));
    }

    @Test
    public void javaMultiUSpellings() {
        assertEquals("O(\\uu0041)", spans(s(B + "uu0041"), JAVA));
        assertEquals("O(\\uuuu0041)", spans(s(B + "uuuu0041"), JAVA));
        assertEquals("E(\\uuu12)", spans(s(B + "uuu12"), JAVA));
    }

    @Test
    public void javaDoubleBackslashConsumesBoth() {
        // The \\ consumes both; the rest stays plain.
        assertEquals("O(\\\\)", spans(s(B + "\\" + "n"), JAVA));
        assertEquals("O(\\\\)", spans(s(B + "\\" + "u0041"), JAVA));
    }

    @Test
    public void javaStructuralUnicodePolicy() {
        // The complete escape is colored; the trailing character has no
        // backslash introducing it, so it stays plain.
        assertEquals("O(\\u005C)", spans(s(B + "u005Cn"), JAVA));
        assertEquals("O(\\u005C)", spans(s(B + "u005Cq"), JAVA));
    }

    @Test
    public void javaUnknownEscapeIsError() {
        assertEquals("E(\\q)", spans(s(B + "q"), JAVA));
        assertEquals("E(\\x)", spans(s(B + "x41"), JAVA));
    }

    @Test
    public void javaTrailingBackslashIsError() {
        assertEquals("E(\\)", spans(s(String.valueOf(B)), JAVA));
    }

    @Test
    public void javaTextBlockAllowsContinuation() {
        assertEquals("O(\\)", spans(s(String.valueOf(B)), JAVA_TEXT_BLOCK, true));
        assertEquals("E(\\)", spans(s(String.valueOf(B)), JAVA_TEXT_BLOCK, false));
    }

    @Test
    public void javaSurrogatePairStaysTogether() {
        // Unknown escape followed by a surrogate pair: the pair is one
        // code point and stays in the error span.
        String pair = "\uD83C\uDF1F";
        assertEquals("E(\\" + pair + ")", spans(s(B + pair), JAVA));
    }

    // --- C family -----------------------------------------------------

    @Test
    public void cSimpleEscapes() {
        assertEquals("O(\\a) O(\\b) O(\\f) O(\\n) O(\\r) O(\\t) O(\\v) O(\\?) O(\\\\)",
            spans(s(B + "a", B + "b", B + "f", B + "n", B + "r", B + "t", B + "v", B + "?", B + "\\"), C));
        assertEquals("O(\\e)", spans(s(B + "e"), C));
    }

    @Test
    public void cOctalUpToThreeDigits() {
        assertEquals("O(\\12)", spans(s(B + "12"), C));
        assertEquals("O(\\123)", spans(s(B + "123"), C));
        assertEquals("O(\\123)", spans(s(B + "1234"), C)); // three digits, '4' plain
    }

    @Test
    public void cHexConsumesAllDigits() {
        assertEquals("O(\\x41face)", spans(s(B + "x41face"), C));
        assertEquals("O(\\x1)", spans(s(B + "x1"), C));
        assertEquals("E(\\x)", spans(s(B + "x"), C));
        assertEquals("E(\\x)", spans(s(B + "xzz"), C));
    }

    @Test
    public void cFixedWidthUnicode() {
        assertEquals("O(\\u0041)", spans(s(B + "u0041"), C));
        assertEquals("O(\\U0001F600)", spans(s(B + "U0001F600"), C));
        assertEquals("E(\\u12)", spans(s(B + "u12"), C));
        assertEquals("E(\\U0000)", spans(s(B + "U0000"), C)); // 4 of 8
    }

    @Test
    public void cUnknownIsSuspicious() {
        assertEquals("E(\\q)", spans(s(B + "q"), C));
    }

    @Test
    public void cTrailingBackslash() {
        assertEquals("E(\\)", spans(s(String.valueOf(B)), C));
        assertEquals("O(\\)", spans(s(String.valueOf(B)), C, true));
    }

    // --- JavaScript ---------------------------------------------------

    @Test
    public void jsIdentityEscapes() {
        assertEquals("O(\\q) O(\\w) O(\\z)", spans(s(B + "q", B + "w", B + "z"), JAVASCRIPT));
    }

    @Test
    public void jsHexNeedsTwoDigits() {
        assertEquals("O(\\x41)", spans(s(B + "x41"), JAVASCRIPT));
        assertEquals("E(\\x4)", spans(s(B + "x4"), JAVASCRIPT));
        assertEquals("E(\\x)", spans(s(B + "x"), JAVASCRIPT));
    }

    @Test
    public void jsBracedUnicode() {
        assertEquals("O(\\u{1F600})", spans(s(B + "u{1F600}"), JAVASCRIPT));
        assertEquals("O(\\u{41})", spans(s(B + "u{41}"), JAVASCRIPT));
        assertEquals("E(\\u{})", spans(s(B + "u{}"), JAVASCRIPT));
        assertEquals("E(\\u{zz})", spans(s(B + "u{zz}"), JAVASCRIPT));
        assertEquals("E(\\u{12)", spans(s(B + "u{12"), JAVASCRIPT)); // no closing brace
    }

    @Test
    public void jsFixedUnicodeNeedsFour() {
        assertEquals("O(\\u0041)", spans(s(B + "u0041"), JAVASCRIPT));
        assertEquals("E(\\u12)", spans(s(B + "u12"), JAVASCRIPT));
    }

    @Test
    public void jsLegacyOctalAndEightNine() {
        assertEquals("O(\\0)", spans(s(B + "0"), JAVASCRIPT));
        assertEquals("O(\\8) O(\\9)", spans(s(B + "8", B + "9"), JAVASCRIPT));
        // The octal run is maximal: \12 is one escape.
        assertEquals("O(\\12)", spans(s(B + "12"), JAVASCRIPT));
        // \0 followed by a non-octal digit is \0 plus ordinary text.
        assertEquals("O(\\0)", spans(s(B + "08"), JAVASCRIPT));
        // At most three digits, two when the first is 4-7.
        assertEquals("O(\\123)", spans(s(B + "1234"), JAVASCRIPT));
        assertEquals("O(\\77)", spans(s(B + "777"), JAVASCRIPT));
    }

    @Test
    public void jsTrailingBackslash() {
        assertEquals("E(\\)", spans(s(String.valueOf(B)), JAVASCRIPT));
        assertEquals("O(\\)", spans(s(String.valueOf(B)), JAVASCRIPT, true));
    }

    // --- shared invariants --------------------------------------------

    @Test
    public void spansAreOrderedNonOverlappingAndNonempty() {
        String text = s(B + "n", "x", B + "u12", "y", B + "x41", B + "\\q");
        for (Escapes.Syntax syntax : Escapes.Syntax.values()) {
            List<Span> list = Escapes.scan(text, 0, text.length(), syntax, false);
            int prev = 0;
            for (Span span : list) {
                org.junit.jupiter.api.Assertions.assertTrue(span.begin >= prev, "ordered");
                org.junit.jupiter.api.Assertions.assertTrue(span.end > span.begin, "nonempty");
                prev = span.end;
            }
        }
    }

    @Test
    public void emptyRangeYieldsNoSpans() {
        assertEquals("", spans("", JAVA));
    }

    @Test
    public void longMalformedSuffixIsLinear() {
        // The span ends at the first non-hex; no rescan of the tail.
        StringBuilder sb = new StringBuilder();
        sb.append(B).append("u");
        for (int i = 0; i < 1000; i++)
            sb.append('z');
        List<Span> list = Escapes.scan(sb.toString(), 0, sb.length(), JAVA, false);
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).end); // the introducer only: stop at the first z
    }
}
