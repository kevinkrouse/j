/*
 * CFamilyFormatterTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** What JavaFormatter's parseBuffer leaves in each line's flags, for Java and C. */
public class CFamilyFormatterTest {
    private static int[] flags(int modeId, String text) {
        EditorHarness h = EditorHarness.create(text).mode(Editor.getModeList().getMode(modeId));
        try {
            Buffer buffer = h.buffer();
            buffer.getFormatter().parseBuffer();
            int[] flags = new int[buffer.getLineCount()];
            int i = 0;
            for (Line line = buffer.getFirstLine(); line != null; line = line.next())
                flags[i++] = line.flags();
            return flags;
        }
        finally {
            h.close();
        }
    }

    @Test
    public void anIfZeroBlockIsDisabledUpToItsElse() {
        int[] f = flags(C_MODE, "#if 0\nnot compiled\n#else\ncompiled\n#endif\n");
        assertEquals(STATE_DISABLED, f[0]);
        assertEquals(STATE_DISABLED, f[1]);
        assertEquals(STATE_NEUTRAL, f[2]);
        assertEquals(STATE_NEUTRAL, f[3]);
    }

    @Test
    public void anIfZeroBlockIncludesItsEndif() {
        int[] f = flags(C_MODE, "#if 0\nx\n#endif\ny\n");
        assertEquals(STATE_DISABLED, f[2]);
        assertEquals(STATE_NEUTRAL, f[3]);
    }

    @Test
    public void aCStringContinuesOnlyAfterABackslash() {
        assertEquals(STATE_QUOTE, flags(C_MODE, "s = \"abc\\\ndef\";\n")[1]);
        assertEquals(STATE_NEUTRAL, flags(C_MODE, "s = \"abc\nx;\n")[1]);
    }

    @Test
    public void aJavaStringEndsWithItsLine() {
        assertEquals(STATE_NEUTRAL, flags(JAVA_MODE, "s = \"abc\\\ndef\";\n")[1]);
        assertEquals(STATE_COMMENT, flags(JAVA_MODE, "/* a\nb */\n")[1]);
    }

    @Test
    public void onlyJavaHasTextBlocks() {
        // In C, four quotes are two empty strings: the next line is code.
        assertEquals(STATE_NEUTRAL, flags(C_MODE, "char *s = \"\"\"\";\nint after = 1;\n")[1]);
        assertEquals(STATE_NEUTRAL, flags(JAVASCRIPT_MODE, "s = \"\"\"\";\nx = 1;\n")[1]);
        assertEquals(STATE_TEXT_BLOCK, flags(JAVA_MODE, "s = \"\"\"\nx\n\"\"\";\n")[1]);
    }

    @Test
    public void anEscapedQuoteDoesNotCloseATextBlock() {
        int[] f = flags(JAVA_MODE, "s = \"\"\"\n  esc \\\"\"\" still\n  \"\"\";\nx;\n");
        assertEquals(STATE_TEXT_BLOCK, f[2]);
        assertEquals(STATE_NEUTRAL, f[3]);
    }

    // --- escape splitting in emitted literals --------------------------
    //
    // runs() renders every segment as name(text), with the plain "text"
    // format printed bare. So a line like `s = "\n"` carries its
    // identifier and operator segments too; only the literal's content
    // splits into string/escape/escapeError. The opening quote stays in
    // the base string format and merges with the first content chunk.

    private static String runs(int modeId, String text) {
        return EditorHarness.runs(Editor.getModeList().getMode(modeId), text);
    }

    @Test
    public void javaStringsSplitEscapes() {
        assertEquals("identifier(s) operator(=) string(\")escape(\\n)string(\")",
                runs(JAVA_MODE, "s = \"\\n\""));
        assertEquals("identifier(s) operator(=) string(\"a)escape(\\n)string(b\")",
                runs(JAVA_MODE, "s = \"a\\nb\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\\\)string(\")",
                runs(JAVA_MODE, "s = \"\\\\\""));
    }

    @Test
    public void javaMalformedEscapesAreErrors() {
        // Backslash-u with only two hex digits: the introducer plus the
        // digits found are one error; the G stays plain string.
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\u12)string(G\")",
                runs(JAVA_MODE, "s = \"\\u12G\""));
        // Java's strict profile: an unknown escape is an error.
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\q)string(\")",
                runs(JAVA_MODE, "s = \"\\q\""));
    }

    @Test
    public void javaDelimitersStayInStringFormat() {
        // The quotes keep the string format; only the content splits.
        assertEquals("keyword(char) identifier(c) operator(=) string(')escape(\\n)string(');",
                runs(JAVA_MODE, "char c = '\\n';"));
    }

    @Test
    public void javaTextBlockSplitsWithContinuation() {
        // Each physical line is its own run; the escape lives on the
        // middle line, the delimiters on the first and last.
        assertEquals("identifier(s) operator(=) string(\"\"\")\n"
                + "string(  )string(a)escape(\\n)string(b)\n"
                + "string(  )string(\"\"\");",
                runs(JAVA_MODE, "s = \"\"\"\n  a\\nb\n  \"\"\";"));
    }

    @Test
    public void javaTrailingBackslashIsError() {
        // No closing quote: the backslash is content, and the strict
        // profile marks the dangling backslash an error.
        assertEquals("identifier(s) operator(=) string(\"x)escapeError(\\)",
                runs(JAVA_MODE, "s = \"x\\"));
    }

    @Test
    public void cStringsSplitEscapes() {
        assertEquals("identifier(s) operator(=) string(\")escape(\\n)string(\")",
                runs(C_MODE, "s = \"\\n\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\x41)string(\")",
                runs(C_MODE, "s = \"\\x41\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\e)string(\")",
                runs(C_MODE, "s = \"\\e\""));
    }

    @Test
    public void cDisabledCodeStaysUntouched() {
        String r = runs(C_MODE, "#if 0\ns = \"\\n\";\n#endif\n");
        assertEquals("disabled(#if 0)\ndisabled(s = \"\\n\";)\ndisabled(#endif)", r);
    }

    @Test
    public void cCommentsStayUntouched() {
        String r = runs(C_MODE, "// \\n is not an escape\n");
        assertEquals("comment(// \\n is not an escape)", r);
    }

    @Test
    public void javascriptStringsSplitEscapes() {
        // JS identity escapes are fine, not errors.
        assertEquals("identifier(s) operator(=) string(\")escape(\\q)string(\")",
                runs(JAVASCRIPT_MODE, "s = \"\\q\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\x41)string(\")",
                runs(JAVASCRIPT_MODE, "s = \"\\x41\""));
        assertEquals("identifier(s) operator(=) string(\")escape(\\u{1F600})string(\")",
                runs(JAVASCRIPT_MODE, "s = \"\\u{1F600}\""));
        // \x needs two hex digits; one is malformed.
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\x4)string(\")",
                runs(JAVASCRIPT_MODE, "s = \"\\x4\""));
    }

    @Test
    public void cppRawLiteralsStayNeutral() {
        // R"(...)": the body carries no escape coloring; the backslash
        // stays ordinary text inside the string.
        assertEquals("keyword(auto) identifier(s) operator(=) identifier(R)string(\"(a\\nb)\");",
                runs(CPP_MODE, "auto s = R\"(a\\nb)\";"));
    }

    @Test
    public void adjacentStringsDoNotBleed() {
        assertEquals("identifier(s) operator(=) string(\"a\") operator(+) string(\")escape(\\n)string(\");",
                runs(JAVA_MODE, "s = \"a\" + \"\\n\";"));
    }

    @Test
    public void malformedBracedEscapeDoesNotEatTheClosingQuote() {
        // Unterminated braced escape: the error stops at the content
        // boundary; the closing quote stays in the string format.
        assertEquals("identifier(s) operator(=) string(\")escapeError(\\u{41)string(\")",
                runs(JAVASCRIPT_MODE, "s = \"\\u{41\""));
    }

    @Test
    public void textBlockTrailingBackslashOnLastLineIsError() {
        // No physical line break follows, so the backslash cannot be a
        // continuation: the strict profile calls it an error.
        assertEquals("identifier(s) operator(=) string(\"\"\"abc)escapeError(\\)",
                runs(JAVA_MODE, "s = \"\"\"abc\\"));
    }

    @Test
    public void identifierEndingInRIsTreatedAsRaw() {
        // The introducer check is the single character before the quote,
        // so an identifier ending in R reads as a raw introducer and
        // the string stays neutral. Accepted: the error is missing
        // highlighting, never a wrong escape error.
        assertEquals("identifier(FOOR)string(\"x\\ny\")",
                runs(CPP_MODE, "FOOR\"x\\ny\""));
    }

    @Test
    public void textBlockTrailingBackslashIsContinuation() {
        // A backslash at the end of a text-block line continues the
        // line: escape, not error.
        assertEquals("identifier(s) operator(=) string(\"\"\")\n"
                + "string(    )string(a )escape(\\)\n"
                + "string(    )string(b\"\"\");",
                runs(JAVA_MODE, "s = \"\"\"\n    a \\\n    b\"\"\";"));
    }

    @Test
    public void javascriptLegacyOctalIsOneEscape() {
        assertEquals("identifier(s) operator(=) string(\")escape(\\012)string(\")",
                runs(JAVASCRIPT_MODE, "s = \"\\012\""));
        // \08: the octal run stops at 8, which stays plain string.
        assertEquals("identifier(s) operator(=) string(\")escape(\\0)string(8\")",
                runs(JAVASCRIPT_MODE, "s = \"\\08\""));
    }

    @Test
    public void repeatedFormatLineIsStable() {
        EditorHarness h = EditorHarness.create("s = \"a\\nb\";\n").mode(Editor.getModeList().getMode(JAVA_MODE));
        try {
            Buffer buffer = h.buffer();
            buffer.getFormatter().parseBuffer();
            Line line = buffer.getFirstLine();
            String first = render(buffer.getFormatter().formatLine(line));
            String again = render(buffer.getFormatter().formatLine(line));
            assertEquals(first, again);
        } finally {
            h.close();
        }
    }

    private static String render(LineSegmentList segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            LineSegment segment = segments.getSegment(i);
            sb.append(segment.getFormat()).append('(').append(segment.getText()).append(')');
        }
        return sb.toString();
    }
}
