/*
 * ExtensionLoadingTest.java
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

package org.armedbear.j.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.armedbear.j.Command;
import org.armedbear.j.CommandTable;
import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.Mode;
import org.armedbear.j.ModeList;
import org.armedbear.j.ModeListEntry;
import org.armedbear.j.vcs.VcsBackends;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A real extension, loaded from a real jar.
 *
 * <p>This lives in core's suite rather than the extension's on purpose: it has
 * to run with core's own class path, where ABCL is absent, because that
 * arrangement -- core in one loader, the extension and its libraries in
 * another -- is exactly what it is checking.
 *
 * <p>It needs {@code build/lib/extensions} to have been built, which
 * {@code bb test} alone does not do; it is skipped then, and
 * {@code bb test-extensions} builds the tree first so it always runs there.
 */
public class ExtensionLoadingTest {
    private static Path extensionsDirectory;

    @BeforeAll
    public static void findTheBuiltExtensions() {
        // build/classes -> build -> build/lib/extensions, the same derivation
        // Extensions itself makes from an installed j.jar.
        Path dir = null;
        try {
            java.security.CodeSource source =
                CommandTable.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path root = Paths.get(source.getLocation().toURI()).getParent();
                if (root != null)
                    dir = root.resolve("lib").resolve("extensions");
            }
        }
        catch (Exception e) {
            dir = null;
        }
        if (dir == null || !Files.isDirectory(dir))
            System.out.println(
                "ExtensionLoadingTest: skipped, no extensions "
                    + "built (run `bb test-extensions`)"
            );
        extensionsDirectory = dir;
    }

    private static boolean loaded;

    /**
     * Loaded once for the whole class, not per test: every load builds a fresh
     * URLClassLoader over the extension, and a second copy of ABCL in a second
     * loader is not something to go looking for. An interpreter cannot be shut
     * down again either, so there is no teardown to write.
     */
    @BeforeEach
    public void load() {
        assumeTrue(
            extensionsDirectory != null
                && Files.isDirectory(extensionsDirectory)
        );
        if (!loaded) {
            System.setProperty(
                Extensions.DIRECTORY_PROPERTY,
                extensionsDirectory.toString()
            );
            try {
                Extensions.load();
            }
            finally {
                System.clearProperty(Extensions.DIRECTORY_PROPERTY);
            }
            loaded = true;
        }
    }

    @Test
    public void theExtensionIsFound() {
        assertTrue(Extensions.loadedNames().contains("abcl"), "loaded: " + Extensions.loadedNames());
    }

    @Test
    public void theBundledExtensionsProvideWhatCoreMoved() {
        assertTrue(
            Extensions.loadedNames().containsAll(List.of("abcl", "mail", "vcs-legacy")),
            "loaded: " + Extensions.loadedNames()
        );
        ModeList modes = Editor.getModeList();
        for (String[] pair : new String[][] {
            { "a.v", "Verilog" },
            { "a.vhd", "VHDL" },
            { "a.asm", "Assembly" },
            { "configure.ac", "Autoconf" },
            { "a.tcl", "Tcl" },
            { "a.m", "Objective C" },
            { "a.scm", "Scheme" }
        }) {
            Mode mode = modes.getModeForFileName(pair[0]);
            assertNotNull(mode, pair[0]);
            assertEquals(pair[1], mode.getDisplayName(), pair[0]);
        }
        for (ModeListEntry entry : modes)
            assertNotNull(entry.getMode(true), entry.getDisplayName());
        assertNotNull(VcsBackends.get(Constants.VC_CVS));
        assertNotNull(VcsBackends.get(Constants.VC_P4));
        assertNotNull(VcsBackends.get(Constants.VC_DARCS));
        assertNotNull(CommandTable.getCommand("compose"));
        assertNotNull(CommandTable.getCommand("cvsDiff"));
    }

    @Test
    public void itReplacesTheNoOpProviders() {
        assertNotSame(EditorHooks.NONE, Extensions.hooks());
        assertNotSame(KeyMapProvider.NONE, Extensions.keyMaps());
        assertNotSame(LanguageClient.NONE, Extensions.languageClient());
        assertTrue(Extensions.languageClient().isAvailable());
    }

    @Test
    public void aCommandArrivesCarryingItsOwnClass() {
        Command jlisp = CommandTable.getCommand("jlisp");
        assertNotNull(jlisp, "jlisp was not registered");
        // The whole reason registerCommand takes a Class and not a name:
        // Class.forName on core's loader could never find this.
        assertNotNull(jlisp.getDeclaringClass());
        assertNotSame(
            CommandTable.class.getClassLoader(),
            jlisp.getDeclaringClass().getClassLoader()
        );
    }

    @Test
    public void theRuntimeSleepsUntilSomethingEvaluatesAndThenWakes()
        throws EvalException {
        // Both halves in one test on purpose: test methods run in no set
        // order, and this is the only test here that may boot a runtime.
        assertFalse(Extensions.session().isReady(), "loading an extension must not start a runtime");
        // Everything core calls on the hot paths, none of which may wake it.
        Extensions.hooks().eventHandled();
        Extensions.hooks().bufferActivated(null);
        Extensions.hooks().modeCreated("Java");
        Extensions.hooks().invoke("key-pressed-hook", "Ctrl X");
        assertNull(Extensions.keyMaps().getGlobalKeyMap());
        assertNull(Extensions.keyMaps().getKeyMapForMode("Java"));
        assertFalse(Extensions.session().hasFeature("slime"));
        assertFalse(Extensions.session().isReady(), "something woke the runtime");

        EvalResult result =
            Extensions.session().evalSync(EvalRequest.of("(+ 1 2)"));
        assertFalse(result.isError(), result.getError());
        assertTrue(result.getValue().contains("3"), result.getValue());
        assertTrue(Extensions.session().isReady());
    }

    @Test
    public void classesInTheExtensionResolveBackIntoCore() throws Exception {
        // The split-package trap. BufferStream is declared in org.armedbear.j
        // but lives in the extension's loader, so it is a *different* runtime
        // package from core's: any package-private member it touches compiles
        // fine and throws IllegalAccessError here.
        ClassLoader loader =
            CommandTable.getCommand("jlisp").getDeclaringClass().getClassLoader();
        Class<?> bufferStream =
            Class.forName("org.armedbear.j.BufferStream", true, loader);
        assertSame(loader, bufferStream.getClassLoader());
        Constructor<?> constructor =
            bufferStream.getConstructor(org.armedbear.j.Buffer.class);
        assertNotNull(constructor);

        Class<?> lispAPI = Class.forName("org.armedbear.j.LispAPI", true, loader);
        assertSame(loader, lispAPI.getClassLoader());
    }

    @Test
    public void abclIsReachableOnlyFromTheExtension() throws Exception {
        ClassLoader loader =
            CommandTable.getCommand("jlisp").getDeclaringClass().getClassLoader();
        assertNotNull(Class.forName("org.armedbear.lisp.Interpreter", false, loader));
        try {
            Class.forName(
                "org.armedbear.lisp.Interpreter",
                false,
                CommandTable.class.getClassLoader()
            );
            org.junit.jupiter.api.Assertions.fail("ABCL leaked onto core's class path");
        }
        catch (ClassNotFoundException expected) {}
    }

}
