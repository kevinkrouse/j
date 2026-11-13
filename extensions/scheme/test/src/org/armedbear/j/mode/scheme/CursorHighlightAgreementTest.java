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

package org.armedbear.j.mode.scheme;

import org.armedbear.j.CursorHighlightAgreement;
import org.armedbear.j.Editor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Scheme cursor and the highlighting parser must agree on where
 * comments and strings are. The fixtures carry a block comment, a line
 * comment, a string and a backslash inside a comment so both the
 * multi-character marker and the escape paths are exercised in both
 * directions.
 */
public class CursorHighlightAgreementTest {
    private static final String[] FIXTURES = {
        "(define x \"a b\") #| blk |# ; tail",
        "#| a \\|# b",
        "\"str\" ; comment",
        "; just a comment",
        "(display \"a\\\"b\")",
        "#| multi\nline |# x",
        "\"multi\nline\" y",
    };

    @BeforeAll
    public static void registerModes() {
        Editor.getModeList().register(new SchemeModes());
    }

    @Test
    public void schemeAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(
                    SchemeMode.getMode(), t,
                    SchemeFormatter.SCHEME_FORMAT_COMMENT,
                    SchemeFormatter.SCHEME_FORMAT_STRING,
                    "#|");
    }
}
