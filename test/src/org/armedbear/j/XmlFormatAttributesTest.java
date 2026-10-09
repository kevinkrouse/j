/*
 * XmlFormatAttributesTest.java
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

import org.armedbear.j.mode.xml.XmlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** xmlFormatAttributes: a start tag's attributes laid out by splitAttributes and wrapCol. */
public class XmlFormatAttributesTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness xml(String text, String splitAttributes, int wrapCol) {
        h = EditorHarness.create(text).mode(XmlMode.getMode());
        h.buffer().setProperty(Property.SPLIT_ATTRIBUTES, splitAttributes);
        h.buffer().setProperty(Property.WRAP_COL, wrapCol);
        h.buffer().getFormatter().parseBuffer();
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    private void on(int line, int offset) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < line; i++)
            l = l.next();
        h.editor().setDot(l, offset);
        h.editor().moveCaretToDotCol();
    }

    @Test
    public void forceAlignedPutsEachAttributeOnALine() {
        xml(
            "<a>\n  <target name=\"build\" depends=\"init\"   description = 'x'>\n  </target>\n</a>\n",
            "force-aligned",
            80);
        on(1, 4);
        XmlMode.xmlFormatAttributes();
        h.assertText(
            "<a>\n" + "  <target name=\"build\"\n" + "          depends=\"init\"\n" + "          description='x'>\n"
                    + "  </target>\n" + "</a>\n");
    }

    @Test
    public void forceAlignedJoinsAClosingBracketOnItsOwnLine() {
        xml("<echo a=\"1\"\n  b=\"2\"\n/>\n", "force-aligned", 80);
        XmlMode.xmlFormatAttributes();
        // The whitespace before /> stays a space, as lemminx's spaceBeforeEmptyCloseTag.
        h.assertText("<echo a=\"1\"\n      b=\"2\" />\n");
    }

    @Test
    public void preserveWrapsPastWrapCol() {
        xml("<target name=\"build\" depends=\"init\" description=\"compile it\">\n</target>\n", "preserve", 40);
        XmlMode.xmlFormatAttributes();
        h.assertText("<target name=\"build\" depends=\"init\"\n" + "    description=\"compile it\">\n" + "</target>\n");
    }

    @Test
    public void preserveAlignedWrapsUnderTheFirstAttribute() {
        xml("<target name=\"build\" depends=\"init\" description=\"compile it\">\n</target>\n", "preserve-aligned", 40);
        XmlMode.xmlFormatAttributes();
        h.assertText(
            "<target name=\"build\" depends=\"init\"\n" + "        description=\"compile it\">\n" + "</target>\n");
    }

    @Test
    public void preserveKeepsTheBreaksThereAre() {
        xml("<target name=\"build\"\n depends=\"init\"\n>\n</target>\n", "preserve", 0);
        XmlMode.xmlFormatAttributes();
        h.assertText("<target name=\"build\"\n    depends=\"init\"\n    >\n</target>\n");
    }

    @Test
    public void wrapColZeroNeverWraps() {
        final String text = "<target name=\"build\" depends=\"init\" description=\"compile it\" if=\"x\">\n</target>\n";
        xml(text, "preserve", 0);
        XmlMode.xmlFormatAttributes();
        h.assertText(text);
    }

    @Test
    public void aRegionFormatsEveryStartTagInIt() {
        xml("<a x=\"1\" y=\"2\">\n<!-- <b p=\"1\" q=\"2\"> -->\n<c m=\"1\" n=\"2\"/>\n</a>\n", "force-aligned", 80);
        h.editor().setMark(new Position(h.buffer().getFirstLine(), 0));
        on(3, 0);
        XmlMode.xmlFormatAttributes();
        h.assertText("<a x=\"1\"\n   y=\"2\">\n<!-- <b p=\"1\" q=\"2\"> -->\n<c m=\"1\"\n   n=\"2\"/>\n</a>\n");
    }

    @Test
    public void oneUndoPutsItBack() {
        final String text = "<target name=\"build\" depends=\"init\">\n</target>\n";
        xml(text, "force-aligned", 80);
        XmlMode.xmlFormatAttributes();
        EditCommands.undo(h.editor());
        h.assertText(text);
    }

    @Test
    public void twoTagsOnALineEachAlignWhereTheyEndUp() {
        xml("<row a=\"1\" b=\"2\"><cell x=\"1\" y=\"2\"/></row>\n", "force-aligned", 80);
        h.editor().setMark(new Position(h.buffer().getFirstLine(), 0));
        on(0, 10);
        XmlMode.xmlFormatAttributes();
        h.assertText("<row a=\"1\"\n     b=\"2\"><cell x=\"1\"\n                 y=\"2\"/></row>\n");
    }
}
