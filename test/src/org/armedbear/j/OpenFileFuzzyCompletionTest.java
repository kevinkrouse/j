/*
 * OpenFileFuzzyCompletionTest.java
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ctrl-O's fallback when nothing starts with what was typed. */
public class OpenFileFuzzyCompletionTest {
    @TempDir
    Path tmp;

    private File dir() {
        return File.getInstance(tmp.toString());
    }

    @Test
    public void ranksTheNamedDirectorysEntries() throws IOException {
        Files.createDirectories(tmp.resolve("src/sub"));
        for (String s : new String[] { "src/OpenFileHandler.java", "src/Other.java", "src/Foo.class" })
            Files.writeString(tmp.resolve(s), "");
        assertEquals(
            List.of("src/OpenFileHandler.java", "src/Other.java"),
            OpenFileTextFieldHandler.fuzzyCompletions(dir(), "src/oh", "^.+\\.class$", true));
        assertEquals(List.of("src/sub/"), OpenFileTextFieldHandler.fuzzyCompletions(dir(), "src/sb", null, true));
    }

    @Test
    public void nothingForAMissingDirectoryOrNoName() throws IOException {
        assertEquals(List.of(), OpenFileTextFieldHandler.fuzzyCompletions(dir(), "nowhere/x", null, true));
        assertEquals(List.of(), OpenFileTextFieldHandler.fuzzyCompletions(dir(), "src/", null, true));
    }
}
