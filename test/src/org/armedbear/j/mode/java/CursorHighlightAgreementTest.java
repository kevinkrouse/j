/*
 * CursorHighlightAgreementTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.mode.java;

import org.armedbear.j.CursorHighlightAgreement;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.js.JavaScriptMode;
import org.junit.jupiter.api.Test;

/**
 * The Java/C/JS cursor and the highlighting parser must agree on where
 * comments and strings are. They are two loops over the same text; this
 * guard fails if they ever drift. The fixtures include the JavaScript
 * regex-literal case the review flagged: the highlighting treats
 * {@code /"/g} as a string and so does the cursor, so they agree.
 */
public class CursorHighlightAgreementTest {
    private static final int COMMENT = JavaFormatter.JAVA_FORMAT_COMMENT;
    private static final int STRING = JavaFormatter.JAVA_FORMAT_STRING;

    private static final String[] FIXTURES = {
        "int x = 1; /* a */ y = \"z\"; // tail",
        "x = \"a\\\"b\"; // c /* d */ e = 'f\\'g';",
        "/* lead */ code",
        "a \"str\" b /* cmt */ d",
        "replace(/\"/g, '')",
        "var re = /\"/g; x = (1);",
        "// just a comment",
        "\"only a string\"",
        "a/*x*/b//y",
    };

    @Test
    public void javaAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(JavaMode.getMode(), t, COMMENT, STRING, "//", "/*");
    }

    @Test
    public void cAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(CMode.getMode(), t, COMMENT, STRING, "//", "/*");
    }

    @Test
    public void jsAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(JavaScriptMode.getMode(), t, COMMENT, STRING, "//", "/*");
    }
}
