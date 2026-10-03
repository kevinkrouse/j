/*
 * JavadocLinksTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.BiConsumer;
import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** followLink on Javadoc's references, to the class's source and member. */
public class JavadocLinksTest {
    private EditorHarness h;
    private Path source;
    private String className;
    private BiConsumer<Editor, Buffer> switcher;

    @BeforeEach
    public void setUp() throws Exception {
        className = "JLink" + System.nanoTime();
        source = Paths.get(System.getProperty("java.io.tmpdir"), className + ".java");
        Files.write(
            source,
            ("public class " + className + "\n{\n"
                + "    public " + className + "() {}\n"
                + "    public void bar() {}\n"
                + "    public void bar(int n) {}\n"
                + "    public void bar(final String s, java.util.List<String> l) {}\n"
                + "    public void baz(String... args) {}\n"
                + "}\n").getBytes("UTF-8")
        );
        // No frame to activate a buffer in: load it and show it directly.
        switcher = FollowLink.switcher;
        FollowLink.switcher = (editor, buffer) -> {
            buffer.setMode(JavaMode.getMode());
            buffer.setFormatter(JavaMode.getMode().getFormatter(buffer));
            try (java.io.InputStream in = buffer.getFile().getInputStream()) {
                buffer.lockWrite();
                try {
                    buffer.load(in, null);
                }
                finally {
                    buffer.unlockWrite();
                }
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
            buffer.renumber();
            editor.setBufferDirectly(buffer);
            editor.setDot(buffer.getFirstLine(), 0);
        };
    }

    @AfterEach
    public void tearDown() throws Exception {
        FollowLink.switcher = switcher;
        if (h != null)
            h.close();
        Files.deleteIfExists(source);
    }

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(JavaMode.getMode());
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    private TextLink linkAt(int line, int offset) {
        h.cursor(line, offset);
        return h.buffer().getMode().getLinkAt(h.editor(), h.editor().getDot());
    }

    private String path() {
        return File.getInstance(source.toString()).canonicalPath();
    }

    @Test
    public void referencesResolveToTheClasssSource() {
        final String c = className;
        on(
            "/**\n * {@link " + c + "#bar(int)} and {@linkplain #local here}\n"
                + " * @see " + c + "\n * @throws NoSuchThing never\n */\nvoid local() {}\n"
        );
        final TextLink bar = linkAt(1, 10);
        assertEquals(path() + "#bar(int)", bar.getTarget());
        assertEquals(10, bar.getBegin());
        assertEquals(10 + c.length() + 9, bar.getEnd());
        assertEquals("#local", linkAt(1, c.length() + 38).getTarget());
        assertEquals(path() + "#" + c, linkAt(2, 8).getTarget());
        final TextLink missing = linkAt(3, 12);
        assertNull(missing.getTarget());
        assertTrue(missing.getProblem().contains("NoSuchThing"), missing.getProblem());
        assertNull(linkAt(5, 2));
    }

    @Test
    public void aMemberIsATagByItsParameters() {
        on("// {@link " + className + "#bar(String, List)}\n").cursor(0, 12);
        FollowLink.followLink();
        assertEquals(
            source.getFileName().toString(),
            h.editor().getBuffer().getFile().getName()
        );
        assertEquals(5, h.editor().getDotLineNumber());
        final List<LocalTag> tags = h.editor().getBuffer().getTags(true);
        LocalTag bar = null;
        LocalTag barInt = null;
        LocalTag baz = null;
        for (LocalTag t : tags) {
            if (t.getLine().lineNumber() == 3)
                bar = t;
            if (t.getLine().lineNumber() == 4)
                barInt = t;
            if (t.getLine().lineNumber() == 6)
                baz = t;
        }
        assertTrue(bar.isNamedBy("bar"));
        assertTrue(bar.isNamedBy("bar()"));
        assertTrue(!bar.isNamedBy("bar(int)"));
        assertTrue(barInt.isNamedBy("bar(int)"));
        assertTrue(barInt.isNamedBy("bar(int n)"));
        assertTrue(baz.isNamedBy("baz(String...)"));
        assertTrue(baz.isNamedBy("baz(java.lang.String[])"));
    }

    @Test
    public void aClassAloneGoesToItsDeclaration() {
        on("// @see " + className + "\n").cursor(0, 9);
        FollowLink.followLink();
        assertEquals(0, h.editor().getDotLineNumber());
    }
}
