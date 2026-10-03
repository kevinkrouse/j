/*
 * ModePreferenceKeyTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A mode's settings are keyed as the docs write them, "JavaMode.indentSize",
 * and still as they had to be while that was broken.
 */
public class ModePreferenceKeyTest {
    @AfterEach
    public void tearDown() {
        for (String key : new String[] { "JavaMode.indentSize",
            "mode.java.JavaMode.indentSize",
            "JavaMode.files" })
            Editor.preferences().removeProperty(key);
    }

    @Test
    public void asTheDocsWriteIt() {
        Editor.preferences().setProperty("JavaMode.indentSize", "7");
        assertEquals(7, JavaMode.getMode().getIntegerProperty(Property.INDENT_SIZE));
    }

    @Test
    public void asItWasKeyedInItsPackage() {
        Editor.preferences().setProperty("mode.java.JavaMode.indentSize", "5");
        assertEquals(5, JavaMode.getMode().getIntegerProperty(Property.INDENT_SIZE));
    }

    @Test
    public void filesAsTheDocsWriteIt() {
        assertTrue(Editor.getModeList().modeAccepts(Constants.JAVA_MODE, "A.java"));
        Editor.preferences().setProperty("JavaMode.files", ".+\\.jav");
        assertFalse(Editor.getModeList().modeAccepts(Constants.JAVA_MODE, "A.java"));
        assertTrue(Editor.getModeList().modeAccepts(Constants.JAVA_MODE, "A.jav"));
    }
}
