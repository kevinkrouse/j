/*
 * FilePermissionsTest.java
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FilePermissionsTest {
    @TempDir
    Path dir;

    @Test
    public void roundTrip() throws Exception {
        assumeTrue(Platform.isPlatformUnix());
        Path p = Files.writeString(dir.resolve("f"), "");
        File file = File.getInstance(p.toString());
        for (int mode : new int[] { 0644, 0755, 0600, 0421 }) {
            file.setPermissions(mode);
            assertEquals(mode, file.getPermissions(), Integer.toOctalString(mode));
        }
    }
}
