/*
 * CommandRegistrationTest.java
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.armedbear.j.Command;
import org.armedbear.j.CommandTable;
import org.junit.Test;

/**
 * Registering a command from outside core.
 *
 * <p>The class is passed as a Class rather than a name on purpose: core
 * resolves built-in commands with Class.forName on its own loader, which can
 * never see a class living in an extension's loader.
 */
public class CommandRegistrationTest
{
    /** Stands in for an extension's command class. */
    public static final class TestCommands
    {
        public static boolean ran;
        public static String argument;

        public static void extensionTestCommand()
        {
            ran = true;
        }

        public static void extensionTestCommandWithArg(String s)
        {
            argument = s;
        }
    }

    @Test
    public void aRegisteredCommandIsFoundByName()
    {
        CommandTable.registerCommand("extensionTestCommand", TestCommands.class,
                                     "extensionTestCommand");

        Command command = CommandTable.getCommand("extensionTestCommand");
        assertNotNull(command);
        assertEquals("extensionTestCommand", command.getName());
        assertSame(TestCommands.class, command.getDeclaringClass());
    }

    @Test
    public void lookupStaysCaseInsensitive()
    {
        CommandTable.registerCommand("extensionCasedCommand", TestCommands.class,
                                     "extensionTestCommand");
        assertNotNull(CommandTable.getCommand("EXTENSIONCASEDCOMMAND"));
        assertNotNull(CommandTable.getCommand("extensioncasedcommand"));
    }

    @Test
    public void aRegisteredCommandShowsUpInCompletion()
    {
        CommandTable.registerCommand("extensionCompletionProbe", TestCommands.class,
                                     "extensionTestCommand");
        List<String> completions =
            CommandTable.getCompletionsForPrefix("extensionCompletionPro");
        assertTrue(completions.toString(),
                   completions.contains("extensionCompletionProbe"));
    }

    @Test
    public void aRegisteredCommandShowsUpInApropos()
    {
        CommandTable.registerCommand("extensionAproposProbe", TestCommands.class,
                                     "extensionTestCommand");
        assertTrue(CommandTable.apropos("AproposProbe").contains("extensionAproposProbe"));
    }

    /**
     * getCompletionsForPrefix and apropos used to read the command map without
     * forcing it to be built, so whichever ran first on a fresh table threw a
     * NullPointerException.
     */
    @Test
    public void completionDoesNotDependOnSomethingElseHavingRunFirst()
    {
        assertNotNull(CommandTable.getCompletionsForPrefix("op"));
        assertNotNull(CommandTable.apropos("open"));
    }

    @Test
    public void builtInCommandsHaveNoResolvedClass()
    {
        // They are resolved from a name at first use instead, which is what
        // keeps them lazy.
        Command command = CommandTable.getCommand("openFile");
        assertNotNull(command);
        assertNull(command.getDeclaringClass());
    }

    @Test
    public void registrationRejectsMissingArguments()
    {
        try {
            CommandTable.registerCommand(null, TestCommands.class, "extensionTestCommand");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException expected) {
        }
        try {
            CommandTable.registerCommand("x", null, "extensionTestCommand");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException expected) {
        }
    }
}
