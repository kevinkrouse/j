/*
 * MermaidBridgeTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.armedbear.j.Buffer;
import org.armedbear.j.Command;
import org.armedbear.j.CommandTable;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.VirtualText;
import org.armedbear.j.mode.markdown.MarkdownFences;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Clojure bridge, end to end: the mermaid extension's jar is loaded,
 * its FenceHandler is registered, and a ```mermaid fence in a Markdown
 * buffer reaches the handler with its body. Proves the seam core exposes
 * (registerFenceHandler, the fence scan, the dispatch) against a real
 * Clojure gen-class, with no renderer involved.
 *
 * <p>The handler records what it received in statics in its own loader;
 * read back through {@link Extensions#findClass} so the test sees the
 * same class the registered instance uses, whichever loader defined it.
 */
public class MermaidBridgeTest {
    private EditorHarness h;

    // The handler's statics, read through the loader that owns the
    // registered instance.
    private static final String HANDLER = "org.armedbear.j.mermaid.MermaidHandler";

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
        Extensions.shutdown();
    }

    // The built mermaid extension directory, where `bb extensions` wrote
    // j-mermaid.jar and the Clojure jars beside it.
    private static Path mermaidExtensionDir() {
        return Paths.get("build", "lib", "extensions", "mermaid").toAbsolutePath();
    }

    private static Object callStatic(String cls, String method) throws Exception {
        Class<?> c = Extensions.findClass(cls);
        assertNotNull(c, "class " + cls + " not found in any extension loader");
        Method m = c.getMethod(method);
        return m.invoke(null);
    }

    @Test
    public void theExtensionLoadsAndRegistersAHandler() throws Exception {
        Path dir = mermaidExtensionDir();
        assertTrue(Files.isDirectory(dir), "run `bb extensions` first: " + dir);
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        assertTrue(Extensions.loadedNames().contains("mermaid"), "mermaid loaded");
        assertNotNull(Extensions.fenceHandler("mermaid"), "a handler claims mermaid");
        assertEquals(null, Extensions.fenceHandler("python"), "no handler for python");
    }

    @Test
    public void aMermaidFenceReachesTheHandlerWithItsBody() throws Exception {
        Path dir = mermaidExtensionDir();
        assertTrue(Files.isDirectory(dir), "run `bb extensions` first: " + dir);
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        callStatic(HANDLER, "reset");

        final String text = "intro\n" + "```mermaid\n" + "graph TD;\n" + "  A-->B;\n" + "```\n" + "outro\n";
        h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(12);
        MarkdownFences.onActivate(h.editor());

        Object count = callStatic(HANDLER, "getRenderCount");
        assertEquals(1, ((Number) count).intValue(), "the fence was dispatched once");
        Object body = callStatic(HANDLER, "getLastBody");
        assertEquals("graph TD;\n  A-->B;", body, "the body between the fences");
    }

    @Test
    public void aMermaidFenceInsideACommentIsNotDispatched() throws Exception {
        Path dir = mermaidExtensionDir();
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        callStatic(HANDLER, "reset");

        // The diagram is commented out: the formatter treats the whole
        // region as a comment, so the fence inside it is not a fence.
        final String text =
                "intro\n" + "<!--\n" + "```mermaid\n" + "graph TD;\n" + "  A-->B;\n" + "```\n" + "-->\n" + "outro\n";
        h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(12);
        MarkdownFences.onActivate(h.editor());

        Object count = callStatic(HANDLER, "getRenderCount");
        assertEquals(0, ((Number) count).intValue(), "a commented-out fence is not drawn");
    }

    @Test
    public void mermaidPingIsRegistered() throws Exception {
        Path dir = mermaidExtensionDir();
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        Command cmd = CommandTable.getCommand("mermaidPing");
        assertNotNull(cmd, "mermaidPing registered");
        assertTrue(cmd.takesNoArgument(), "mermaidPing takes no argument");
    }

    @Test
    public void aMissingRendererShowsAPlaceholderNotACrash() throws Exception {
        Path dir = mermaidExtensionDir();
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        callStatic(HANDLER, "reset");

        // A renderer kind that resolves to nothing: the render fails, and
        // the fence must show a placeholder rather than a blank or a crash.
        org.armedbear.j.Editor.preferences().setProperty("mermaid.renderer", "bogus-kind");
        try {
            final String text = "intro\n```mermaid\nflowchart LR; A-->B\n```\noutro\n";
            h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(12);
            MarkdownFences.onActivate(h.editor());

            // The render is async; wait for the REPLACE to land.
            final Buffer buffer = h.editor().getBuffer();
            VirtualText replace = null;
            for (int i = 0; i < 100 && replace == null; i++) {
                Thread.sleep(50);
                java.util.List<VirtualText> vt = buffer.getVirtualText().get("mermaid");
                if (!vt.isEmpty())
                    replace = vt.get(0);
            }
            assertNotNull(replace, "a REPLACE landed for the failed render");
            assertEquals(VirtualText.Placement.REPLACE, replace.placement());
            assertTrue(
                replace.painter().getClass().getName().contains("PlaceholderPainter"),
                "the painter is the placeholder, not a diagram");
            // The painter's height is the fence's own height, so collapsing
            // and revealing the diagram moves nothing (the scroll-jump fix).
            int sourceHeight = 0;
            for (Line l = replace.line(); l != null; l = l.next()) {
                sourceHeight += l.getHeight();
                if (l == replace.last())
                    break;
            }
            assertEquals(sourceHeight, replace.painter().getHeight(), "the painter fills exactly the fence's height");
        }
        finally {
            org.armedbear.j.Editor.preferences().setProperty("mermaid.renderer", "auto");
        }
    }

    @Test
    public void toggleMermaidHidesAndShowsTheDiagram() throws Exception {
        Path dir = mermaidExtensionDir();
        Extensions.loadFrom(dir, Collections.<String>emptySet());
        callStatic(HANDLER, "reset");
        // A renderer kind that resolves to nothing: the REPLACE lands as a
        // placeholder, so the toggle is testable without a real renderer.
        org.armedbear.j.Editor.preferences().setProperty("mermaid.renderer", "bogus-kind");
        try {
            final String text = "intro\n```mermaid\nflowchart LR; A-->B\n```\noutro\n";
            h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(12);
            MarkdownFences.onActivate(h.editor());
            final Buffer buffer = h.editor().getBuffer();

            // On by default: the placeholder REPLACE lands.
            assertTrue(waitForVt(buffer, true), "a REPLACE landed while on");

            // Toggle off: the source shows, the REPLACE is cleared.
            callStatic(HANDLER, "toggleMermaid");
            assertTrue(waitForVt(buffer, false), "the REPLACE cleared when toggled off");

            // Toggle on: the REPLACE returns.
            callStatic(HANDLER, "toggleMermaid");
            assertTrue(waitForVt(buffer, true), "the REPLACE returned when toggled on");
        }
        finally {
            org.armedbear.j.Editor.preferences().setProperty("mermaid.renderer", "auto");
        }
    }

    // Poll the buffer's mermaid REPLACE list until it is non-empty (want)
    // or empty (not want), or the timeout passes.
    private boolean waitForVt(Buffer buffer, boolean want) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            boolean has = !buffer.getVirtualText().get("mermaid").isEmpty();
            if (has == want)
                return true;
            Thread.sleep(50);
        }
        return false;
    }
}
