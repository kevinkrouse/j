/*
 * ModeListTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.armedbear.j.extension.ModeDescriptor;
import org.junit.jupiter.api.Test;

public class ModeListTest {
    private static final class TestMode extends AbstractMode {
        TestMode(int id, String name) {
            super(id, name);
        }
    }

    private final ModeList modes = Editor.getModeList();

    @Test
    public void builtinModesByNameAliasFileAndFence() {
        assertEquals(JAVA_MODE, modes.getModeIdFromModeName("java"));
        assertEquals(PLAIN_TEXT_MODE, modes.getModeIdFromModeName("text"));
        assertEquals(JAVA_MODE, modes.getModeIdForFileName("Foo.java"));
        assertEquals(PYTHON_MODE, modes.getModeIdForFenceName("py"));
        assertEquals(-1, modes.getModeIdForFenceName("no-such-language"));
    }

    @Test
    public void everyBuiltinModeCanBeMade() {
        for (ModeListEntry entry : modes) {
            if (entry.getId() < 1000)
                assertNotNull(entry.getMode(true), entry.getDisplayName());
        }
    }

    @Test
    public void anExtensionModeGetsAnId() {
        String name = "ModeListTest A";
        modes.register(
            () -> List.of(
                new ModeDescriptor(
                    0,
                    name,
                    TestMode.class,
                    id -> new TestMode(id, name),
                    true,
                    ".+\\.modelisttest",
                    List.of(),
                    List.of("modelisttest")
                )
            )
        );
        int id = modes.getModeIdFromModeName(name);
        assertTrue(id >= 1000, "id " + id);
        Mode mode = modes.getMode(id);
        assertEquals(id, mode.getId());
        assertSame(mode, modes.getMode(id));
        assertEquals(id, modes.getModeIdForFileName("Foo.modelisttest"));
        assertEquals(id, modes.getModeIdForFenceName("modelisttest"));
    }

    @Test
    public void aTakenIdOrAliasIsSkipped() {
        modes.register(
            () -> List.of(
                new ModeDescriptor(
                    JAVA_MODE,
                    "ModeListTest B",
                    TestMode.class,
                    id -> new TestMode(id, "B"),
                    true,
                    null,
                    List.of(),
                    List.of()
                ),
                new ModeDescriptor(
                    0,
                    "ModeListTest C",
                    TestMode.class,
                    id -> new TestMode(id, "C"),
                    true,
                    null,
                    List.of("text"),
                    List.of()
                )
            )
        );
        assertEquals(-1, modes.getModeIdFromModeName("ModeListTest B"));
        assertEquals(-1, modes.getModeIdFromModeName("ModeListTest C"));
    }

    @Test
    public void aNameAlreadyRegisteredIsSkipped() {
        modes.register(
            () -> List.of(
                new ModeDescriptor(
                    0,
                    JAVA_MODE_NAME,
                    TestMode.class,
                    id -> new TestMode(id, "Other"),
                    true,
                    null,
                    List.of(),
                    List.of()
                )
            )
        );
        assertEquals(JAVA_MODE, modes.getModeIdFromModeName(JAVA_MODE_NAME));
    }
}
