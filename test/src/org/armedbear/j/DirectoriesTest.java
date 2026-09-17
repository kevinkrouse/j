/*
 * DirectoriesTest.java
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

package org.armedbear.j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The XDG layout and its escape hatch. Every case here goes through the
 * <code>--home</code> form of {@link Directories#initialize}, which ignores
 * the $XDG_* variables, so the results do not depend on the environment the
 * tests happen to run in.
 */
public class DirectoriesTest
{
    private Path home;

    @Before
    public void createHome() throws IOException
    {
        home = Files.createTempDirectory("j-directories-test");
    }

    @After
    public void removeHome() throws IOException
    {
        if (home == null || !Files.exists(home))
            return;
        try (Stream<Path> paths = Files.walk(home)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void initialize()
    {
        initialize(false);
    }

    private void initialize(boolean migrate)
    {
        Directories.initialize(File.getInstance(home.toString()), migrate);
    }

    private Path path(String first, String... more)
    {
        return home.resolve(Paths.get(first, more));
    }

    private void write(String relative, String contents) throws IOException
    {
        Path p = home.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.write(p, contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertSameFile(Path expected, File actual)
    {
        assertEquals(expected.toString(), actual.canonicalPath());
    }

    @Test
    public void freshHomeUsesTheXdgLayout()
    {
        initialize();

        assertFalse(Directories.isLegacyLayout());
        assertSameFile(path(".config", "j"), Directories.getConfigDirectory());
        assertSameFile(path(".local", "share", "j"), Directories.getDataDirectory());
        assertSameFile(path(".local", "state", "j"), Directories.getStateDirectory());
        assertSameFile(path(".cache", "j"), Directories.getCacheDirectory());
    }

    @Test
    public void subdirectoriesHangOffTheRightRoot()
    {
        initialize();

        assertSameFile(path(".cache", "j", "temp"), Directories.getTempDirectory());
        assertSameFile(path(".local", "share", "j", "mail"),
                       Directories.getMailDirectory());
        assertSameFile(path(".local", "share", "j", "mail", "local", "drafts"),
                       Directories.getDraftsFolder());
        assertSameFile(path(".local", "share", "j", "registers"),
                       Directories.getRegistersDirectory());
    }

    @Test
    public void runtimeFallsBackToTheStateDirectory()
    {
        // $XDG_RUNTIME_DIR cannot apply here, since --home ignores the
        // environment.
        initialize();

        assertSameFile(path(".local", "state", "j"),
                       Directories.getRuntimeDirectory());
    }

    @Test
    public void existingLegacyDirectoryWins() throws IOException
    {
        write(".j/prefs", "an old configuration");

        initialize();

        assertTrue(Directories.isLegacyLayout());
        // Every root is ~/.j, so every file keeps the path it always had.
        assertSameFile(path(".j"), Directories.getConfigDirectory());
        assertSameFile(path(".j"), Directories.getDataDirectory());
        assertSameFile(path(".j"), Directories.getStateDirectory());
        assertSameFile(path(".j"), Directories.getCacheDirectory());
        assertSameFile(path(".j"), Directories.getRuntimeDirectory());
        assertSameFile(path(".j", "mail"), Directories.getMailDirectory());
        assertSameFile(path(".j", "temp"), Directories.getTempDirectory());

        assertTrue(Files.exists(path(".j", "prefs")));
        assertFalse(Files.exists(path(".config", "j")));
        assertFalse(Files.exists(path(".cache", "j")));
    }

    @Test
    public void migrationMovesEachFileToItsRoot() throws IOException
    {
        write(".j/prefs", "prefs");
        write(".j/init.lisp", "lisp");
        write(".j/MyExtension.class", "bytecode");
        write(".j/mail/local/drafts/1", "a draft");
        write(".j/registers/a", "a register");
        write(".j/recent", "recent files");
        write(".j/tagfiles/catalog", "tags");

        initialize(true);

        assertFalse(Directories.isLegacyLayout());
        assertEquals("prefs", read(".config/j/prefs"));
        assertEquals("lisp", read(".config/j/init.lisp"));
        assertEquals("bytecode", read(".config/j/MyExtension.class"));
        assertEquals("a draft", read(".local/share/j/mail/local/drafts/1"));
        assertEquals("a register", read(".local/share/j/registers/a"));
        assertEquals("recent files", read(".local/state/j/recent"));
        assertEquals("tags", read(".cache/j/tagfiles/catalog"));
    }

    @Test
    public void migrationDiscardsFilesThatAreRecreatedAnyway() throws IOException
    {
        write(".j/prefs", "prefs");
        write(".j/port", "12345");
        write(".j/swank", "12346");
        write(".j/log", "chatter");
        write(".j/log.1", "older chatter");

        initialize(true);

        assertFalse(Files.exists(path(".local", "state", "j", "log")));
        assertFalse(Files.exists(path(".local", "state", "j", "port")));
    }

    @Test
    public void migrationLeavesNothingThatLooksLikeALegacyDirectory()
        throws IOException
    {
        write(".j/prefs", "prefs");

        initialize(true);

        assertFalse("~/.j must be gone, or the next run reverts to it",
                    Files.exists(path(".j")));
    }

    @Test
    public void migrationKeepsWhatItDoesNotRecognize() throws IOException
    {
        write(".j/prefs", "prefs");
        write(".j/mystery.txt", "something a user put here");

        initialize(true);

        // Moved aside rather than deleted, and out of the way of the legacy
        // check so that the next startup still uses the XDG layout.
        assertFalse(Files.exists(path(".j")));
        assertEquals("something a user put here", read(".j.migrated/mystery.txt"));
    }

    @Test
    public void migrationIsIdempotent() throws IOException
    {
        write(".j/prefs", "prefs");

        initialize(true);
        initialize(true);

        assertFalse(Directories.isLegacyLayout());
        assertEquals("prefs", read(".config/j/prefs"));
    }

    @Test
    public void migrationDoesNotOverwriteAnExistingFile() throws IOException
    {
        write(".j/prefs", "the old one");
        write(".config/j/prefs", "the new one");

        initialize(true);

        assertEquals("the new one", read(".config/j/prefs"));
        // The old copy is not destroyed; it is moved aside with ~/.j.
        assertEquals("the old one", read(".j.migrated/prefs"));
    }

    private String printDirectories()
    {
        PrintStream saved = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Directories.printDirectories();
        } finally {
            System.setOut(saved);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void printsEachResolvedDirectory()
    {
        initialize();

        String printed = printDirectories();

        assertTrue(printed, printed.contains("config   " + path(".config", "j")));
        assertTrue(printed, printed.contains("data     " + path(".local", "share", "j")));
        assertTrue(printed, printed.contains("state    " + path(".local", "state", "j")));
        assertTrue(printed, printed.contains("cache    " + path(".cache", "j")));
        assertTrue(printed, printed.contains("runtime  " + path(".local", "state", "j")));
        assertFalse("no migration hint is due under the XDG layout",
                    printed.contains("--migrate-to-xdg"));
    }

    @Test
    public void printsWhereMigrationWouldLeadUnderTheLegacyLayout()
        throws IOException
    {
        write(".j/prefs", "an old configuration");
        initialize();

        String printed = printDirectories();

        // Every root resolves to ~/.j ...
        assertEquals(5, countOccurrences(printed, path(".j").toString()));
        // ... and the hint says what migrating would give instead.
        assertTrue(printed, printed.contains("--migrate-to-xdg"));
        assertTrue(printed, printed.contains("config   " + path(".config", "j")));
        assertTrue(printed, printed.contains("data     " + path(".local", "share", "j")));
        assertTrue(printed, printed.contains("state    " + path(".local", "state", "j")));
        assertTrue(printed, printed.contains("cache    " + path(".cache", "j")));
    }

    @Test
    public void printingCreatesNothing() throws IOException
    {
        write(".j/prefs", "an old configuration");
        initialize();

        printDirectories();

        // Naming the XDG directories in the hint must not bring them into
        // being: that would make j look migrated when it is not.
        assertFalse(Files.exists(path(".config")));
        assertFalse(Files.exists(path(".local")));
        assertFalse(Files.exists(path(".cache")));
    }

    private static int countOccurrences(String haystack, String needle)
    {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
             i = haystack.indexOf(needle, i + needle.length()))
            ++count;
        return count;
    }

    private String read(String relative) throws IOException
    {
        Path p = home.resolve(relative);
        assertTrue(p + " does not exist", Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
