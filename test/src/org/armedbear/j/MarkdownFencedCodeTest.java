/*
 * MarkdownFencedCodeTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.awt.Color;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.python.PythonMode;
import org.armedbear.j.mode.sh.ShellScriptMode;
import org.junit.After;
import org.junit.Test;

/** A fence's code, colored as the mode for its language colors it. */
public class MarkdownFencedCodeTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        Editor.preferences().removeProperty("MarkdownMode.style.codeBlock");
        if (h != null)
            h.close();
    }

    private Line line(int lineNumber)
    {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            line = line.next();
        return line;
    }

    // Each character's color and style, as "#rrggbb/style", one per char.
    private static String[] looks(Formatter formatter, Line line, int styleMask)
    {
        final LineSegmentList segments = formatter.formatLine(line);
        final String[] looks = new String[line.length()];
        int pos = 0;
        for (int i = 0; i < segments.size(); i++) {
            final LineSegment segment = segments.getSegment(i);
            final int format = segment.getFormat();
            final Color c = formatter.getColor(format);
            final String look = String.format("#%06x/%d", c.getRGB() & 0xffffff,
                                              formatter.getStyle(format) & styleMask);
            for (int j = 0; j < segment.length() && pos < looks.length; j++)
                looks[pos++] = look;
        }
        return looks;
    }

    // The looks of code in its own mode.
    private String[] inOwnMode(String code, Mode mode)
    {
        final EditorHarness own = EditorHarness.create(code + "\n").mode(mode);
        try {
            own.buffer().getFormatter().parseBuffer();
            return looks(own.buffer().getFormatter(), own.buffer().getFirstLine(),
                         ~TextStyle.ITALIC);
        }
        finally {
            own.close();
        }
    }

    private void fenced(String info, String code)
    {
        h = EditorHarness.create("```" + info + "\n" + code + "\n```\n")
            .mode(MarkdownMode.getMode());
        h.buffer().getFormatter().parseBuffer();
    }

    private void assertColoredAs(String info, String code, Mode mode)
    {
        fenced(info, code);
        final String[] expected = inOwnMode(code, mode);
        final String[] actual = looks(h.buffer().getFormatter(), line(1), ~TextStyle.ITALIC);
        for (int i = 0; i < expected.length; i++)
            assertEquals(info + " at " + i + " of " + code, expected[i], actual[i]);
    }

    @Test
    public void javaIsColoredAsJavaModeColorsIt()
    {
        assertColoredAs("java", "public static int x = 42; // the answer", JavaMode.getMode());
    }

    @Test
    public void namesFencesUse()
    {
        assertColoredAs(" py", "def f(): return \"s\"  # c", PythonMode.getMode());
        assertColoredAs("{.sh}", "if [ -f x ]; then echo $HOME; fi", ShellScriptMode.getMode());
        assertColoredAs("bash title=x", "for f in *; do echo $f; done", ShellScriptMode.getMode());
        assertColoredAs("Java", "class A { }", JavaMode.getMode());
    }

    @Test
    public void codeIsItalicAsCodeBlocksAre()
    {
        fenced("java", "class A { }");
        final String[] looks = looks(h.buffer().getFormatter(), line(1), -1);
        // "class" is a bold keyword in Java mode: bold and italic here.
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC,
                     Integer.parseInt(looks[0].substring(looks[0].indexOf('/') + 1)));
    }

    @Test
    public void aPlainCodeBlockThemeMakesThemPlain()
    {
        Editor.preferences().setProperty("MarkdownMode.style.codeBlock", "plain");
        fenced("java", "class A { }");
        final String[] looks = looks(h.buffer().getFormatter(), line(1), -1);
        assertEquals(TextStyle.BOLD,
                     Integer.parseInt(looks[0].substring(looks[0].indexOf('/') + 1)));
    }

    @Test
    public void anUnknownLanguageOrMarkdownIsJustCode()
    {
        for (String info : new String[] { "nosuch", "markdown", "", "plain text" }) {
            fenced(info, "class A { }");
            final Formatter f = h.buffer().getFormatter();
            final String[] looks = looks(f, line(1), -1);
            for (String look : looks)
                assertEquals(info, looks[0], look);
            h.close();
            h = null;
        }
    }

    @Test
    public void theFencesStayFences()
    {
        fenced("java", "class A { }");
        final Formatter f = h.buffer().getFormatter();
        final String open = looks(f, line(0), -1)[0];
        final String close = looks(f, line(2), -1)[0];
        assertEquals(open, close);
        assertFalse(open.equals(looks(f, line(1), -1)[0]));
    }
}
