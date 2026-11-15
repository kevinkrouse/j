/*
 * HtmlReferenceTest.java
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

import org.armedbear.j.mode.html.HtmlMode;
import org.junit.jupiter.api.Test;

/**
 * HTML character references split into escape and escapeError segments in
 * character data and in eligible attribute values. Comments, script and
 * style contents are raw and take no references; JavaScript event-handler
 * attributes are excluded.
 */
public class HtmlReferenceTest {
    private static String runs(String text) {
        return EditorHarness.runs(HtmlMode.getMode(), text);
    }

    // The segments must cover every character of the line: concatenated,
    // they equal the input. A dropped character (a closing quote, say)
    // shows up here as a mismatch.
    private static void assertCoverage(String text) {
        EditorHarness h = EditorHarness.create(text).mode(HtmlMode.getMode());
        try {
            Buffer b = h.buffer();
            b.getFormatter().parseBuffer();
            StringBuilder sb = new StringBuilder();
            for (Line line = b.getFirstLine(); line != null; line = line.next()) {
                if (sb.length() > 0)
                    sb.append('\n');
                LineSegmentList segs = b.getFormatter().formatLine(line);
                for (int i = 0; i < segs.size(); i++)
                    sb.append(segs.getSegment(i).getText());
            }
            assertEquals(text, sb.toString(),
                "segments must cover [" + text.replace("\n", "\\n") + "]");
        } finally {
            h.close();
        }
    }

    @Test
    public void characterDataSplitsReferences() {
        assertEquals("tag(<p>)hello escape(&amp;) goodbyetag(</p>)",
            runs("<p>hello &amp; goodbye</p>"));
        assertEquals("tag(<p>)a escape(&amp;) b escape(&lt;) ctag(</p>)",
            runs("<p>a &amp; b &lt; c</p>"));
    }

    @Test
    public void doubleQuotedAttributeValueSplits() {
        // The closing quote is its own tag-format piece; it must render.
        assertEquals("anchor(<a href=\")anchor(x)escape(&amp;)anchor(y)"
                + "anchor(\")anchor(>)linkanchor(</a>)",
            runs("<a href=\"x&amp;y\">link</a>"));
    }

    @Test
    public void singleQuotedAttributeValueSplits() {
        assertEquals("anchor(<a href=')anchor(a)escape(&lt;)anchor(b)"
                + "anchor(')anchor(>)linkanchor(</a>)",
            runs("<a href='a&lt;b'>link</a>"));
    }

    @Test
    public void unquotedAttributeValueSplits() {
        assertEquals("anchor(<a href=)anchor(x)escape(&amp;)anchor(y)anchor(>)"
                + "linkanchor(</a>)",
            runs("<a href=x&amp;y>link</a>"));
    }

    @Test
    public void numericReferences() {
        assertEquals("anchor(<a href=\")escape(&#233;)anchor(\")anchor(>)"
                + "xanchor(</a>)",
            runs("<a href=\"&#233;\">x</a>"));
    }

    @Test
    public void malformedNumericIsError() {
        assertEquals("tag(<p>)escapeError(&#xZZ;)tag(</p>)",
            runs("<p>&#xZZ;</p>"));
    }

    @Test
    public void bareAmpersandStaysPlain() {
        assertEquals("tag(<p>)&tag(</p>)", runs("<p>&</p>"));
    }

    @Test
    public void semicolonlessNamedStaysPlain() {
        assertEquals("tag(<p>)&amptag(</p>)", runs("<p>&amp</p>"));
    }

    @Test
    public void queryStringStaysPlain() {
        // A bare ampersand in ordinary text is not a reference.
        assertEquals("?a=1&b=2", runs("?a=1&b=2"));
    }

    @Test
    public void eventHandlerValueIsExcluded() {
        // A JavaScript event handler is code, not a reference context.
        assertEquals("anchor(<a onclick=\")anchor(go('&amp;'))anchor(\")"
                + "anchor(>)xanchor(</a>)",
            runs("<a onclick=\"go('&amp;')\">x</a>"));
    }

    @Test
    public void eventHandlerExcludedOtherValuesSplit() {
        assertEquals("anchor(<a href=\")anchor(a)escape(&amp;)anchor(b)"
                + "anchor(\")anchor( onclick=\")anchor(x('c&amp;d'))anchor(\")"
                + "anchor(>)tanchor(</a>)",
            runs("<a href=\"a&amp;b\" onclick=\"x('c&amp;d')\">t</a>"));
    }

    @Test
    public void quotedGtDoesNotEndTag() {
        // A `>` inside a quoted value is content; the tag keeps its color.
        assertEquals("tag(<div title=\")tag(a > b)tag(\")tag(>)xtag(</div>)",
            runs("<div title=\"a > b\">x</div>"));
    }

    @Test
    public void multilineQuotedValueSplits() {
        assertEquals("anchor(<a href=\")anchor(multi)\\nescape(&amp;)"
                + "anchor(line)anchor(\")anchor(>)xanchor(</a>)",
            runs("<a href=\"multi\n&amp;line\">x</a>").replace("\n", "\\n"));
    }

    @Test
    public void multilineEventBitCarried() {
        // The event-handler bit survives to the next line: the onclick
        // value stays whole, the href value on the next line splits.
        assertEquals("anchor(<a onclick=\")anchor(f('a&amp;b'))anchor(\")\\n"
                + "anchor(   href=\")anchor(c)escape(&amp;)anchor(d)anchor(\")"
                + "anchor(>)tanchor(</a>)",
            runs("<a onclick=\"f('a&amp;b')\"\n   href=\"c&amp;d\">t</a>")
                .replace("\n", "\\n"));
    }

    @Test
    public void pendingValueOpensOnNextLine() {
        // `=` at end of line: the value opens on the next line.
        assertEquals("anchor(<a href=)\\nanchor(x)escape(&amp;)anchor(y)"
                + "anchor(>)linkanchor(</a>)",
            runs("<a href=\nx&amp;y>link</a>").replace("\n", "\\n"));
    }

    @Test
    public void unquotedValueEndsButTagContinues() {
        // An unquoted value ends at the newline, but the tag stays open:
        // the next line continues between attributes, and the event bit
        // resets so the next attribute is eligible.
        assertEquals("anchor(<a onclick=)anchor(x)\\nanchor( href=\")"
                + "anchor(c)escape(&amp;)anchor(d)anchor(\")anchor(>)"
                + "tanchor(</a>)",
            runs("<a onclick=x\n href=\"c&amp;d\">t</a>").replace("\n", "\\n"));
    }

    @Test
    public void commentsAreRaw() {
        assertEquals("comment(<!-- &amp; -->)", runs("<!-- &amp; -->"));
    }

    @Test
    public void scriptContentIsRaw() {
        // The `&amp;` inside a script is JavaScript, not a reference.
        assertEquals("tag(<script>)script(&amp;)tag(</script>)",
            runs("<script>&amp;</script>"));
    }

    @Test
    public void styleContentIsRaw() {
        assertEquals("tag(<style>)&amp;tag(</style>)",
            runs("<style>&amp;</style>"));
    }

    @Test
    public void titleIsEligible() {
        assertEquals("tag(<title>)escape(&amp;)tag(</title>)",
            runs("<title>&amp;</title>"));
    }

    @Test
    public void textareaIsEligible() {
        assertEquals("tag(<textarea>)escape(&amp;)tag(</textarea>)",
            runs("<textarea>&amp;</textarea>"));
    }

    @Test
    public void elementClassificationPreserved() {
        assertEquals("table(<table>)tableRow(<tr>)tableData(<td>)"
                + "escape(&amp;)tableData(</td>)tableRow(</tr>)table(</table>)",
            runs("<table><tr><td>&amp;</td></tr></table>"));
    }

    @Test
    public void imageTagPreserved() {
        assertEquals("image(<img src=\")image(a.png)image(\")image(>)",
            runs("<img src=\"a.png\">"));
    }

    @Test
    public void selfClosingTagPreserved() {
        assertEquals("anchor(<a href=\")anchor(a)escape(&amp;)anchor(b)"
                + "anchor(\")anchor(/>)",
            runs("<a href=\"a&amp;b\"/>"));
    }

    @Test
    public void noAmpersandLeavesTextWhole() {
        assertEquals("tag(<br>)", runs("<br>"));
    }

    @Test
    public void segmentsCoverEveryCharacter() {
        // The closing quote and every other character must be in a segment.
        assertCoverage("<a href=\"x&amp;y\">link</a>");
        assertCoverage("<a href='a&lt;b'>link</a>");
        assertCoverage("<a href=\"\">x</a>");
        assertCoverage("<a href=\"multi\n&amp;line\">x</a>");
        assertCoverage("<a href=\"a&amp;b\" onclick=\"x('c&amp;d')\">t</a>");
        assertCoverage("<a onclick=\"f('a&amp;b')\"\n   href=\"c&amp;d\">t</a>");
        assertCoverage("<div title=\"a > b\">x</div>");
        assertCoverage("<a href=\"a&amp;b\"/>");
        assertCoverage("<a href = \"x&amp;y\">link</a>");
        assertCoverage("<a href=\nx&amp;y>link</a>");
        assertCoverage("<a onclick=x\n href=\"c&amp;d\">t</a>");
        assertCoverage("<style media=\"print\">&amp;</style>");
        assertCoverage("<img src=\"a.png\">");
    }
}
