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

package org.armedbear.j.mode.css;

import org.armedbear.j.CursorHighlightAgreement;
import org.junit.jupiter.api.Test;

/**
 * The CSS cursor and the highlighting parser must agree on where
 * comments and strings are. They are two loops over the same text; this
 * guard fails if they ever drift. The single-quote fixtures pin the
 * correction: a single-quoted value used to close on a double quote,
 * so the two loops disagreed on everything after the mismatched closer.
 */
public class CursorHighlightAgreementTest {
    private static final int COMMENT = CSSFormatter.CSS_FORMAT_COMMENT;
    private static final int STRING = CSSFormatter.CSS_FORMAT_STRING;

    private static final String[] FIXTURES = {
        "/* lead */ body { color: red; }",
        "a { content: \"x\"; }",
        "a { content: 'x'; color: red; }",
        "a { content: 'it\\'s'; }",
        "a { font: \"a\\\"b\"; }",
        "/* c */ a { b: 'c'; } /* d */",
        "a { b: 'x' /* not a closer */; }",
    };

    @Test
    public void cssAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(CSSMode.getMode(), t, COMMENT, STRING, "/*");
    }
}
