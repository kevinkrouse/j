/*
 * MarkdownHidingTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.After;
import org.junit.Test;

/** Markdown's markup, hidden until the caret is in the item it marks. */
public class MarkdownHidingTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        Editor.preferences().removeProperty("MarkdownMode.conceal");
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text)
    {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    private Line line(int lineNumber)
    {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            line = line.next();
        return line;
    }

    // The line as drawn, with the caret where it is.
    private String drawn(int lineNumber)
    {
        return h.editor().getDisplay().drawnText(line(lineNumber));
    }

    // The runs of hidden text, "|" between.
    private String hidden(int lineNumber)
    {
        final LineSegmentList segments =
            h.buffer().getFormatter().formatLine(line(lineNumber));
        final StringBuilder sb = new StringBuilder();
        boolean inRun = false;
        for (int i = 0; i < segments.size(); i++) {
            final LineSegment segment = segments.getSegment(i);
            if (!segment.isHidden()) {
                inRun = false;
                continue;
            }
            if (!inRun && sb.length() > 0)
                sb.append('|');
            sb.append(segment.getText());
            inRun = true;
        }
        return sb.toString();
    }

    private static final String LINE =
        "see [the docs](http://x.y) and **b** `c` \\* <http://a.b> ![i](p.png)";

    @Test
    public void whatIsMarkup()
    {
        on(LINE);
        assertEquals("[|](http://x.y)|**|**|`|`|\\|<|>|![|](p.png)", hidden(0));
    }

    @Test
    public void hiddenWithTheCaretElsewhere()
    {
        on(LINE + "\nnext").cursor(1, 0);
        assertEquals("see the docs and b c * http://a.b i", drawn(0));
    }

    @Test
    public void theItemTheCaretIsInShowsItsMarkup()
    {
        on(LINE).cursor(0, 6);
        assertEquals("see [the docs](http://x.y) and b c * http://a.b i", drawn(0));
        // Just past an item's closing marker is in it too.
        h.cursor(0, 36);
        assertEquals("see the docs and **b** c * http://a.b i", drawn(0));
    }

    @Test
    public void anItemInsideALinkShowsWithIt()
    {
        on("[a `b` c](u)\n").cursor(0, 4);
        assertEquals("[a `b` c](u)", drawn(0));
        h.cursor(0, 1);
        assertEquals("[a b c](u)", drawn(0));
    }

    @Test
    public void aFenceShowsWithTheCaretAnywhereInIt()
    {
        on("text\n```java\nint x;\n```\nafter").cursor(0, 0);
        assertEquals("", drawn(1));
        assertEquals("int x;", drawn(2));
        assertEquals("", drawn(3));
        h.cursor(2, 0);
        assertEquals("```java", drawn(1));
        assertEquals("```", drawn(3));
        final Line[] block = h.buffer().getFormatter().getHiddenBlock(line(2));
        assertSame(line(1), block[0]);
        assertSame(line(3), block[1]);
        assertNull(h.buffer().getFormatter().getHiddenBlock(line(4)));
    }

    @Test
    public void noneHidesNothing()
    {
        Editor.preferences().setProperty("MarkdownMode.conceal", "none");
        on(LINE + "\nnext").cursor(1, 0);
        assertFalse(h.buffer().getFormatter().hidesMarkup());
        assertEquals(LINE, drawn(0));
    }

    @Test
    public void headingsHidesHeadingMarkersToo()
    {
        Editor.preferences().setProperty("MarkdownMode.conceal", "markup, headings");
        on("# Title #\nText\n====\nnext").cursor(3, 0);
        assertTrue(h.buffer().getFormatter().hidesMarkup());
        assertEquals("Title", drawn(0));
        assertEquals("", drawn(2));
        h.cursor(1, 0);
        assertEquals("====", drawn(2));
        h.cursor(0, 3);
        assertEquals("# Title #", drawn(0));
    }

    @Test
    public void headingMarkersShowByDefault()
    {
        on("# Title\nnext").cursor(1, 0);
        assertEquals("# Title", drawn(0));
    }

    @Test
    public void headingsAloneLeavesTheRest()
    {
        Editor.preferences().setProperty("MarkdownMode.conceal", "headings");
        on("# Title **b**\nnext").cursor(1, 0);
        assertEquals("Title **b**", drawn(0));
    }

    @Test
    public void aQuotesMarkersAreBarsInTheirOwnRoom()
    {
        on("> a\n> > b **c**\nnext").cursor(2, 0);
        // Each '>' a bar in its own room, so the text does not move.
        assertEquals("| a", drawn(0));
        assertEquals("| | b c", drawn(1));
        final LineSegmentList segments =
            h.buffer().getFormatter().formatLine(line(1));
        int bars = 0;
        for (int i = 0; i < segments.size(); i++)
            if (segments.getSegment(i).isBar())
                bars += segments.getSegment(i).length();
        assertEquals(2, bars);
        h.cursor(1, 4);
        assertEquals("> > b c", drawn(1));
    }
}
