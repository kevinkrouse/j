/*
 * ProcessRunnerTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.armedbear.j.File;
import org.armedbear.j.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
public class ProcessRunnerTest {
    @TempDir
    Path dir;

    @BeforeEach
    public void unix() {
        assumeTrue(Platform.isPlatformUnix());
    }

    @Test
    public void outputAndErrorsTogether() {
        ProcessRunner.Result r = ProcessRunner.of("sh", "-c", "echo out; echo err 1>&2; exit 3").run();
        assertEquals(3, r.exitValue());
        assertTrue(r.output().contains("out\n"), r.output());
        assertTrue(r.output().contains("err\n"), r.output());
    }

    @Test
    public void inputAndDirectory() throws Exception {
        ProcessRunner.Result r = ProcessRunner.of("sh", "-c", "pwd; cat")
            .directory(File.getInstance(dir.toString()))
            .input("hello\n")
            .run();
        assertTrue(r.succeeded());
        assertEquals(dir.toRealPath().toString() + "\nhello\n", r.output());
    }

    // A file name with shell syntax is just an argument.
    @Test
    public void argumentsAreNotShellCode() throws Exception {
        Path weird = dir.resolve("a;touch x");
        Files.writeString(weird, "data\n");
        ProcessRunner.Result r = ProcessRunner.of("cat", weird.toString())
            .directory(File.getInstance(dir.toString()))
            .run();
        assertEquals("data\n", r.output());
        assertFalse(Files.exists(dir.resolve("x")));
    }

    @Test
    public void aMissingProgramIsAnErrorNotAnException() {
        ProcessRunner.Result r = ProcessRunner.of("no-such-program-j").run();
        assertEquals(-1, r.exitValue());
        assertFalse(r.output().isEmpty());
    }

    @Test
    public void aTimeoutKillsTheProgram() {
        long start = System.nanoTime();
        ProcessRunner.Result r = ProcessRunner.of("sleep", "20").timeout(Duration.ofMillis(200)).run();
        assertEquals(-1, r.exitValue());
        assertTrue(System.nanoTime() - start < 10_000_000_000L);
    }

    @Test
    public void which() {
        assertNotNull(ProcessRunner.which("sh"));
        assertNull(ProcessRunner.which("no-such-program-j"));
    }
}
