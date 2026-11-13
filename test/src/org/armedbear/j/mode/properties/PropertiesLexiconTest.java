/*
 * PropertiesLexiconTest.java
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The comment/continuation classifier shared by formatting and the
 * queries. Odd trailing backslashes continue; even ones are escaped.
 * Leading whitespace before a marker still makes a comment; a
 * continuation starting with a marker does not.
 */
public class PropertiesLexiconTest {
    @Test
    public void oddTrailingBackslashContinues() {
        assertTrue(PropertiesLexicon.continuesFrom("key = value \\"));
        assertTrue(PropertiesLexicon.continuesFrom("\\"));
        assertTrue(PropertiesLexicon.continuesFrom("a\\\\\\"));
    }

    @Test
    public void evenTrailingBackslashDoesNotContinue() {
        assertFalse(PropertiesLexicon.continuesFrom("key = value \\\\"));
        assertFalse(PropertiesLexicon.continuesFrom("\\\\"));
        assertFalse(PropertiesLexicon.continuesFrom("a\\\\"));
    }

    @Test
    public void noTrailingBackslashDoesNotContinue() {
        assertFalse(PropertiesLexicon.continuesFrom("key = value"));
        assertFalse(PropertiesLexicon.continuesFrom(""));
        assertFalse(PropertiesLexicon.continuesFrom(null));
    }

    @Test
    public void markerAtStartIsComment() {
        assertTrue(PropertiesLexicon.isComment("# comment", false));
        assertTrue(PropertiesLexicon.isComment("! bang", false));
        assertTrue(PropertiesLexicon.isComment("; semi", false));
    }

    @Test
    public void leadingWhitespaceBeforeMarkerIsComment() {
        assertTrue(PropertiesLexicon.isComment("   # indented comment", false));
        assertTrue(PropertiesLexicon.isComment("\t! tab bang", false));
    }

    @Test
    public void continuationIsNotComment() {
        // A continuation whose text starts with a marker is value text,
        // not a fresh comment.
        assertFalse(PropertiesLexicon.isComment("# continues the value", true));
        assertFalse(PropertiesLexicon.isComment("! continues", true));
    }

    @Test
    public void ordinaryTextIsNotComment() {
        assertFalse(PropertiesLexicon.isComment("key = value", false));
        assertFalse(PropertiesLexicon.isComment("\"quoted\" and don't", false));
        assertFalse(PropertiesLexicon.isComment("", false));
    }
}
