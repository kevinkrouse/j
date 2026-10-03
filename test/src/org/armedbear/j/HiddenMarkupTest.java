/*
 * HiddenMarkupTest.java
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * Hidden markup is the display's, for any formatter that marks it: here one
 * that hides the braces of {{word}}.
 */
public class HiddenMarkupTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        Editor.preferences().removeProperty("conceal");
        if (h != null)
            h.close();
    }

    private static final class BraceFormatter extends Formatter
    {
        private static final Pattern ITEM = Pattern.compile("\\{\\{(\\w+)\\}\\}");

        BraceFormatter(Buffer buffer)
        {
            this.buffer = buffer;
        }

        public boolean hidesMarkup()
        {
            return conceals("braces");
        }

        public LineSegmentList formatLine(Line line)
        {
            clearSegmentList();
            final String text = line.getText();
            final boolean hide = hidesMarkup();
            final Matcher m = ITEM.matcher(text);
            int at = 0;
            int item = 0;
            while (m.find()) {
                if (m.start() > at)
                    addSegment(text, at, m.start(), 0);
                ++item;
                addSegment(text, m.start(), m.start(1), 0, hide, item);
                addSegment(text, m.start(1), m.end(1), 0, false, item);
                addSegment(text, m.end(1), m.end(), 0, hide, item);
                at = m.end();
            }
            addSegment(text, at, text.length(), 0);
            return segmentList;
        }

        public FormatTable getFormatTable()
        {
            if (formatTable == null) {
                formatTable = new FormatTable(null);
                formatTable.addEntryFromPrefs(0, "text");
            }
            return formatTable;
        }
    }

    private EditorHarness on(String text)
    {
        Editor.preferences().setProperty("conceal", "braces");
        h = EditorHarness.create(text);
        h.buffer().setFormatter(new BraceFormatter(h.buffer()));
        return h;
    }

    private String drawn()
    {
        return h.editor().getDisplay().drawnText(h.buffer().getFirstLine());
    }

    @Test
    public void anyFormattersMarkupHides()
    {
        on("a {{b}} c {{d}}\nnext").cursor(1, 0);
        assertEquals("a b c d", drawn());
    }

    @Test
    public void theCaretsItemShows()
    {
        on("a {{b}} c {{d}}").cursor(0, 4);
        assertEquals("a {{b}} c d", drawn());
        h.cursor(0, 15);
        assertEquals("a b c {{d}}", drawn());
    }

    @Test
    public void notWhenTheFormatterIsNotAsked()
    {
        on("a {{b}} c\nnext").cursor(1, 0);
        Editor.preferences().setProperty("conceal", "none");
        assertEquals("a {{b}} c", drawn());
    }
}
