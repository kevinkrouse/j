/*
 * PythonEscapeTest.java
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

package org.armedbear.j.mode.python;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.junit.jupiter.api.Test;

/** Escape splitting in Python literals, with prefix and raw/bytes context. */
public class PythonEscapeTest {
    private static String runs(String text) {
        return EditorHarness.runs(Editor.getModeList().getMode(Constants.PYTHON_MODE), text);
    }

    @Test
    public void ordinaryStringsSplitEscapes() {
        assertEquals("identifier(s) operator(=) string(\")string(a)escape(\\n)string(b)string(\")",
                runs("s = \"a\\nb\""));
        assertEquals("identifier(s) operator(=) string(')string(a)escape(\\')string(b)string(')",
                runs("s = 'a\\'b'"));
        assertEquals("identifier(s) operator(=) string(\")escape(\\x41)string(\")",
                runs("s = \"\\x41\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\u0041)string(\")",
                runs("s = \"\\u0041\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\N{BULLET})string(\")",
                runs("s = \"\\N{BULLET}\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\012)string(\")",
                runs("s = \"\\012\""));
    }

    @Test
    public void malformedEscapesAreErrors() {
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\q)string(\")",
                runs("s = \"\\q\""));
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\x4)string(\")",
                runs("s = \"\\x4\""));
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\N{)string(\")",
                runs("s = \"\\N{\""));
    }

    @Test
    public void rawLiteralsStayNeutral() {
        assertEquals("identifier(s) operator(=) identifier(r)string(\"a\\nb\")",
                runs("s = r\"a\\nb\""));
        assertEquals("identifier(s) operator(=) identifier(rb)string(\"a\\nb\")",
                runs("s = rb\"a\\nb\""));
        assertEquals("identifier(s) operator(=) identifier(br)string(\"x\\ny\")",
                runs("s = br\"x\\ny\""));
    }

    @Test
    public void bytesExcludeUnicodeForms() {
        assertEquals("identifier(s) operator(=) identifier(b)string(\")escape(\\x41)string(\")",
                runs("s = b\"\\x41\""));
        // The introducer is an error; the digits stay plain string.
        assertEquals("identifier(s) operator(=) identifier(b)string(\")escapeError(\\u)string(0041)string(\")",
                runs("s = b\"\\u0041\""));
    }

    @Test
    public void fStringsStayNeutral() {
        // Expression boundaries are not parsed; the body is left whole.
        assertEquals("identifier(s) operator(=) identifier(f)string(\"{x}\\n\")",
                runs("s = f\"{x}\\n\""));
    }

    @Test
    public void templateStringsStayNeutral() {
        // t/tr/rt are template prefixes: the body is left whole, so a
        // backslash that is not a valid escape is not an error.
        assertEquals("identifier(s) operator(=) identifier(t)string(\"\\q\")",
                runs("s = t\"\\q\""));
        assertEquals("identifier(s) operator(=) identifier(tr)string(\"\\q\")",
                runs("s = tr\"\\q\""));
        assertEquals("identifier(s) operator(=) identifier(rt)string(\"\\q\")",
                runs("s = rt\"\\q\""));
    }

    @Test
    public void identifiersEndingInPrefixLettersAreOrdinary() {
        assertEquals("identifier(bar) operator(=) string(\")string(x)escape(\\n)string(y)string(\")",
                runs("bar = \"x\\ny\""));
        // Adjacent to the quote, the walk-back still reads the whole name.
        assertEquals("identifier(bar)string(\")string(x)escape(\\n)string(y)string(\")",
                runs("bar\"x\\ny\""));
    }

    @Test
    public void tripleQuotesSplitOnEachLine() {
        assertEquals("identifier(s) operator(=) string(\"\"\")string(a)escape(\\n)string(b)string(\"\"\")",
                runs("s = \"\"\"a\\nb\"\"\""));
        // A literal spanning lines: each physical line is its own run.
        assertEquals("identifier(s) operator(=) string(\"\"\")\n"
                + "string(  )string(a)escape(\\n)string(b)\n"
                + "string(  )string(\"\"\")",
                runs("s = \"\"\"\n  a\\nb\n  \"\"\""));
    }

    @Test
    public void rawTripleQuotesStayNeutralAcrossLines() {
        assertEquals("identifier(s) operator(=) identifier(r)string(\"\"\")\n"
                + "string(  )string(a\\nb  )\n"
                + "string(  )string(\"\"\")",
                runs("s = r\"\"\"\n  a\\nb  \n  \"\"\""));
    }

    @Test
    public void trailingBackslashContinuesWhenALineFollows() {
        assertEquals("identifier(s) operator(=) string(\")string(a)escape(\\)\n"
                + "string(b\")",
                runs("s = \"a\\\nb\""));
        // On the last line there is nothing to continue onto.
        assertEquals("identifier(s) operator(=) string(\")string(a)escapeError(\\)",
                runs("s = \"a\\"));
    }

    @Test
    public void commentsAndStringsAreIndependent() {
        assertEquals("identifier(s) operator(=) string(\")escape(\\n)string(\")"
                + " comment(# \\q is not an escape)",
                runs("s = \"\\n\" # \\q is not an escape"));
    }
}
