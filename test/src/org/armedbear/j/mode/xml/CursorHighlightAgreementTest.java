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

package org.armedbear.j.mode.xml;

import org.armedbear.j.CursorHighlightAgreement;
import org.junit.jupiter.api.Test;

/**
 * The XML cursor and the highlighting parser must agree on where
 * comments and attribute strings are. CDATA is OPAQUE, which the
 * agreement guard does not check (it only pins COMMENT and STRING
 * runs), so the CDATA fixtures exercise the cursor's own boundary
 * handling rather than agreement. The fixtures carry both quote kinds,
 * an empty attribute value, a prose apostrophe that must not open a
 * string, partial delimiters, and multi-line comments, CDATA and
 * attributes so both consumers of the shared state are pinned to the
 * same answer.
 */
public class CursorHighlightAgreementTest {
    private static final int COMMENT = XmlFormatter.XML_FORMAT_COMMENT;
    private static final int STRING = XmlFormatter.XML_FORMAT_QUOTE;

    private static final String[] FIXTURES = {
        "<!-- a comment -->",
        "<e a=\"v\" b='w'>t</e>",
        "<e a=\"\">empty</e>",
        "<e a=\"it's\">apostrophe in value</e>",
        "plain don't text",
        "<e>don't open a string</e>",
        "<!- not a comment",
        "<![CDATA[ raw <x> ]]>",
        "<!-- multi\nline comment -->",
        "<![CDATA[ multi\nline cdata ]]>",
        "<e a=\"one\nvalue\">x</e>",
        "<e a='one\nvalue'>x</e>",
        "<!-- c1 --><e a=\"v\"/><!-- c2 -->",
        "<!DOCTYPE e SYSTEM \"file:/schema.dtd\"><e/>",
        "<!DOCTYPE e PUBLIC '-//Example//DTD Example//EN' 'file:/schema.dtd'><e/>",
        "<!DOCTYPE e SYSTEM\n  \"file:/schema.dtd\">\n<e/>",
        "<!DOCTYPE e SYSTEM \"file:/\n  schema.dtd\">\n<e/>",
        "<!DOCTYPE e [\n<!-- internal comment -->\n<!ENTITY msg 'a value'>\n]>\n<e a=\"v\"/>",
        "<!DOCTYPE e SYSTEM \"unterminated at eof",
    };

    @Test
    public void xmlAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(
                    XmlMode.getMode(), t, COMMENT, STRING);
    }
}
