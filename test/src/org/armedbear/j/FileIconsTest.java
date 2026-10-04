/*
 * FileIconsTest.java
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

import org.junit.jupiter.api.Test;

public class FileIconsTest {
    @Test
    public void byExtensionAndName() {
        assertEquals("file-code", FileIcons.iconName("KeyMap.java"));
        assertEquals("file-code", FileIcons.iconName("build.CLJ"));
        assertEquals("file-markup", FileIcons.iconName("README.md"));
        assertEquals("file-config", FileIcons.iconName("deps.edn"));
        assertEquals("file-config", FileIcons.iconName("Makefile"));
        assertEquals("file-config", FileIcons.iconName(".gitignore"));
        assertEquals("file-image", FileIcons.iconName("j-16.png"));
        assertEquals("buffer", FileIcons.iconName("COPYING"));
        assertEquals("buffer", FileIcons.iconName("notes.txt"));
    }
}
