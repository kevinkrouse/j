/*
 * AtomicFiles.java
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.armedbear.j.File;

/** Files written whole or not at all. */
public final class AtomicFiles {
    private AtomicFiles() {}

    /**
     * Writes bytes to file, whole or not at all: written aside in its
     * directory, made first, then moved over it, atomically where the file
     * system can; what is left aside removed if it isn't moved. Readable by
     * its owner alone if owner, where the file system has permissions.
     */
    public static void write(Path file, byte[] bytes, boolean owner) throws IOException {
        final Path temp = aside(file, owner);
        try {
            Files.write(temp, bytes);
            moveIntoPlace(temp, file);
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * A {@link java.io.FileOutputStream} stand-in for j's {@link File}, to
     * be moved over it on close. Readable by its owner alone if owner,
     * where the file system has permissions.
     */
    public static OutputStream newOutputStream(File file, boolean owner) throws IOException {
        return new FileOutputStream(file.toPath(), owner);
    }

    public static OutputStream newOutputStream(Path path, boolean owner) throws IOException {
        return new FileOutputStream(path, owner);
    }

    /**
     * A {@link java.io.FileWriter} stand-in for j's {@link File}, to be
     * moved over it on close. Readable by its owner alone if owner, where
     * the file system has permissions.
     */
    public static Writer newWriter(File file, boolean owner) throws IOException {
        return new FileWriter(file.toPath(), owner);
    }

    public static Writer newWriter(Path path, boolean owner) throws IOException {
        return new FileWriter(path, owner);
    }

    /**
     * A {@link java.io.FileWriter} stand-in that writes to a temp file in the
     * target's own directory and moves it over the target on
     * {@link #close()}, whole or not at all. Use it where a streaming writer
     * fits the try-with-resources pattern but the bytes must not land in the
     * target until the write is complete. The target is untouched until
     * close; a close that fails leaves the old file in place and the temp
     * removed.
     */
    public static class FileWriter extends Writer {
        private final Path file;
        private final Path temp;
        private final java.io.FileWriter writer;
        private boolean closed;

        /**
         * Opens the aside file for file, to be moved over it on close.
         * Readable by its owner alone if owner, where the file system has
         * permissions.
         */
        public FileWriter(Path file, boolean owner) throws IOException {
            this.file = file.toAbsolutePath();
            this.temp = aside(this.file, owner);
            this.writer = new java.io.FileWriter(temp.toFile(), StandardCharsets.UTF_8);
        }

        @Override
        public void write(int c) throws IOException {
            writer.write(c);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            writer.write(cbuf, off, len);
        }

        @Override
        public void flush() throws IOException {
            writer.flush();
        }

        /** Closes the aside file and moves it over the target. */
        @Override
        public void close() throws IOException {
            if (closed)
                return;
            closed = true;
            try {
                writer.close();
                moveIntoPlace(temp, file);
            }
            finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * A {@link java.io.FileOutputStream} stand-in that writes to a temp file
     * in the target's own directory and moves it over the target on
     * {@link #close()}, whole or not at all. The bytes-only counterpart of
     * {@link FileWriter}, for callers that hold an {@link OutputStream}.
     */
    public static class FileOutputStream extends OutputStream {
        private final Path file;
        private final Path temp;
        private final java.io.FileOutputStream out;
        private boolean closed;

        /**
         * Opens the aside file for file, to be moved over it on close.
         * Readable by its owner alone if owner, where the file system has
         * permissions.
         */
        public FileOutputStream(Path file, boolean owner) throws IOException {
            this.file = file.toAbsolutePath();
            this.temp = aside(this.file, owner);
            this.out = new java.io.FileOutputStream(temp.toFile());
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        /** Closes the aside file and moves it over the target. */
        @Override
        public void close() throws IOException {
            if (closed)
                return;
            closed = true;
            try {
                out.close();
                moveIntoPlace(temp, file);
            }
            finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** The aside file in file's own directory, made first if the directory is missing. */
    private static Path aside(Path file, boolean owner) throws IOException {
        final Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        final String prefix = file.getFileName().toString();
        try {
            return owner
                    ? Files.createTempFile(
                        dir,
                        prefix,
                        ".new",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, prefix, ".new");
        }
        catch (UnsupportedOperationException e) {
            return Files.createTempFile(dir, prefix, ".new");
        }
    }

    /** Moves the aside file over file, atomically where the file system can. */
    private static void moveIntoPlace(Path temp, Path file) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
