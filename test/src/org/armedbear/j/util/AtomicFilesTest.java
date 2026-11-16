/*
 * AtomicFilesTest.java
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

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AtomicFilesTest {
    @TempDir
    Path tmp;

    @Test
    public void writesTheBytesWhole() throws Exception {
        Path file = tmp.resolve("thing");
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, false);
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    public void replacesWhatWasThere() throws Exception {
        Path file = tmp.resolve("thing");
        Files.write(file, "old and longer contents".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(file, "new".getBytes(StandardCharsets.UTF_8), false);
        assertEquals("new", Files.readString(file));
    }

    @Test
    public void leavesNothingAside() throws Exception {
        Path file = tmp.resolve("thing");
        AtomicFiles.write(file, "x".getBytes(StandardCharsets.UTF_8), false);
        try (Stream<Path> s = Files.list(tmp)) {
            long temps = s.filter(p -> p.getFileName().toString().endsWith(".new")).count();
            assertEquals(0, temps, "the temp is moved, not left behind");
        }
    }

    @Test
    public void makesTheDirectoryItNeeds() throws Exception {
        Path file = tmp.resolve("a/b/c/thing");
        AtomicFiles.write(file, "x".getBytes(StandardCharsets.UTF_8), false);
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    public void ownerKeepsItToItsOwnerAlone() throws Exception {
        Path file = tmp.resolve("thing");
        AtomicFiles.write(file, "x".getBytes(StandardCharsets.UTF_8), true);
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            return;
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    public void aWriteOverAFailedOneStillLeavesNoTemp() throws Exception {
        // A directory where the target name is, so the move can't land: the
        // aside must still be cleaned up rather than left to rot.
        Path dir = tmp.resolve("thing");
        Files.createDirectory(dir);
        try {
            AtomicFiles.write(dir, "x".getBytes(StandardCharsets.UTF_8), false);
            // Some file systems replace a directory with REPLACE_EXISTING; if
            // it did, there is nothing to check.
        }
        catch (java.io.IOException expected) {
            try (Stream<Path> s = Files.list(tmp)) {
                long temps = s.filter(p -> p.getFileName().toString().endsWith(".new")).count();
                assertEquals(0, temps, "a refused write removes its temp");
            }
        }
    }

    @Test
    public void anEmptyFileIsStillAFile() throws Exception {
        Path file = tmp.resolve("thing");
        AtomicFiles.write(file, new byte[0], false);
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, Files.size(file));
        assertFalse(Files.exists(tmp.resolve("thing.new")));
    }

    private long temps() throws Exception {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".new")).count();
        }
    }

    @Test
    public void fileWriterMovesOnClose() throws Exception {
        Path file = tmp.resolve("thing");
        try (Writer writer = new AtomicFiles.FileWriter(file, false)) {
            writer.write("hello writer");
        }
        assertEquals("hello writer", Files.readString(file));
        assertEquals(0, temps(), "the writer's temp is moved, not left behind");
    }

    @Test
    public void fileWriterTouchesNothingUntilClose() throws Exception {
        Path file = tmp.resolve("thing");
        Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.FileWriter writer = new AtomicFiles.FileWriter(file, false);
        writer.write("new and longer");
        // Still the old bytes: the move is close's, not the write's.
        assertEquals("old", Files.readString(file));
        writer.close();
        assertEquals("new and longer", Files.readString(file));
    }

    @Test
    public void fileWriterWritesUtf8() throws Exception {
        Path file = tmp.resolve("thing");
        try (Writer writer = new AtomicFiles.FileWriter(file, false)) {
            writer.write("naïve 日本語");
        }
        assertEquals("naïve 日本語", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void fileWriterOwnerKeepsItToItsOwnerAlone() throws Exception {
        Path file = tmp.resolve("thing");
        try (Writer writer = new AtomicFiles.FileWriter(file, true)) {
            writer.write("x");
        }
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            return;
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    public void fileWriterMakesTheDirectoryItNeeds() throws Exception {
        Path file = tmp.resolve("a/b/c/thing");
        try (Writer writer = new AtomicFiles.FileWriter(file, false)) {
            writer.write("x");
        }
        assertEquals("x", Files.readString(file));
    }

    @Test
    public void aFileWriterCloseThatCannotLandLeavesTheOldFileAndNoTemp() throws Exception {
        // The target name is a directory, so the move can't land: the old
        // file beside it must survive and the aside must be cleaned up.
        Path dir = tmp.resolve("thing");
        Files.createDirectory(dir);
        AtomicFiles.FileWriter writer = new AtomicFiles.FileWriter(dir, false);
        writer.write("x");
        try {
            writer.close();
            // Some file systems replace a directory with REPLACE_EXISTING; if
            // it did, there is nothing to check.
        }
        catch (IOException expected) {
            assertEquals(0, temps(), "a refused close removes its temp");
        }
    }

    @Test
    public void fileWriterCloseIsIdempotent() throws Exception {
        Path file = tmp.resolve("thing");
        AtomicFiles.FileWriter writer = new AtomicFiles.FileWriter(file, false);
        writer.write("once");
        writer.close();
        writer.close();
        assertEquals("once", Files.readString(file));
        assertEquals(0, temps());
    }

    @Test
    public void fileOutputStreamMovesOnClose() throws Exception {
        Path file = tmp.resolve("thing");
        try (OutputStream out = new AtomicFiles.FileOutputStream(file, false)) {
            out.write("hello stream".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("hello stream", Files.readString(file));
        assertEquals(0, temps(), "the stream's temp is moved, not left behind");
    }

    @Test
    public void fileOutputStreamTouchesNothingUntilClose() throws Exception {
        Path file = tmp.resolve("thing");
        Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.FileOutputStream out = new AtomicFiles.FileOutputStream(file, false);
        out.write('n');
        out.write("ew".getBytes(StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(file));
        out.close();
        assertEquals("new", Files.readString(file));
    }

    @Test
    public void fileOutputStreamOwnerKeepsItToItsOwnerAlone() throws Exception {
        Path file = tmp.resolve("thing");
        try (OutputStream out = new AtomicFiles.FileOutputStream(file, true)) {
            out.write('x');
        }
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            return;
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    public void fileOutputStreamEmptyIsStillAFile() throws Exception {
        Path file = tmp.resolve("thing");
        try (OutputStream out = new AtomicFiles.FileOutputStream(file, false)) {
            // Nothing written: close still lands an empty file.
        }
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, Files.size(file));
        assertEquals(0, temps());
    }

    @Test
    public void aFileOutputStreamCloseThatCannotLandLeavesNoTemp() throws Exception {
        Path dir = tmp.resolve("thing");
        Files.createDirectory(dir);
        AtomicFiles.FileOutputStream out = new AtomicFiles.FileOutputStream(dir, false);
        out.write('x');
        try {
            out.close();
        }
        catch (IOException expected) {
            assertEquals(0, temps(), "a refused close removes its temp");
        }
    }

    @Test
    public void fileOutputStreamCloseIsIdempotent() throws Exception {
        Path file = tmp.resolve("thing");
        AtomicFiles.FileOutputStream out = new AtomicFiles.FileOutputStream(file, false);
        out.write("once".getBytes(StandardCharsets.UTF_8));
        out.close();
        out.close();
        assertEquals("once", Files.readString(file));
        assertEquals(0, temps());
    }
}
