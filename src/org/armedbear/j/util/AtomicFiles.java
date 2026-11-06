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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

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
        final Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        final String prefix = file.getFileName().toString();
        Path temp;
        try {
            temp = owner
                    ? Files.createTempFile(
                        dir,
                        prefix,
                        ".new",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, prefix, ".new");
        }
        catch (UnsupportedOperationException e) {
            temp = Files.createTempFile(dir, prefix, ".new");
        }
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }
}
