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
 * Core carries no ABCL, and doesn't reference what its extensions provide.
 *
 * <p>A byte search of core's classes, so it sees type references and the
 * dotted names Class.forName takes. Dotted org.armedbear.lisp is allowed:
 * {@link org.armedbear.j.mode.lisp.LispShellBuffer} names the main class of
 * an <em>external</em> Lisp process, not a class core loads.
 */
public class CorePurityTest {
    // As build.clj's check-core lists them.
    private static final List<String> EXTENSION_PACKAGES = extensionPackages();

    private static List<String> extensionPackages() {
        List<String> packages = new ArrayList<>();
        packages.add("org/armedbear/lisp");
        for (String p : List.of(
            "mail",
            "vcs/cvs",
            "vcs/p4",
            "vcs/darcs",
            "mode/asm",
            "mode/autoconf",
            "mode/verilog",
            "mode/vhdl",
            "mode/objc",
            "mode/tcl",
            "mode/scheme"
        )) {
            packages.add("org/armedbear/j/" + p + "/");
            packages.add("org.armedbear.j." + p.replace('/', '.') + ".");
        }
        return packages;
    }

    /** Where core's compiled classes are; build/classes when run from bb. */
    private static Path classesDirectory() throws URISyntaxException {
        java.security.CodeSource source =
            Editor.class.getProtectionDomain().getCodeSource();
        assertNotNull(source, "cannot locate core's own classes");
        return Paths.get(source.getLocation().toURI());
    }

    @Test
    public void noCoreClassReferencesAnExtension() throws IOException, URISyntaxException {
        Path root = classesDirectory();
        assertTrue(Files.isDirectory(root), "expected a directory of classes, got " + root);
        List<String> tainted = new ArrayList<>();
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
                for (String pkg : EXTENSION_PACKAGES) {
                    if (bytes.contains(pkg))
                        tainted.add(root.relativize(file) + " -> " + pkg);
                }
            }
        }
        assertTrue(scanned > 0, "core has no classes to scan; wrong directory?");
        assertEquals(0, tainted.size(), "classes referencing extensions: " + tainted);
    }

    @Test
    public void noLispResourceRidesAlong() throws IOException, URISyntaxException {
        // j.lisp and emacs.lisp belong to the abcl extension. If one reappears
        // in core's output it would end up inside j.jar, and ABCL would load
        // whichever copy the class path happened to reach first.
        Path root = classesDirectory();
        List<String> lisp = new ArrayList<>();
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
