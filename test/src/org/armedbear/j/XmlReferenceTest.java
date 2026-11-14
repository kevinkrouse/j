/*
 * XmlReferenceTest.java
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
import org.junit.jupiter.api.Test;

/**
 * Character references in XML character data and attribute values split
 * into escape and escapeError segments. Comments, CDATA, processing
 * instructions and DOCTYPE contents are skipped.
 */
public class XmlReferenceTest {
    private static String runs(String text) {
        return EditorHarness.runs(XmlMode.getMode(), text);
    }

    @Test
    public void characterDataSplitsReferences() {
        assertEquals("a" + "escape(&amp;)" + "b", runs("a&amp;b"));
        assertEquals("escape(&lt;)" + "x" + "escape(&gt;)", runs("&lt;x&gt;"));
    }

    @Test
    public void quotedAttributeValueSplitsValueOnly() {
        // The quotes stay in the string format; only the value splits.
        assertEquals("delimiter(<)tag(a)attribute( href)equals(=)"
                + "string(\"x)escape(&amp;)string(y\")delimiter(>)",
            runs("<a href=\"x&amp;y\">"));
    }

    @Test
    public void singleQuotedAttributeValueSplitsValueOnly() {
        assertEquals("delimiter(<)tag(a)attribute( href)equals(=)"
                + "string('a)escape(&lt;)string(b')delimiter(>)",
            runs("<a href='a&lt;b'>"));
    }

    @Test
    public void numericReferences() {
        assertEquals("delimiter(<)tag(a)attribute( href)equals(=)"
                + "string(\")escape(&#233;)string(\")delimiter(>)",
            runs("<a href=\"&#233;\">"));
    }

    @Test
    public void colonAndUnicodeNamesAreReferences() {
        assertEquals("escape(&xhtml:b;)", runs("&xhtml:b;"));
        assertEquals("escape(&café;)", runs("&café;"));
    }

    @Test
    public void malformedReferencesAreErrors() {
        assertEquals("a escapeError(&) b", runs("a & b"));
        assertEquals("escapeError(&#xZZ;)", runs("&#xZZ;"));
        assertEquals("escapeError(&#;)", runs("&#;"));
    }

    @Test
    public void commentsSkipReferences() {
        assertEquals("comment(<!-- &amp; -->)", runs("<!-- &amp; -->"));
    }

    @Test
    public void cdataSkipsReferences() {
        assertEquals("delimiter(<!)tag([CDATA[)cdata(&amp;)tag(]])delimiter(>)",
            runs("<![CDATA[&amp;]]>"));
    }

    @Test
    public void processingInstructionSkipsReferences() {
        // A PI's text is not character data: no reference splitting.
        assertEquals("delimiter(<?)tag(xml)attribute( version)equals(=)"
                + "string(\"1.0\")delimiter(?>)",
            runs("<?xml version=\"1.0\"?>"));
    }

    @Test
    public void doctypeInternalSubsetSkipsReferences() {
        // The subset's declarations keep their tag colors, but the
        // ampersand inside is not a reference.
        assertEquals("delimiter(<!)tag(DOCTYPE) a [ delimiter(<!)tag(ENTITY)"
                + "attribute( x )string(\"&amp;\")delimiter(>) ]>",
            runs("<!DOCTYPE a [ <!ENTITY x \"&amp;\"> ]>"));
    }

    @Test
    public void piInsideSubsetKeepsSubsetSuppression() {
        // A PI closed inside the subset clears only the PI suppression;
        // the subset still suppresses its own references, and the data
        // after `]>` is character data again.
        assertEquals("delimiter(<!)tag(DOCTYPE) a [ delimiter(<?)tag(pi)"
                + "delimiter(?>) delimiter(<!)tag(ENTITY)attribute( x )"
                + "string(\"&amp;\")delimiter(>) ]> delimiter(<)tag(p)"
                + "delimiter(>)escape(&amp;)delimiter(</)tag(p)delimiter(>)",
            runs("<!DOCTYPE a [ <?pi?> <!ENTITY x \"&amp;\"> ]> <p>&amp;</p>"));
    }

    @Test
    public void multilineAttributeValueSplitsAcrossLines() {
        // The value spans a line break; each line's references split.
        assertEquals("delimiter(<)tag(a)attribute( href)equals(=)"
                + "string(\"multi)escape(&amp;)\nstring(cont\")delimiter(>)",
            runs("<a href=\"multi&amp;\ncont\">"));
    }

    @Test
    public void noAmpersandLeavesTextWhole() {
        assertEquals("plain text", runs("plain text"));
    }
}
