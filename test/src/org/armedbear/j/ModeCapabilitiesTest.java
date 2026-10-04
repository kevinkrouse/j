/*
 * ModeCapabilitiesTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The mode methods that replaced switches on mode ids in WrapText and PropertiesDialog. */
public class ModeCapabilitiesTest {
    private static Mode mode(int id) {
        return Editor.getModeList().getMode(id);
    }

    @Test
    public void wrapCommentStart() {
        for (int id : new int[] { JAVA_MODE, JAVASCRIPT_MODE, C_MODE, CPP_MODE }) {
            assertEquals("// ", mode(id).getWrapCommentStart("// text"));
            assertEquals("* ", mode(id).getWrapCommentStart("* text"));
            assertNull(mode(id).getWrapCommentStart("text"));
        }
        for (int id : new int[] { PERL_MODE, PROPERTIES_MODE }) {
            assertEquals("# ", mode(id).getWrapCommentStart("# text"));
            assertNull(mode(id).getWrapCommentStart("text"));
        }
        assertEquals(mode(PHP_MODE).getCommentStart(), mode(PHP_MODE).getWrapCommentStart("// text"));
        assertEquals(mode(LISP_MODE).getCommentStart(), mode(LISP_MODE).getWrapCommentStart("text"));
    }

    @Test
    public void indentBeforeBrace() {
        for (int id : new int[] { JAVA_MODE, JAVASCRIPT_MODE, C_MODE, CPP_MODE, PERL_MODE })
            assertTrue(mode(id).supportsIndentBeforeBrace());
        for (int id : new int[] { PHP_MODE, PYTHON_MODE, LISP_MODE, PLAIN_TEXT_MODE })
            assertFalse(mode(id).supportsIndentBeforeBrace());
    }
}
