/*
 * VimRegexTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.regex.PatternSyntaxException;
import org.armedbear.j.EditorHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Vim's regular expressions, and the four magic levels.
 *
 * Every row below was produced by nvim running {@code :s#PATTERN#[&]#g} over
 * the text, which brackets exactly what matched; the test runs the same
 * substitute through our {@code :s} and requires the same answer. So each
 * row tests the whole pipeline -- translation, compilation and the
 * substitute -- against vim itself rather than against anyone's reading of
 * {@code :help pattern}.
 */
public class VimRegexTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /** text, pattern, what nvim made of it */
    private static final String[][] NVIM = {
        // Magic, the default: + ? ( ) | { are characters until escaped.
        { "a+ aa", "a+", "[a+] aa" },
        { "a+ aa", "a\\+", "[a]+ [aa]" },
        { "ab b", "a\\?b", "[ab] [b]" },
        { "ab b", "a\\=b", "[ab] [b]" },
        { "(ab) ab", "(ab)", "[(ab)] ab" },
        { "abab ab", "\\(ab\\)\\1", "[abab] ab" },
        { "a|b c", "a|b", "[a|b] c" },
        { "a b c", "a\\|b", "[a] [b] c" },
        { "a{2} aa", "a{2}", "[a{2}] aa" },
        { "a aa aaa", "a\\{2}", "a [aa] [aa]a" },
        { "aaaa", "a\\{2,}", "[aaaa]" },
        { "aaaa", "a\\{1,2}", "[aa][aa]" },
        // Vim reads {3,1} as {1,3}; Java refuses it outright.
        { "aaaa", "a\\{3,1}", "[aaa][a]" },
        { "aaa", "a\\{-1,}", "[a][a][a]" },
        { "aaa", "a\\{-2,}", "[aa]a" },
        // Empty matches everywhere but the end of the line.
        { "aaa", "a\\{-}", "[]a[]a[]a" },
        { "abab", "\\%(ab\\)\\+", "[abab]" },
        { "a.b axb", "a\\.b", "[a.b] axb" },
        // ^ and $ only anchor at the ends of a branch; elsewhere they are
        // characters, where Java would read an anchor that cannot match.
        { "a^b", "a^b", "[a^b]" },
        { "a$b", "a$b", "[a$b]" },
        { "xab ab", "^ab", "xab ab" },
        // * with nothing before it is the character.
        { "*a", "*a", "[*a]" },
        { "a/b", "a\\/b", "[a/b]" },
        { "a\\b", "a\\\\b", "[a\\b]" },

        // Very magic: every operator is bare.
        { "abab x", "\\v(ab)+", "[abab] x" },
        { "abcabc", "\\v(abc)\\1", "[abcabc]" },
        { "a b", "\\va|b", "[a] [b]" },
        { "a{2} aa", "\\va{2}", "a{2} [aa]" },
        { "a b", "\\v<b>", "a [b]" },

        // Nomagic: . and * are characters until escaped.
        { "a.b", "\\M.", "a[.]b" },
        { "a.b", "\\M\\.", "[a][.][b]" },
        { "a*b", "\\Ma*", "[a*]b" },

        // Very nomagic: only the backslash is special -- even a leading ^.
        { "a.b", "\\V.", "a[.]b" },
        { "a*b ab", "\\Va*", "[a*]b ab" },
        { "ab ab", "\\Vab", "[ab] [ab]" },
        { "^a$", "\\V^a", "[^a]$" },
        { "a^b", "\\V^", "a[^]b" },
        { "ab$", "\\Vb$", "a[b$]" },

        // Letter classes, several of which mean something else to Java.
        { "a1b", "\\a", "[a]1[b]" },
        { "aBc", "\\u", "a[B]c" },
        { "aBc", "\\l", "[a]B[c]" },
        { "g0f", "\\x", "g[0][f]" },
        { "a12b", "\\d\\+", "a[12]b" },
        { "a1_b", "\\h", "[a]1[_][b]" },
        { "a\tb", "a\\sb", "[a\tb]" },
        { "ax", "\\%x61", "[a]x" },

        // Bracket classes.
        { "a1b", "[[:alpha:]]", "[a]1[b]" },
        { "x]y", "[]]", "x[]]y" },
        { "a-b", "[a-]", "[a][-]b" },
        { "x^y", "[x^]", "[x][^]y" },

        // Where the match starts and ends.
        { "foobar", "foo\\zsbar", "foo[bar]" },
        { "foobar", "foo\\zebar", "[foo]bar" },

        // Lookaround, which vim writes after its atom and Java before.
        { "foobar foobaz", "foo\\(bar\\)\\@=", "[foo]bar foobaz" },
        { "foobar foobaz", "foo\\(bar\\)\\@!", "foobar [foo]baz" },
        { "foobar xbar", "\\(foo\\)\\@<=bar", "foo[bar] xbar" },
        { "xbar foobar", "\\(foo\\)\\@<!bar", "x[bar] foobar" },

        // Case, in the pattern itself.
        { "foo", "\\cFOO", "[foo]" },
        { "Foo foo", "\\Cfoo", "Foo [foo]" },
    };

    @Test
    public void everyRowMatchesWhatNvimMatched() {
        final StringBuilder failures = new StringBuilder();
        for (String[] row : NVIM) {
            h = EditorHarness.create().vim();
            h.value(row[0]).cursor(0, 0);
            h.exCommand("s#" + row[1] + "#[&]#g");
            if (!row[2].equals(h.value()))
                failures.append("\n  ")
                    .append(row[1])
                    .append(" on \"")
                    .append(row[0])
                    .append("\": nvim \"")
                    .append(row[2])
                    .append("\", we \"")
                    .append(h.value())
                    .append('"');
            h.close();
            h = null;
        }
        if (failures.length() > 0)
            fail("patterns that disagree with nvim:" + failures);
    }

    // ---------------------------------------------------------- the pipeline

    @Test
    public void searchUsesTheSameRules() {
        // Not only :s -- / goes through the same translation.
        h = EditorHarness.create().vim();
        h.value("aaa bbb").cursor(0, 0).keys("/").searchPattern("b\\+");
        assertEquals(4, h.offset());
    }

    @Test
    public void globalUsesTheSameRules() {
        h = EditorHarness.create().vim();
        h.value("a+\nab\na+b").cursor(0, 0).exCommand("g/a+/d");
        assertEquals("ab", h.value(), "the + is literal, so only the lines with a+ go");
    }

    @Test
    public void tildeIsTheLastReplacement() {
        // nvim: after :s/x/y/, ~z finds "yz".
        h = EditorHarness.create().vim();
        h.value("x yz").cursor(0, 0).exCommand("s/x/y/");
        h.exCommand("s/~z/[&]/");
        assertEquals("y [yz]", h.value());
    }

    // ------------------------------------------------------------ smartcase

    @Test
    public void anEscapedCapitalIsNotAnUpperCaseLetter() {
        // 'smartcase' looks for an upper case letter; the S in \S is part of
        // an escape and vim does not count it. This counted it before.
        assertFalse(VimRegex.translate("a\\Sb").hasUppercase);
        assertFalse(VimRegex.translate("\\_S").hasUppercase);
        assertTrue(VimRegex.translate("aBc").hasUppercase);
        assertTrue(VimRegex.translate("[A-Z]").hasUppercase, "inside a class it counts");
    }

    @Test
    public void caseFlagsInThePatternAreReported() {
        assertEquals(Boolean.TRUE, VimRegex.translate("\\cfoo").ignoreCase);
        assertEquals(Boolean.FALSE, VimRegex.translate("foo\\C").ignoreCase);
        assertEquals(null, VimRegex.translate("foo").ignoreCase);
    }

    // ------------------------------------------------------------- refusals

    @Test
    public void whatCannotBeExpressedIsRefusedByName() {
        // A silently wrong pattern is worse than a clear refusal.
        for (String p : new String[] { "\\%V", "\\%#", "a\\&b",
            "\\(a\\zsb\\)", "a\\|b\\zsc" }) {
            try {
                VimRegex.translate(p);
                fail("expected " + p + " to be refused");
            }
            catch (PatternSyntaxException expected) {
                assertTrue(expected.getDescription() != null, p + ": " + expected.getDescription());
            }
        }
    }

    @Test
    public void aRefusalReachesTheUserAsAMessageNotAnException() {
        // Through every door a pattern comes in by. Only :s was tested at
        // first, and it was the one path that happened to catch a refusal:
        // / and the /pat/ address let it escape into the key handler.
        h = EditorHarness.create().vim();
        h.value("abc\nxyz").cursor(0, 0).exCommand("s/\\%V/x/");
        assertEquals("abc\nxyz", h.value());
        h.keys("/").searchPattern("\\%V");
        h.exCommand("/\\%V/d");
        h.exCommand("g/a\\&b/d");
        h.exCommand("sort r/\\%V/");
        assertEquals("abc\nxyz", h.value());
        assertEquals(0, h.lineNumber(), "the caret has not moved either");
    }

    @Test
    public void whatJavaRefusesIsNotCalledNotFound() {
        // \zs becomes a lookbehind, and Java refuses a lookbehind around a
        // quantified group -- though not around a*, which it takes. nvim
        // makes "ababx" of this; we refuse it, and the message must say the
        // pattern is invalid, not that a search which never ran found
        // nothing. A documented gap.
        h = EditorHarness.create().vim();
        h.value("ababc").cursor(0, 0).exCommand("s/\\(ab\\)*\\zsc/x/");
        assertEquals("ababc", h.value(), "nothing is changed");
        final String message = VimExSubstitute.badPattern(
            "\\(ab\\)*\\zsc",
            new PatternSyntaxException(
                "Look-behind group does not have an "
                    + "obvious maximum length",
                "",
                0
            )
        );
        assertTrue(message.startsWith("E383"), message);
    }

    @Test
    public void zsAfterSomethingOfVariableWidthIsRefused() {
        // The lookbehind \zs becomes is satisfied at the leftmost place it
        // can be; vim's \zs lands after the greedy match. .*\zsfoo is the
        // last foo in nvim and was every foo here, silently. Refused by
        // name until \zs is done properly.
        h = EditorHarness.create().vim();
        h.value("foo x foo").cursor(0, 0).exCommand("s/.*\\zsfoo/X/");
        assertEquals("foo x foo", h.value());
        for (String p : new String[] { "a\\+\\zsb", "\\(a\\|bc\\)\\zsd",
            "\\(a\\)\\1\\zsb", "a\\{1,2}\\zsb" }) {
            try {
                VimRegex.translate(p);
                fail("expected " + p + " to be refused");
            }
            catch (PatternSyntaxException expected) {
                // as intended
            }
        }
    }

    @Test
    public void butAFixedWidthPrefixIsExactAndAllowed() {
        // With a fixed width the leftmost lookbehind and vim's greedy \zs
        // land in the same place. \{3} is still fixed.
        h = EditorHarness.create().vim();
        h.value("aaab").cursor(0, 0).exCommand("s/a\\{3}\\zsb/X/");
        assertEquals("aaaX", h.value());
    }

    @Test
    public void unbalancedGroupsAreErrors() {
        for (String p : new String[] { "\\(ab", "ab\\)" }) {
            try {
                VimRegex.translate(p);
                fail("expected " + p + " to be refused");
            }
            catch (PatternSyntaxException expected) {
                // as vim does: E54 and E55
            }
        }
    }
}
