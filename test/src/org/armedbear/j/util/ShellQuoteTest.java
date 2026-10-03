/*
 * ShellQuoteTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** maybeQuote makes any string one word that /bin/sh expands nothing in. */
public class ShellQuoteTest {
    @TempDir
    Path dir;

    @Test
    public void plainWordsAreLeftAlone() {
        assertEquals("src/Foo.java", Utilities.maybeQuote("src/Foo.java"));
    }

    @Test
    public void theShellReadsBackWhatWasQuoted() throws Exception {
        assumeTrue(Platform.isPlatformUnix());
        final String[] words = {
            "a b", "it's", "a;touch pwned", "$(touch pwned)", "`touch pwned`",
            "\"quoted\"", "back\\slash", "", "-rf", "*", "new\nline",
        };
        for (String word : words)
            assertEquals(word, echo(Utilities.maybeQuote(word)), word);
        assertFalse(Files.exists(dir.resolve("pwned")));
    }

    private String echo(String quoted) throws Exception {
        final Process p = new ProcessBuilder("/bin/sh", "-c", "printf %s " + quoted)
            .directory(dir.toFile())
            .start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }
}
