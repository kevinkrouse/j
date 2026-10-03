/*
 * ExtensionsTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The registry and its discovery.
 *
 * <p>The point of most of these is that core can call into the SPI
 * unconditionally: with nothing installed every accessor still returns
 * something safe to call, which is what lets the call sites drop their "is it
 * installed yet" guards.
 */
public class ExtensionsTest {
    private Path dir;

    @Before
    public void reset() {
        Extensions.shutdown();
        Extensions.setDisabled(false);
    }

    @After
    public void cleanUp() throws IOException {
        Extensions.shutdown();
        Extensions.setDisabled(false);
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }

    // ---- nothing installed -------------------------------------------------

    @Test
    public void accessorsAreNeverNull() {
        assertSame(EditorHooks.NONE, Extensions.hooks());
        assertSame(KeyMapProvider.NONE, Extensions.keyMaps());
        assertSame(LanguageClient.NONE, Extensions.languageClient());
        assertFalse(Extensions.languageClient().isAvailable());
    }

    @Test
    public void noOpHooksDoNothingQuietly() {
        EditorHooks hooks = Extensions.hooks();
        hooks.eventHandled();
        hooks.bufferActivated(null);
        hooks.openFile(null);
        hooks.afterSave(null);
        hooks.modeCreated("Java");
        hooks.invoke("some-hook", "an argument");
    }

    @Test
    public void noKeyMapProviderMeansCoreFallsBack() {
        // null is "nothing to say", which is what lets AbstractMode and KeyMap
        // fall through to a key map file and then to their own defaults.
        assertNull(Extensions.keyMaps().getGlobalKeyMap());
        assertNull(Extensions.keyMaps().getKeyMapForMode("Lisp"));
    }

    @Test
    public void defaultSessionIsUnreadyAndRefusesToEvaluate() {
        Session session = Extensions.session();
        assertEquals(LanguageClient.DEFAULT_SESSION, session.getKey());
        assertFalse(session.isReady());
        try {
            session.evalSync(EvalRequest.of("(+ 1 2)"));
            fail("expected an EvalException when no client is installed");
        }
        catch (EvalException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("extension"));
        }
    }

    @Test
    public void asyncEvalReportsTheFailureRatherThanThrowing() {
        EvalResult[] seen = new EvalResult[1];
        Extensions.session().eval(EvalRequest.of("(+ 1 2)"), r -> seen[0] = r);
        assertTrue(seen[0].isError());
    }

    // ---- discovery ---------------------------------------------------------

    @Test
    public void discoversAnExtensionFromAJar() throws IOException {
        dir = Files.createTempDirectory("j-extensions-test");
        writeExtensionJar(dir.resolve("fake.jar"), FakeExtension.class);

        Extensions.loadFrom(dir, Collections.<String>emptySet());

        assertEquals(Collections.singletonList("fake"), Extensions.loadedNames());
        assertSame(FakeExtension.HOOKS, Extensions.hooks());
    }

    @Test
    public void honoursTheDisabledList() throws IOException {
        dir = Files.createTempDirectory("j-extensions-test");
        writeExtensionJar(dir.resolve("fake.jar"), FakeExtension.class);

        Set<String> disabled = new HashSet<String>();
        disabled.add("fake");
        Extensions.loadFrom(dir, disabled);

        assertTrue(Extensions.loadedNames().isEmpty());
        assertSame(EditorHooks.NONE, Extensions.hooks());
    }

    @Test
    public void aThrowingExtensionIsIsolated() throws IOException {
        dir = Files.createTempDirectory("j-extensions-test");
        writeExtensionJar(
            dir.resolve("broken.jar"),
            BrokenExtension.class,
            FakeExtension.class
        );

        Extensions.loadFrom(dir, Collections.<String>emptySet());

        // The good one still loaded. One bad jar must not stop j from starting.
        assertEquals(Collections.singletonList("fake"), Extensions.loadedNames());
    }

    @Test
    public void anEmptyDirectoryIsNotAnError() throws IOException {
        dir = Files.createTempDirectory("j-extensions-test");
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        assertTrue(Extensions.loadedNames().isEmpty());
    }

    @Test
    public void aJarWithNoServiceFileIsIgnored() throws IOException {
        dir = Files.createTempDirectory("j-extensions-test");
        try (JarOutputStream out = new JarOutputStream(
            Files.newOutputStream(dir.resolve("plain.jar"))
        )) {
            out.putNextEntry(new JarEntry("nothing.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        assertTrue(Extensions.loadedNames().isEmpty());
    }

    @Test
    public void shutdownRestoresTheNoOpRegistry() throws IOException {
        dir = Files.createTempDirectory("j-extensions-test");
        writeExtensionJar(dir.resolve("fake.jar"), FakeExtension.class);
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        assertSame(FakeExtension.HOOKS, Extensions.hooks());

        Extensions.shutdown();

        assertSame(EditorHooks.NONE, Extensions.hooks());
        assertSame(LanguageClient.NONE, Extensions.languageClient());
        assertTrue(Extensions.loadedNames().isEmpty());
        assertTrue(FakeExtension.shutdownCalled);
    }

    /**
     * Build a jar holding the given extension classes and the service file
     * that declares them. The classes themselves are copied out of the test
     * class path, so no compiler is needed at test time.
     */
    private static void writeExtensionJar(Path jar, Class<?>... extensions)
        throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            StringBuilder services = new StringBuilder();
            for (Class<?> extension : extensions) {
                services.append(extension.getName()).append('\n');
                copyClass(out, extension);
                for (Class<?> nested : extension.getDeclaredClasses())
                    copyClass(out, nested);
            }
            out.putNextEntry(
                new JarEntry(
                    "META-INF/services/org.armedbear.j.extension.Extension"
                )
            );
            out.write(services.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void copyClass(JarOutputStream out, Class<?> c) throws IOException {
        String path = c.getName().replace('.', '/').concat(".class");
        try (InputStream in = c.getClassLoader().getResourceAsStream(path)) {
            if (in == null)
                throw new IOException("cannot read " + path);
            out.putNextEntry(new JarEntry(path));
            copy(in, out);
            out.closeEntry();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n > 0; n = in.read(buffer))
            out.write(buffer, 0, n);
    }
}
