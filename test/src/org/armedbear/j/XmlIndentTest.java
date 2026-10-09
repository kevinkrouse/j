/*
 * XmlIndentTest.java
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

import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** XmlMode's indentation of a start tag's attributes, after splitAttributes. */
public class XmlIndentTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    /** The indentation getCorrectIndentation gives each line. */
    private List<Integer> indents(String text, String splitAttributes) {
        h = EditorHarness.create(text).mode(XmlMode.getMode());
        if (splitAttributes != null)
            h.buffer().setProperty(Property.SPLIT_ATTRIBUTES, splitAttributes);
        h.buffer().getFormatter().parseBuffer();
        final List<Integer> indents = new ArrayList<>();
        for (Line line = h.buffer().getFirstLine(); line != null; line = line.next())
            if (!line.isBlank())
                indents.add(XmlMode.getMode().getCorrectIndentation(line, h.buffer()));
        return indents;
    }

    private static final String TAG = "<project>\n" + "  <target name=\"build\"\n" + "depends=\"init\"\n"
            + "description=\"compile\">\n" + "<echo/>\n" + "</target>\n" + "</project>\n";

    @Test
    public void preserveIndentsAttributesTwoLevels() {
        // XmlMode's indentSize is 2; splitAttributesIndentSize 2 levels of it.
        assertEquals(List.of(0, 2, 6, 6, 4, 2, 0), indents(TAG, null));
    }

    @Test
    public void splitAttributesIndentSizeSetsTheLevels() {
        h = EditorHarness.create(TAG).mode(XmlMode.getMode());
        h.buffer().setProperty(Property.SPLIT_ATTRIBUTES_INDENT_SIZE, 1);
        h.buffer().getFormatter().parseBuffer();
        final Line attribute = h.buffer().getFirstLine().next().next();
        assertEquals(4, XmlMode.getMode().getCorrectIndentation(attribute, h.buffer()));
    }

    @Test
    public void alignedLinesUpWithTheFirstAttribute() {
        assertEquals(List.of(0, 2, 10, 10, 4, 2, 0), indents(TAG, "preserve-aligned"));
        assertEquals(List.of(0, 2, 10, 10, 4, 2, 0), indents(TAG, "forceAligned"));
    }

    @Test
    public void alignedWithNoAttributeOnTheTagLineFallsBack() {
        final String text = "<project>\n" + "  <target\n" + "name=\"build\">\n" + "</target>\n" + "</project>\n";
        assertEquals(List.of(0, 2, 6, 2, 0), indents(text, "force-aligned"));
    }

    @Test
    public void aClosingBracketOnItsOwnLineGoesWhereAnAttributeWould() {
        final String text = "<project>\n" + "  <target name=\"build\"\n" + "depends=\"init\"\n" + ">\n"
                + "    <echo message=\"hi\"\n" + "/>\n" + "</target>\n" + "</project>\n";
        assertEquals(List.of(0, 2, 6, 6, 4, 8, 2, 0), indents(text, null));
    }

    @Test
    public void aChildAfterAMultiLineEmptyElementTagStaysLevel() {
        final String text =
                "<project>\n" + "  <echo message=\"hi\"\n" + "level=\"info\"/>\n" + "<echo/>\n" + "</project>\n";
        assertEquals(List.of(0, 2, 6, 2, 0), indents(text, null));
    }

    @Test
    public void anElementClosedOnTheTagsLastLineStaysLevel() {
        final String text = "<project>\n" + "  <echo message=\"hi\"\n" + "level=\"info\">text</echo>\n" + "<echo/>\n"
                + "</project>\n";
        assertEquals(List.of(0, 2, 6, 2, 0), indents(text, null));
    }

    @Test
    public void aGreaterThanInAValueDoesNotEndTheTag() {
        final String text =
                "<project>\n" + "  <if test=\"a > b\"\n" + "then=\"c\">\n" + "<echo/>\n" + "</if>\n" + "</project>\n";
        assertEquals(List.of(0, 2, 6, 4, 2, 0), indents(text, null));
    }

    @Test
    public void enterBeforeTheClosingBracketLinesUpWithTheAttribute() {
        h = EditorHarness.create("<a>\n  <foo x=\"3\">\n</a>\n").mode(XmlMode.getMode());
        h.buffer().setProperty(Property.SPLIT_ATTRIBUTES, "preserve-aligned");
        h.buffer().getFormatter().parseBuffer();
        Editor.setCurrentEditor(h.editor());
        h.cursor(1, 12);
        EditCommands.newlineAndIndent(h.editor());
        h.assertText("<a>\n  <foo x=\"3\"\n       >\n</a>\n");
        h.assertCursorAt(2, 7);
    }
}
