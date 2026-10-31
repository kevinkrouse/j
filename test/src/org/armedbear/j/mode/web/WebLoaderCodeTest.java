/*
 * WebLoaderCodeTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSequence;
import org.junit.jupiter.api.Test;

/** Code, as &lt;code&gt;, set apart in a page, and wrapped as the words around it are. */
public class WebLoaderCodeTest implements WebConstants {
    @Test
    public void codeIsItsOwnFormatALinkInItStillALink() {
        final List<LineSegment> s =
                segments(load("<p>one <code>none</code> or <code><a href=\"#x\">x</a></code>").get(0));
        assertEquals("none", find(s, "none").getText());
        assertEquals(FORMAT_CODE, find(s, "none").getFormat() & FORMAT_CODE);
        assertEquals(0, find(s, "one").getFormat() & FORMAT_CODE);
        assertEquals(FORMAT_CODE | FORMAT_LINK, find(s, "x").getFormat() & (FORMAT_CODE | FORMAT_LINK));
    }

    @Test
    public void aWordWithCodeInItWrapsWhole() {
        // Code that ends the line, the text after it over: "(" goes with it.
        final String words = "word ".repeat(14) + "ends ";
        assertEquals(75, words.length());
        final List<Line> lines = load("<p>" + words + "(<code>--ab</code>), on");
        assertEquals(words.strip(), lines.get(0).getText());
        assertEquals("(--ab), on", lines.get(1).getText().strip());
        // Ending in the code itself, as before.
        final List<Line> over = load("<p>" + words + "(<code>--ignore-space-at-eol</code>), on");
        assertEquals(words.strip(), over.get(0).getText());
        assertEquals("(--ignore-space-at-eol), on", over.get(1).getText().strip());
    }

    private static List<Line> load(String html) {
        final LineSequence seq = new WebLoader(new StringReader(html)).load();
        final List<Line> lines = new ArrayList<>();
        for (Line l = seq.getFirstLine(); l != null; l = l.next())
            if (!l.getText().isBlank())
                lines.add(l);
        return lines;
    }

    private static List<LineSegment> segments(Line line) {
        final List<LineSegment> list = new ArrayList<>();
        final var segments = ((WebLine) line).getSegmentList();
        for (int i = 0; i < segments.size(); i++)
            list.add(segments.getSegment(i));
        return list;
    }

    private static LineSegment find(List<LineSegment> segments, String text) {
        for (LineSegment s : segments)
            if (s.getText().strip().equals(text))
                return s;
        throw new AssertionError(text + " not in " + segments.stream().map(LineSegment::getText).toList());
    }
}
