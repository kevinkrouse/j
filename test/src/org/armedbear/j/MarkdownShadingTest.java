/*
 * MarkdownShadingTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.awt.Color;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.After;
import org.junit.Test;

/** Code's shaded background: a code block's lines, inline code's text. */
public class MarkdownShadingTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        Editor.preferences().removeProperty("color.codeBackground");
        if (h != null)
            h.close();
    }

    private Formatter on(String text)
    {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        return h.buffer().getFormatter();
    }

    private Line line(int lineNumber)
    {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            line = line.next();
        return line;
    }

    @Test
    public void aCodeBlocksLinesAreShaded()
    {
        final Formatter f = on("text\n```java\nint x;\n```\n\n    indented\nafter");
        assertNull(f.getLineBackground(line(0)));
        final Color shade = f.getLineBackground(line(1));
        assertNotNull(shade);
        assertEquals(shade, f.getLineBackground(line(2)));
        assertEquals(shade, f.getLineBackground(line(3)));
        assertNull(f.getLineBackground(line(4)));
        assertEquals(shade, f.getLineBackground(line(5)));
        assertNull(f.getLineBackground(line(6)));
    }

    @Test
    public void inlineCodeIsShadedAsABlockIs()
    {
        final Formatter f = on("a `b` c\n```\nx\n```");
        final LineSegmentList segments = f.formatLine(line(0));
        final Color shade = f.getLineBackground(line(2));
        int shaded = 0;
        for (int i = 0; i < segments.size(); i++) {
            final Color c = f.getRunBackground(segments.getSegment(i).getFormat());
            if (c != null) {
                assertEquals(shade, c);
                ++shaded;
            }
        }
        assertEquals(3, shaded); // `, b, `
    }

    @Test
    public void theShadeIsBetweenTheBackgroundAndTheText()
    {
        final Formatter f = on("```\nx\n```");
        final Color bg = f.getBackgroundColor();
        final Color shade = f.getLineBackground(line(1));
        final Color text = f.getColor(0);
        assertEquals(true, Math.abs(shade.getRed() - bg.getRed())
                     <= Math.abs(text.getRed() - bg.getRed()));
        assertEquals(false, shade.equals(bg));
    }

    @Test
    public void aThemeCanSayTheShade()
    {
        Editor.preferences().setProperty("color.codeBackground", "#123456");
        final Formatter f = on("```\nx\n```");
        assertEquals(new Color(0x123456), f.getLineBackground(line(1)));
    }

    @Test
    public void onALightThemeItIsNotTheCurrentLines()
    {
        Editor.preferences().setProperty("color.background", "255 255 255");
        Editor.preferences().setProperty("color.currentLineBackground", "237 237 237");
        try {
            final Formatter f = on("```\nx\n```");
            // Half the current line's step, and cool: GitHub's code gray.
            assertEquals(new Color(246, 248, 250), f.getLineBackground(line(1)));
        }
        finally {
            Editor.preferences().removeProperty("color.background");
            Editor.preferences().removeProperty("color.currentLineBackground");
        }
    }
}
