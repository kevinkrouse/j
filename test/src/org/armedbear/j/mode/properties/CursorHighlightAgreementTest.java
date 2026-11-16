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

package org.armedbear.j.mode.properties;

import org.armedbear.j.CursorHighlightAgreement;
import org.junit.jupiter.api.Test;

/**
 * The Properties cursor and the highlighting parser must agree on
 * comments. Fixtures cover the three markers, leading whitespace, and
 * a marker-prefixed continuation; Properties has no string syntax, so
 * the string direction is vacuous.
 */
public class CursorHighlightAgreementTest {
    private static final String[] FIXTURES = {
        "# comment",
        "! bang",
        "; semi",
        "  # indented comment",
        "key = value",
        "cont = value \\\n# continues the value",
        "escaped = a \\\\",
    };

    @Test
    public void propertiesAgrees() {
        for (String t : FIXTURES)
            CursorHighlightAgreement.assertAgrees(
                    PropertiesMode.getMode(), t,
                    PropertiesFormatter.PROPERTIES_FORMAT_COMMENT,
                    // -1: no string format exists, so the string
                    // direction can never fire.
                    -1);
    }
}
