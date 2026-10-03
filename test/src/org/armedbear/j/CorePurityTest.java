/*
 * CorePurityTest.java
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

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Core carries no ABCL.
 *
 * <p>The executable statement of the goal. A raw byte search rather than a
 * classpath check, because it catches both kinds of dependency at once:
 * constant-pool type references, which a compiler would need, and reflective
 * {@code Class.forName("org.armedbear.lisp...")} strings, which it would not.
 *
 * <p>Note the slash form. The dotted spelling appears legitimately in
 * {@link org.armedbear.j.mode.lisp.LispShellBuffer}, which builds a command
 * line for an <em>external</em> Lisp process -- that is data about a
 * subprocess, not a class core loads.
 */
public class CorePurityTest {
    private static final String ABCL = "org/armedbear/lisp";

    /** Where core's compiled classes are; build/classes when run from bb. */
    private static Path classesDirectory() throws URISyntaxException {
        java.security.CodeSource source =
            Editor.class.getProtectionDomain().getCodeSource();
        assertNotNull(source, "cannot locate core's own classes");
        return Paths.get(source.getLocation().toURI());
    }

    @Test
    public void noCoreClassReferencesAbcl() throws IOException, URISyntaxException {
        Path root = classesDirectory();
        assertTrue(Files.isDirectory(root), "expected a directory of classes, got " + root);
        List<String> tainted = new ArrayList<String>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root.resolve("org").resolve("armedbear"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.toString().endsWith(".class"))
                    continue;
                ++scanned;
                String bytes = new String(
                    Files.readAllBytes(file),
                    StandardCharsets.ISO_8859_1
                );
                if (bytes.contains(ABCL))
                    tainted.add(root.relativize(file).toString());
            }
        }
        assertTrue(scanned > 0, "core has no classes to scan; wrong directory?");
        assertEquals(0, tainted.size(), "classes referencing ABCL: " + tainted);
    }

    @Test
    public void noLispResourceRidesAlong() throws IOException, URISyntaxException {
        // j.lisp and emacs.lisp belong to the abcl extension. If one reappears
        // in core's output it would end up inside j.jar, and ABCL would load
        // whichever copy the class path happened to reach first.
        Path root = classesDirectory();
        List<String> lisp = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files::iterator)
                if (file.toString().endsWith(".lisp"))
                    lisp.add(root.relativize(file).toString());
        }
        assertEquals(0, lisp.size(), "Lisp resources in core: " + lisp);
    }

    @Test
    public void abclsOwnResourcesAreNotReachableFromCore() {
        // The same check from the other side: whatever is on the class path,
        // core itself must not be able to resolve ABCL's bootstrap files.
        assertNull(Editor.class.getResource("/org/armedbear/lisp/j.lisp"));
        assertNull(Editor.class.getResource("/org/armedbear/lisp/emacs.lisp"));
    }

    @Test
    public void coreCannotLoadAnAbclClass() {
        try {
            Class.forName(
                "org.armedbear.lisp.Interpreter",
                false,
                Editor.class.getClassLoader()
            );
            org.junit.jupiter.api.Assertions.fail("ABCL is on core's class path");
        }
        catch (ClassNotFoundException expected) {}
    }
}
