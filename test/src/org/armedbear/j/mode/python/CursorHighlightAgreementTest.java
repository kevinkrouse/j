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

package org.armedbear.j.mode.python;

import org.armedbear.j.CursorHighlightAgreement;
import org.junit.jupiter.api.Test;

/**
 * The Python cursor and the highlighting parser must agree on where
 * comments and strings are. They are two loops over the same text; this
 * guard fails if they ever drift. The triple-quote fixtures exercise the
 * multi-character string delimiter.
 */
public class CursorHighlightAgreementTest {
    private static final int COMMENT = PythonFormatter.PYTHON_FORMAT_COMMENT;
    private static final int STRING = PythonFormatter.PYTHON_FORMAT_STRING;

    private static final String[] FIXTURES = {
        "x = \"a\\\"b\" # c '''d''' e",
        "s = 'a\\'b' # cmt",
        "'''blk''' x",
        "# just a comment",
        "\"only a string\"",
        "a#x b\"y\"z",
    };

    @Test
    public void pythonAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(PythonMode.getMode(), t, COMMENT, STRING);
    }
}
