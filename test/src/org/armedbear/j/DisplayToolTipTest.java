/*
 * DisplayToolTipTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A tool tip of several lines shown as several. */
public class DisplayToolTipTest {
    @Test
    public void oneLineIsLeftAsItIs() {
        assertNull(Display.multiline(null));
        assertEquals("a <b>", Display.multiline("a <b>"));
        assertEquals("<html>a<br>b</html>", Display.multiline("<html>a<br>b</html>"));
        assertEquals("<HTML>a\nb", Display.multiline("<HTML>a\nb"));
    }

    @Test
    public void severalAreHtmlEscapedTheirSpacesKept() {
        assertEquals(
            "<html>Ann&nbsp;&lt;a@example.com&gt;<br><br>&nbsp;&nbsp;-&nbsp;x&nbsp;&nbsp;&amp;&nbsp;y</html>",
            Display.multiline("Ann <a@example.com>\n\n  - x  & y"));
    }

    @Test
    public void tabsReachTheNextEighthColumn() {
        assertEquals(
            "<html>ab&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;c<br>" + "&nbsp;".repeat(8) + "d</html>",
            Display.multiline("ab\tc\n\td"));
    }
}
