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

    // --- Python -------------------------------------------------------

    @Test
    public void pythonSimpleEscapes() {
        assertEquals("O(\\a) O(\\b) O(\\f) O(\\n) O(\\r) O(\\t) O(\\v) O(\\\\) O(\\') O(\\\")",
            spans(s(B + "a", B + "b", B + "f", B + "n", B + "r", B + "t", B + "v",
                  B + "\\", B + "'", B + "\""), PYTHON));
    }

    @Test
    public void pythonOctalAllowsAnyFirstDigit() {
        assertEquals("O(\\400)", spans(s(B + "400"), PYTHON));
        assertEquals("O(\\377)", spans(s(B + "377"), PYTHON));
        // Three digits max: \1234 is \123 plus a plain 4.
        assertEquals("O(\\123)", spans(s(B + "1234"), PYTHON));
    }

    @Test
    public void pythonHexNeedsTwoDigits() {
        assertEquals("O(\\x41)", spans(s(B + "x41"), PYTHON));
        assertEquals("E(\\x4)", spans(s(B + "x4"), PYTHON));
        assertEquals("E(\\x)", spans(s(B + "x"), PYTHON));
    }

    @Test
    public void pythonUnicodeForms() {
        assertEquals("O(\\u0041)", spans(s(B + "u0041"), PYTHON));
        assertEquals("O(\\U0010FFFF)", spans(s(B + "U0010FFFF"), PYTHON));
        assertEquals("E(\\u12)", spans(s(B + "u12"), PYTHON));
    }

    @Test
    public void pythonNamedUnicode() {
        assertEquals("O(\\N{BULLET})", spans(s(B + "N{BULLET}"), PYTHON));
        // Empty braces, no brace, or no closer are errors.
        assertEquals("E(\\N{})", spans(s(B + "N{}"), PYTHON));
        assertEquals("E(\\N)", spans(s(B + "Nq"), PYTHON));
        assertEquals("E(\\N{BUL)", spans(s(B + "N{BUL"), PYTHON));
    }

    @Test
    public void pythonNamedEscapeStopsAtBackslash() {
        // The unclosed candidate stops at the next backslash; the
        // following escape is still recognized, not swallowed.
        assertEquals("E(\\N{unfinished) O(\\n)",
                spans(s(B + "N{unfinished", B + "n"), PYTHON));
    }

    @Test
    public void pythonBytesExcludeUnicode() {
        assertEquals("O(\\x41)", spans(s(B + "x41"), PYTHON_BYTES));
        // The backslash-u introducer is an error; the digits stay plain.
        assertEquals("E(\\u)", spans(s(B + "u0041"), PYTHON_BYTES));
        assertEquals("E(\\N)", spans(s(B + "N{BULLET}"), PYTHON_BYTES));
    }

    @Test
    public void pythonUnknownIsError() {
        assertEquals("E(\\q)", spans(s(B + "q"), PYTHON));
    }

    @Test
    public void pythonTrailingBackslash() {
        assertEquals("E(\\)", spans(s(String.valueOf(B)), PYTHON));
        assertEquals("O(\\)", spans(s(String.valueOf(B)), PYTHON, true));
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

    // --- XML references ---------------------------------------------

    private static final Escapes.Reference XML_REF = Escapes.Reference.XML;

    private static String refs(String text, Escapes.Reference syntax) {
        List<Span> list = Escapes.scanReferences(text, 0, text.length(), syntax);
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

    @Test
    public void xmlNamedReferences() {
        assertEquals("O(&amp;)", refs("a&amp;b", XML_REF));
        assertEquals("O(&lt;) O(&gt;)", refs("&lt;&gt;", XML_REF));
        // A colon is a name character: namespaced names are one reference.
        assertEquals("O(&xhtml:b;)", refs("&xhtml:b;", XML_REF));
        // Non-ASCII names are recognized, not marked errors.
        assertEquals("O(&café;)", refs("&café;", XML_REF));
        assertEquals("O(&Ω;)", refs("&Ω;", XML_REF));
    }

    @Test
    public void xmlNumericReferences() {
        assertEquals("O(&#233;)", refs("&#233;", XML_REF));
        assertEquals("O(&#xE9;)", refs("&#xE9;", XML_REF));
        assertEquals("O(&#x00000041;)", refs("&#x00000041;", XML_REF));
    }

    @Test
    public void xmlMalformedReferencesAreErrors() {
        // A bare ampersand.
        assertEquals("E(&)", refs("a & b", XML_REF));
        assertEquals("E(&) O(&amp;)", refs("& &amp;", XML_REF));
        // Missing digits or semicolon.
        assertEquals("E(&#;)", refs("&#;", XML_REF));
        assertEquals("E(&#x;)", refs("&#x;", XML_REF));
        assertEquals("E(&#233)", refs("&#233", XML_REF));
        assertEquals("E(&#xZZ;)", refs("&#xZZ;", XML_REF));
        // A name with no closer is an error up to the stop. A paren is
        // not a stop, so it is consumed with the candidate.
        assertEquals("E(&amp)", refs("&amp", XML_REF));
        assertEquals("E(&amp)) O(&lt;)", refs("&amp) &lt;", XML_REF));
    }

    @Test
    public void xmlCandidateStopsAtHardBoundary() {
        // Never consume a following tag.
        assertEquals("E(&foo)", refs("&foo<b>", XML_REF));
        // Stops at another ampersand, a quote, or the end.
        assertEquals("E(&foo) E(&bar)", refs("&foo&bar", XML_REF));
        assertEquals("E(&foo)", refs("&foo\"x\"", XML_REF));
    }

    @Test
    public void xmlCandidateConsumesThroughSemicolon() {
        // A malformed candidate runs to its semicolon, not past it.
        assertEquals("E(&123;)", refs("&123;", XML_REF));
        assertEquals("E(&#23x;)", refs("&#23x;", XML_REF));
        // A space stops the name, so the candidate ends before it.
        assertEquals("E(&a)", refs("&a b;", XML_REF));
    }

    @Test
    public void xmlBackslashHasNoMeaning() {
        assertEquals("O(&amp;)", refs("a\\nb&amp;", XML_REF));
    }

    @Test
    public void xmlNoAmpersandYieldsNothing() {
        assertEquals("", refs("plain text", XML_REF));
    }

    @Test
    public void xmlSupplementaryName() {
        // A supplementary code point in a name stays one span.
        final String name = "&\uD840\uDC00;";
        assertEquals("O(" + name + ")", refs(name, XML_REF));
    }

    // --- HTML references --------------------------------------------

    private static final Escapes.Reference HTML_REF = Escapes.Reference.HTML;

    @Test
    public void htmlNamedReferences() {
        assertEquals("O(&amp;)", refs("a&amp;b", HTML_REF));
        assertEquals("O(&lt;) O(&gt;)", refs("&lt;&gt;", HTML_REF));
        assertEquals("O(&nbsp;)", refs("&nbsp;", HTML_REF));
        // HTML names are ASCII alphanumeric; case is preserved.
        assertEquals("O(&AMP;) O(&Amp;)", refs("&AMP; &Amp;", HTML_REF));
    }

    @Test
    public void htmlNumericReferences() {
        assertEquals("O(&#233;)", refs("&#233;", HTML_REF));
        assertEquals("O(&#xE9;)", refs("&#xE9;", HTML_REF));
        assertEquals("O(&#xe9;)", refs("&#xe9;", HTML_REF));
        assertEquals("O(&#x00000041;)", refs("&#x00000041;", HTML_REF));
    }

    @Test
    public void htmlSemicolonlessNamedStaysPlain() {
        // A missing semicolon on a named candidate is not an error:
        // legacy HTML parsing recovers.
        assertEquals("", refs("&amp", HTML_REF));
        assertEquals("", refs("&foo", HTML_REF));
        assertEquals("", refs("&notanentity", HTML_REF));
        assertEquals("", refs("a & b", HTML_REF));
        assertEquals("", refs("?a=1&b=2", HTML_REF));
    }

    @Test
    public void htmlMalformedNumericIsError() {
        // A malformed numeric candidate is an error even without a
        // semicolon, because the `#` commits to a numeric reference.
        assertEquals("E(&#xZZ;)", refs("&#xZZ;", HTML_REF));
        assertEquals("E(&#;)", refs("&#;", HTML_REF));
        assertEquals("E(&#x;)", refs("&#x;", HTML_REF));
        assertEquals("E(&#23x;)", refs("&#23x;", HTML_REF));
    }

    @Test
    public void htmlNumericWithoutSemicolonIsError() {
        // The spec marks a missing semicolon on a numeric reference as
        // an error even though browsers recover.
        assertEquals("E(&#233)", refs("&#233", HTML_REF));
        assertEquals("E(&#x41)", refs("&#x41", HTML_REF));
    }

    @Test
    public void htmlDigitsAreNamedNotNumeric() {
        // `&123;` is a complete named reference (all digits), not a
        // malformed numeric one.
        assertEquals("O(&123;)", refs("&123;", HTML_REF));
    }

    @Test
    public void htmlNameStopsAtSpace() {
        // A space ends the name candidate; nothing is a reference.
        assertEquals("", refs("&a b;", HTML_REF));
    }

    @Test
    public void htmlBackslashHasNoMeaning() {
        assertEquals("O(&amp;)", refs("a\\nb&amp;", HTML_REF));
    }

    @Test
    public void htmlNoAmpersandYieldsNothing() {
        assertEquals("", refs("plain text", HTML_REF));
    }

    @Test
    public void htmlNonAsciiNameStaysPlain() {
        // HTML names are ASCII only; a non-ASCII candidate is not a
        // reference and stays plain rather than an error.
        assertEquals("", refs("&café;", HTML_REF));
    }

    // --- Ruby ---------------------------------------------------------

    @Test
    public void rubySimpleEscapes() {
        assertEquals("O(\\a) O(\\b) O(\\t) O(\\n) O(\\v) O(\\f) O(\\r) O(\\e) O(\\s)",
            spans(s(B + "a", B + "b", B + "t", B + "n", B + "v", B + "f",
                   B + "r", B + "e", B + "s"), RUBY));
        assertEquals("O(\\\\) O(\\\") O(\\\')",
            spans(s(B + "\\", B + "\"", B + "'"), RUBY));
    }

    @Test
    public void rubyOctalAllowsAnyFirstDigit() {
        // 1-3 octal digits, any first: \777 is one escape.
        assertEquals("O(\\7) O(\\77) O(\\777)",
            spans(s(B + "7", B + "77", B + "777"), RUBY));
        // Maximal: \400 is one escape, not \40 plus a literal 0.
        assertEquals("O(\\400)", spans(B + "400", RUBY));
    }

    @Test
    public void rubyHexNeedsOneOrTwoDigits() {
        assertEquals("O(\\x41) O(\\xA)", spans(s(B + "x41", B + "xA"), RUBY));
        // \x with no hex digits is malformed; the trailing z is literal.
        assertEquals("E(\\x)", spans(B + "x", RUBY));
        assertEquals("E(\\x)", spans(B + "xz", RUBY));
    }

    @Test
    public void rubyUnicodeNeedsFourHexOrBraces() {
        assertEquals("O(\\u0041)", spans(B + "u0041", RUBY));
        assertEquals("E(\\u004)", spans(B + "u004", RUBY));
        // A braced list of 1-6 hex values, blanks allowed.
        assertEquals("O(\\u{41})", spans(B + "u{41}", RUBY));
        assertEquals("O(\\u{1F600 41})", spans(B + "u{1F600 41}", RUBY));
        assertEquals("O(\\u{ 41 })", spans(B + "u{ 41 }", RUBY));
    }

    @Test
    public void rubyBracedUnicodeMalformedIsError() {
        // Empty braces, a non-hex byte, over six digits, unclosed. The
        // error span runs through the closing brace when there is one.
        assertEquals("E(\\u{})", spans(B + "u{}", RUBY));
        assertEquals("E(\\u{4z})", spans(B + "u{4z}", RUBY));
        assertEquals("E(\\u{1234567})", spans(B + "u{1234567}", RUBY));
        assertEquals("E(\\u{41)", spans(B + "u{41", RUBY));
    }

    @Test
    public void rubyControlAndMetaEscapes() {
        assertEquals("O(\\cA) O(\\C-x) O(\\M-x) O(\\M-\\C-x) O(\\M-\\cx) O(\\c\\M-x)",
            spans(s(B + "cA", B + "C-x", B + "M-x", B + "M-\\C-x",
                   B + "M-\\cx", B + "c\\M-x"), RUBY));
        assertEquals("O(\\c?)", spans(B + "c?", RUBY));
        // The reverse combination \C-\M-x is valid, same as \M-\C-x.
        assertEquals("O(\\C-\\M-x)", spans(B + "C-\\M-x", RUBY));
    }

    @Test
    public void rubyDoubleControlIsError() {
        // A control base under a control introducer is invalid: \C-\C-x
        // and \C-\cx both double the control. The error consumes through
        // the backslash base; the trailing text is ordinary, not rescanned
        // as a new escape.
        assertEquals("E(\\C-\\)", spans(B + "C-\\C-x", RUBY));
        assertEquals("E(\\C-\\)", spans(B + "C-\\cx", RUBY));
    }

    @Test
    public void rubyControlMissingBaseIsError() {
        // A control or meta form without its printable base; the error
        // runs through the structural prefix it matched.
        assertEquals("E(\\c)", spans(B + "c", RUBY));
        assertEquals("E(\\M-)", spans(B + "M-", RUBY));
        assertEquals("E(\\M-\\C)", spans(B + "M-\\C", RUBY));
        assertEquals("E(\\M-\\C-)", spans(B + "M-\\C-", RUBY));
    }

    @Test
    public void rubyIdentityEscape() {
        // Any other backslashed character is an identity escape.
        assertEquals("O(\\q) O(\\z)", spans(s(B + "q", B + "z"), RUBY));
    }

    @Test
    public void rubyTrailingBackslash() {
        assertEquals("E(\\)", spans(String.valueOf(B), RUBY));
        assertEquals("O(\\)", spans(String.valueOf(B), RUBY, true));
    }

    // --- Perl ---------------------------------------------------------

    @Test
    public void perlSimpleAndCaseEscapes() {
        assertEquals("O(\\t) O(\\n) O(\\r) O(\\b) O(\\f) O(\\a) O(\\e)",
            spans(s(B + "t", B + "n", B + "r", B + "b", B + "f", B + "a", B + "e"), PERL));
        assertEquals("O(\\\\) O(\\\") O(\\l) O(\\u) O(\\L) O(\\U) O(\\F) O(\\Q) O(\\E)",
            spans(s(B + "\\", B + "\"", B + "l", B + "u", B + "L",
                   B + "U", B + "F", B + "Q", B + "E"), PERL));
    }

    @Test
    public void perlVerticalTabIsNotAnEscape() {
        // Perl has no \v for vertical tab; it stays neutral.
        assertEquals("", spans(B + "v", PERL));
    }

    @Test
    public void perlHexAcceptsZeroToTwoDigits() {
        assertEquals("O(\\x41) O(\\xA) O(\\x)",
            spans(s(B + "x41", B + "xA", B + "x"), PERL));
        // A lone \x is accepted (it is \x00), not flagged.
        assertEquals("O(\\x)", spans(B + "x", PERL));
    }

    @Test
    public void perlBracedHexAndOctal() {
        assertEquals("O(\\x{263A}) O(\\o{23072})",
            spans(s(B + "x{263A}", B + "o{23072}"), PERL));
        // Empty braces are accepted for hex (\x{} is \x00).
        assertEquals("O(\\x{})", spans(B + "x{}", PERL));
        // Blanks inside the braces are allowed.
        assertEquals("O(\\x{ 263A })", spans(B + "x{ 263A }", PERL));
        // Underscores are separators; Perl accepts them without warning.
        assertEquals("O(\\x{4_1}) O(\\o{1_01})",
            spans(s(B + "x{4_1}", B + "o{1_01}"), PERL));
    }

    @Test
    public void perlBracedMalformedIsError() {
        // The error span runs through the closing brace when present.
        assertEquals("E(\\x{ZZ})", spans(B + "x{ZZ}", PERL), "non-hex in braced hex");
        assertEquals("E(\\o{8})", spans(B + "o{8}", PERL), "non-octal in braced octal");
        // An empty octal payload is fatal in Perl, unlike empty hex.
        assertEquals("E(\\o{})", spans(B + "o{}", PERL), "empty braced octal");
        assertEquals("E(\\x{263A)", spans(B + "x{263A", PERL), "unclosed");
    }

    @Test
    public void perlUnclosedBracedStopsAtNextBackslash() {
        // The unclosed candidate stops before the next backslash, so a
        // following escape is still recognized rather than swallowed.
        assertEquals("E(\\x{41) O(\\n)", spans(s(B + "x{41", B + "n"), PERL));
        assertEquals("E(\\o{12) O(\\t)", spans(s(B + "o{12", B + "t"), PERL));
    }

    @Test
    public void perlNamedUnicode() {
        assertEquals("O(\\N{alpha})", spans(B + "N{alpha}", PERL));
        assertEquals("O(\\N{U+263D})", spans(B + "N{U+263D}", PERL));
        // Empty or unclosed is an error.
        assertEquals("E(\\N{})", spans(B + "N{}", PERL));
        assertEquals("E(\\N{alpha)", spans(B + "N{alpha", PERL));
    }

    @Test
    public void perlControlEscape() {
        assertEquals("O(\\cA) O(\\c[)", spans(s(B + "cA", B + "c["), PERL));
        // \c{ is not a control escape: the brace is disallowed, so the
        // error is the introducer and the brace stays literal.
        assertEquals("E(\\c)", spans(B + "c{", PERL));
    }

    @Test
    public void perlUnknownStaysNeutral() {
        // Unrecognized forms are not highlighted at all.
        assertEquals("", spans(B + "y", PERL));
        assertEquals("", spans(B + "q", PERL));
        assertEquals("", spans(B + "o", PERL), "\\o without a brace is neutral");
    }

    @Test
    public void perlOctalAllowsAnyFirstDigit() {
        assertEquals("O(\\7) O(\\77) O(\\777)",
            spans(s(B + "7", B + "77", B + "777"), PERL));
    }

    @Test
    public void perlTrailingBackslash() {
        assertEquals("E(\\)", spans(String.valueOf(B), PERL));
        assertEquals("O(\\)", spans(String.valueOf(B), PERL, true));
    }

    // --- PHP ----------------------------------------------------------

    @Test
    public void phpSimpleEscapes() {
        assertEquals("O(\\n) O(\\r) O(\\t) O(\\v) O(\\e) O(\\f) O(\\\\) O(\\$) O(\\\")",
            spans(s(B + "n", B + "r", B + "t", B + "v", B + "e",
                   B + "f", B + "\\", B + "$", B + "\""), PHP));
    }

    @Test
    public void phpOctalUpToThreeDigits() {
        // 1-3 octal digits; the value silently overflows the byte, so
        // \400 is one escape (PHP "\400" === "\000"), not \40 plus 0.
        assertEquals("O(\\0) O(\\12) O(\\377)",
            spans(s(B + "0", B + "12", B + "377"), PHP));
        assertEquals("O(\\400)", spans(B + "400", PHP));
        assertEquals("O(\\777)", spans(B + "777", PHP));
    }

    @Test
    public void phpHexNeedsOneOrTwoDigits() {
        assertEquals("O(\\x41) O(\\xA)", spans(s(B + "x41", B + "xA"), PHP));
        // \x with no hex digits stays literal.
        assertEquals("", spans(B + "x", PHP));
    }

    @Test
    public void phpBracedUnicode() {
        assertEquals("O(\\u{41}) O(\\u{1F600})",
            spans(s(B + "u{41}", B + "u{1F600}"), PHP));
        // backslash-u without a brace is literal.
        assertEquals("", spans(B + "u0041", PHP));
    }

    @Test
    public void phpBracedUnicodeMalformedIsError() {
        // The error span runs through the closing brace when present.
        assertEquals("E(\\u{})", spans(B + "u{}", PHP));
        assertEquals("E(\\u{4z})", spans(B + "u{4z}", PHP));
        assertEquals("E(\\u{41)", spans(B + "u{41", PHP));
    }

    @Test
    public void phpUnknownStaysLiteral() {
        assertEquals("", spans(B + "a", PHP));
        assertEquals("", spans(B + "q", PHP));
    }

    @Test
    public void phpTrailingBackslash() {
        assertEquals("E(\\)", spans(String.valueOf(B), PHP));
        assertEquals("O(\\)", spans(String.valueOf(B), PHP, true));
    }

    // --- single-quoted ------------------------------------------------

    @Test
    public void singleQuotedOnlyBackslashAndQuote() {
        assertEquals("O(\\\\) O(\\')", spans(s(B + "\\", B + "'"), SINGLE_QUOTED));
    }

    @Test
    public void singleQuotedOtherBackslashesAreLiteral() {
        // \n, \t and friends are NOT escapes in a single-quoted string.
        assertEquals("", spans(B + "n", SINGLE_QUOTED));
        assertEquals("", spans(B + "t", SINGLE_QUOTED));
        assertEquals("", spans(B + "x41", SINGLE_QUOTED));
        assertEquals("", spans(B + "u0041", SINGLE_QUOTED));
    }

    @Test
    public void singleQuotedDoubleBackslashConsumesBoth() {
        // \\' is \\ (one escape) then a literal quote, not \ then an
        // escaped quote.
        assertEquals("O(\\\\)", spans(B + "\\" + "'", SINGLE_QUOTED));
    }

    @Test
    public void singleQuotedTrailingBackslashIsLiteral() {
        assertEquals("", spans(String.valueOf(B), SINGLE_QUOTED));
        assertEquals("", spans(String.valueOf(B), SINGLE_QUOTED, true));
    }
}
