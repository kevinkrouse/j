/*
 * ScriptEscapeFormatterTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.mode.perl.PerlMode;
import org.armedbear.j.mode.php.PHPMode;
import org.armedbear.j.mode.ruby.RubyMode;
import org.junit.jupiter.api.Test;

/**
 * Escape-sequence splitting in the Ruby, Perl and PHP formatters. Ordinary
 * quoted strings split into escape and escapeError pieces; single-quoted
 * strings take only {@code \\} and {@code \'}; other constructs (regexes,
 * here-documents, PHP HTML) keep their existing style.
 */
public class ScriptEscapeFormatterTest {
    private static String ruby(String text) {
        return EditorHarness.runs(RubyMode.getMode(), text);
    }

    private static String perl(String text) {
        return EditorHarness.runs(PerlMode.getMode(), text);
    }

    private static String php(String text) {
        return EditorHarness.runs(PHPMode.getMode(), text);
    }

    // Drop the leading `identifier(s) operator(=)` (or the PHP tag/var
    // prefix) and the trailing `; delimiter(?>)` so the assertions focus
    // on the string itself.
    private static String tail(String runs) {
        int i = runs.indexOf("string(");
        int j = runs.indexOf("delimiter(?>)");
        String s = runs.substring(i, j < 0 ? runs.length() : j).trim();
        // Drop the PHP statement terminator that follows the string.
        if (s.endsWith(";"))
            s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    // --- Ruby ---------------------------------------------------------

    @Test
    public void rubyDoubleQuotedSplitsSimpleEscapes() {
        assertEquals("string(\")string(a)escape(\\n)string(b)string(\")",
            tail(ruby("s = \"a\\nb\"")));
    }

    @Test
    public void rubySingleQuotedOnlyBackslashAndQuote() {
        // \n is literal in a single-quoted string.
        assertEquals("string('a\\nb')", tail(ruby("s = 'a\\nb'")));
        // \' is an escape.
        assertEquals("string(')string(a)escape(\\')string(b)string(')",
            tail(ruby("s = 'a\\'b'")));
    }

    @Test
    public void rubyUnicodeAndBracedLists() {
        assertEquals("string(\")escape(\\u0041)string(\")", tail(ruby("s = \"\\u0041\"")));
        assertEquals("string(\")escape(\\u{1F600 41})string(\")",
            tail(ruby("s = \"\\u{1F600 41}\"")));
    }

    @Test
    public void rubyControlAndMetaEscapes() {
        assertEquals("string(\")escape(\\cA)string(\")", tail(ruby("s = \"\\cA\"")));
        assertEquals("string(\")escape(\\M-\\C-x)string(\")",
            tail(ruby("s = \"\\M-\\C-x\"")));
    }

    @Test
    public void rubyHexAndIdentity() {
        assertEquals("string(\")escape(\\x41)string(\")", tail(ruby("s = \"\\x41\"")));
        assertEquals("string(\")escape(\\q)string(\")", tail(ruby("s = \"\\q\"")));
    }

    @Test
    public void rubyMalformedIsError() {
        assertEquals("string(\")escapeError(\\x)string(ZZ)string(\")",
            tail(ruby("s = \"\\xZZ\"")));
        assertEquals("string(\")string(bad )escapeError(\\u12)string(\")",
            tail(ruby("s = \"bad \\u12\"")));
        assertEquals("string(\")escapeError(\\M-\\C-)string(\")",
            tail(ruby("s = \"\\M-\\C-\"")));
    }

    @Test
    public void rubyAdjacentStringsDoNotBleed() {
        assertEquals("string(\"a\") operator(+) string('b')",
            tail(ruby("s = \"a\" + 'b'")));
    }

    @Test
    public void rubyDivisionIsNotRegexp() {
        // The '/' stays an operator, not a regexp that would swallow the
        // rest of the line.
        assertEquals("identifier(x) operator(=) number(1) / number(2) / number(3)",
            ruby("x = 1 / 2 / 3"));
    }

    // --- Perl ---------------------------------------------------------

    @Test
    public void perlDoubleQuotedSplitsSimpleEscapes() {
        assertEquals("string(\")string(a)escape(\\n)string(b)string(\")",
            tail(perl("s = \"a\\nb\"")));
    }

    @Test
    public void perlSingleQuotedOnlyBackslashAndQuote() {
        assertEquals("string('a\\nb')", tail(perl("s = 'a\\nb'")));
        assertEquals("string(')string(a)escape(\\')string(b)string(')",
            tail(perl("s = 'a\\'b'")));
    }

    @Test
    public void perlHexAndBraced() {
        assertEquals("string(\")escape(\\x41)string(\")", tail(perl("s = \"\\x41\"")));
        assertEquals("string(\")escape(\\x{263A})string(\")",
            tail(perl("s = \"\\x{263A}\"")));
        assertEquals("string(\")escape(\\o{23072})string(\")",
            tail(perl("s = \"\\o{23072}\"")));
    }

    @Test
    public void perlNamedAndControl() {
        assertEquals("string(\")escape(\\N{alpha})string(\")",
            tail(perl("s = \"\\N{alpha}\"")));
        assertEquals("string(\")escape(\\cA)string(\")", tail(perl("s = \"\\cA\"")));
    }

    @Test
    public void perlVerticalTabStaysNeutral() {
        // Perl has no \v for vertical tab; the whole string stays plain.
        assertEquals("string(\"\\v\")", tail(perl("s = \"\\v\"")));
    }

    @Test
    public void perlCaseOperators() {
        assertEquals("string(\")escape(\\Q)string(foo)escape(\\E)string(\")",
            tail(perl("s = \"\\Qfoo\\E\"")));
    }

    @Test
    public void perlUnknownStaysNeutral() {
        assertEquals("string(\"\\y\")", tail(perl("s = \"\\y\"")));
    }

    @Test
    public void perlMalformedBracedIsError() {
        assertEquals("string(\")string(bad )escapeError(\\x{ZZ})string(\")",
            tail(perl("s = \"bad \\x{ZZ}\"")));
    }

    // --- PHP ----------------------------------------------------------

    @Test
    public void phpDoubleQuotedSplitsSimpleEscapes() {
        assertEquals("string(\")string(a)escape(\\n)string(b)string(\")",
            tail(php("<?php $s = \"a\\nb\"; ?>")));
    }

    @Test
    public void phpSingleQuotedOnlyBackslashAndQuote() {
        assertEquals("string('a\\nb')", tail(php("<?php $s = 'a\\nb'; ?>")));
        assertEquals("string(')string(a)escape(\\')string(b)string(')",
            tail(php("<?php $s = 'a\\'b'; ?>")));
    }

    @Test
    public void phpHexAndBracedUnicode() {
        assertEquals("string(\")escape(\\x41)string(\")",
            tail(php("<?php $s = \"\\x41\"; ?>")));
        assertEquals("string(\")escape(\\u{41})string(\")",
            tail(php("<?php $s = \"\\u{41}\"; ?>")));
    }

    @Test
    public void phpVerticalTabIsAnEscape() {
        // Unlike Perl, PHP's \v is the vertical tab escape.
        assertEquals("string(\")escape(\\v)string(\")",
            tail(php("<?php $s = \"\\v\"; ?>")));
    }

    @Test
    public void phpDollarEscape() {
        assertEquals("string(\")escape(\\$)string(var)string(\")",
            tail(php("<?php $s = \"\\$var\"; ?>")));
    }

    @Test
    public void phpUnknownStaysLiteral() {
        assertEquals("string(\"\\y\")", tail(php("<?php $s = \"\\y\"; ?>")));
    }

    @Test
    public void phpMalformedBracedIsError() {
        assertEquals("string(\")string(bad )escapeError(\\u{4z})string(\")",
            tail(php("<?php $s = \"bad \\u{4z}\"; ?>")));
    }
}
