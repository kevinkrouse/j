/*
 * KeywordsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class KeywordsTest implements Constants {
    @Test
    public void anIncludedListCounts() {
        Mode cpp = Editor.getModeList().getMode(CPP_MODE);
        assertTrue(cpp.isKeyword("class"));
        assertTrue(cpp.isKeyword("struct"), "from CMode.keywords");
        assertFalse(cpp.isKeyword("#include"));
        assertFalse(cpp.isKeyword("banana"));
    }
}
