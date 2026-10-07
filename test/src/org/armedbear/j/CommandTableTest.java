/*
 * CommandTableTest.java
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CommandTableTest {
    // Every key in j's own key maps runs a command the table has.
    @Test
    public void everyBoundCommandIsInTheTable() {
        List<String> missing = new ArrayList<>();
        check(KeyMap.getGlobalKeyMap(), "global", missing);
        for (ModeListEntry entry : Editor.getModeList()) {
            Mode mode = entry.getMode(true);
            if (mode != null)
                check(mode.getKeyMap(), entry.getDisplayName(), missing);
        }
        assertEquals(List.of(), missing);
    }

    private static void check(KeyMap keyMap, String where, List<String> missing) {
        for (KeyMapping mapping : keyMap.getMappings()) {
            if (mapping.getCommand() instanceof String name && !name.startsWith("(")) {
                String[] command = Editor.parseCommand(name);
                if (command != null && CommandTable.getCommand(command[0]) == null)
                    missing.add(where + ": " + name);
            }
        }
    }

    @Test
    public void everyCommandInTheTableCanRun() {
        for (String name : CommandTable.getCompletionsForPrefix(""))
            assertTrue(CommandTable.getCommand(name).isRunnable(), name);
    }

    @Test
    public void aCommandRunsTheFormItHas() throws Exception {
        EditorHarness h = EditorHarness.create("abc\n");
        try {
            assertTrue(h.editor().execute("insertString", "x"));
            assertEquals("xabc\n", h.text());
            assertThrows(NoSuchMethodException.class, () -> h.editor().execute("insertString", null));
            assertThrows(NoSuchMethodException.class, () -> h.editor().execute("noSuchCommand", null));
            assertFalse(CommandTable.getCommand("bol").run(h.editor(), "unexpected"));
        }
        finally {
            h.close();
        }
    }

    @Test
    public void summariesComeFromTheDocs() {
        assertEquals("Moves to the beginning of the buffer.", CommandTable.getSummary("bob"));
        assertEquals(CommandTable.getSummary("bob"), CommandTable.getSummary("BOB"));
        assertEquals(null, CommandTable.getSummary("noSuchCommand"));
    }

    @Test
    public void finderCommandsAreDocumented() {
        for (String name : new String[] {
            "findFileInProject",
            "findAction",
            "dirProjectDir",
            "rescanProject",
            "recentFiles",
            "switchBuffer",
            "findBookmark",
            "jumps",
            "changeList",
            "olderChange",
            "newerChange",
            "findDefinitionAtDot" })
            assertTrue(CommandTable.getSummary(name) != null, name);
    }

    @Test
    public void argumentForms() {
        assertTrue(CommandTable.getCommand("bob").takesNoArgument());
        assertFalse(CommandTable.getCommand("bob").takesArgument());
        assertFalse(CommandTable.getCommand("replaceChar").takesNoArgument());
        assertTrue(CommandTable.getCommand("replaceChar").takesArgument());
    }

    @Test
    public void humanizedNames() {
        assertEquals("Open File In Other Window", ActionTextFieldHandler.humanize("openFileInOtherWindow"));
        assertEquals("Bob", ActionTextFieldHandler.humanize("bob"));
        assertEquals("Vsplit Window", ActionTextFieldHandler.humanize("vsplitWindow"));
        assertEquals("Jdb Step", ActionTextFieldHandler.humanize("jdbStep"));
        assertEquals("Goto Line 2", ActionTextFieldHandler.humanize("gotoLine2"));
    }

    @Test
    public void abbreviationsRunButAreNotListed() {
        assertTrue(CommandTable.getCommand("ir") != null);
        assertFalse(CommandTable.getCommands().stream().anyMatch(c -> c.getName().equals("ir")));
        assertTrue(CommandTable.getCommands().stream().anyMatch(c -> c.getName().equals("insertRegister")));
    }
}
