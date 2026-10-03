/*
 * MarkdownLinksTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.markdown.MarkdownTasks;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** followLink: what a link at the caret is, and where it goes. */
public class MarkdownLinksTest {
    private EditorHarness h;
    private final List<String> browsed = new ArrayList<String>();
    private Consumer<String> browser;
    private java.util.function.BiConsumer<Editor, Buffer> switcher;
    private final List<Path> files = new ArrayList<Path>();

    @Before
    public void setUp() {
        browser = FollowLink.browser;
        FollowLink.browser = browsed::add;
        // No frame to activate a buffer in: load it and show it directly.
        switcher = FollowLink.switcher;
        FollowLink.switcher = (editor, buffer) -> {
            buffer.setMode(MarkdownMode.getMode());
            buffer.setFormatter(MarkdownMode.getMode().getFormatter(buffer));
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

    @After
    public void tearDown() throws Exception {
        FollowLink.browser = browser;
        FollowLink.switcher = switcher;
        if (h != null)
            h.close();
        for (Path p : files)
            Files.deleteIfExists(p);
    }

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        Editor.setCurrentEditor(h.editor());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    private String linkAt(int line, int offset) {
        h.cursor(line, offset);
        final TextLink link = h.buffer().getMode().getLinkAt(h.editor(), h.editor().getDot());
        return link != null ? link.getTarget() : null;
    }

    @Test
    public void whatALinkPointsTo() {
        on(
            "see [the docs](docs.md \"Docs\") and ![a](p.png)\n"
                + "[full][r] [collapsed][] [shortcut] [not one]\n"
                + "<https://a.b/c> and https://x.y/z.\n"
                + "[a](<my notes.md>)\n"
                + "[r]: https://r.example\n"
                + "[collapsed]: c.md\n"
                + "[Shortcut]:   <s.md>\n"
        );
        assertEquals("docs.md", linkAt(0, 5));
        assertEquals("docs.md", linkAt(0, 4));
        assertNull(linkAt(0, 2));
        assertEquals("p.png", linkAt(0, 37));
        assertEquals("https://r.example", linkAt(1, 2));
        assertEquals("c.md", linkAt(1, 12));
        // Labels match whatever the case.
        assertEquals("s.md", linkAt(1, 26));
        assertNull(linkAt(1, 38));
        assertEquals("https://a.b/c", linkAt(2, 3));
        assertEquals("https://x.y/z", linkAt(2, 25));
        assertEquals("my notes.md", linkAt(3, 1));
        assertEquals("https://r.example", linkAt(4, 10));
    }

    @Test
    public void notInCode() {
        on("```\n[a](b.md)\n```\n");
        assertNull(linkAt(1, 1));
    }

    @Test
    public void anAnchorGoesToItsHeading() {
        on(
            "[go](#hello-world-2) [again](#notes-1)\n\n# Hello, World!\n"
                + "## Notes\n## Notes\n# Hello World 2\n"
        ).cursor(0, 1);
        FollowLink.followLink();
        assertEquals(5, h.editor().getDotLineNumber());
        h.cursor(0, 22);
        FollowLink.followLink();
        assertEquals(4, h.editor().getDotLineNumber());
    }

    @Test
    public void jumpBackReturns() {
        on("[go](#end)\n\n\n# End\n").cursor(0, 2);
        FollowLink.followLink();
        assertEquals(3, h.editor().getDotLineNumber());
        JumpList.jumpBack();
        assertEquals(0, h.editor().getDotLineNumber());
    }

    @Test
    public void aMissingAnchorSaysSo() {
        on("[go](#nowhere)\n# Here\n").cursor(0, 1);
        FollowLink.followLink();
        assertEquals(0, h.editor().getDotLineNumber());
        assertTrue(h.status(), h.status().contains("#nowhere"));
    }

    private String file(String text) throws Exception {
        final Path dir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
        final Path p = dir.resolve("j-link-" + System.nanoTime() + ".md");
        Files.write(p, text.getBytes("UTF-8"));
        files.add(p);
        return p.getFileName().toString();
    }

    @Test
    public void aPathOpensTheFileAtItsHeadingOrLine() throws Exception {
        final String name = file("# One\n\n## Two\nthree\n");
        on("[a](" + name + "#two) [b](" + name + "#L4)\n").cursor(0, 1);
        final Buffer here = h.buffer();
        FollowLink.followLink();
        assertEquals(name, h.editor().getBuffer().getFile().getName());
        assertEquals(2, h.editor().getDotLineNumber());
        h.editor().setBufferDirectly(here);
        h.cursor(0, 2 + name.length() + 8);
        FollowLink.followLink();
        assertEquals(3, h.editor().getDotLineNumber());
    }

    @Test
    public void aMissingFileSaysSo() {
        on("[a](no-such-file.md)\n").cursor(0, 1);
        FollowLink.followLink();
        assertTrue(h.status(), h.status().contains("No such file"));
    }

    @Test
    public void aUrlGoesToTheBrowser() {
        on("[site](https://example.com/x) mailto:me@example.com\n").cursor(0, 2);
        FollowLink.followLink();
        h.cursor(0, 35);
        FollowLink.followLink();
        assertEquals("[https://example.com/x, mailto:me@example.com]", browsed.toString());
    }

    @Test
    public void ctrlEnterFollowsALinkElseItIsTask() {
        on("- [ ] [go](https://g.example)\n").cursor(0, 8);
        MarkdownTasks.followLinkOrTask();
        assertEquals("[https://g.example]", browsed.toString());
        h.assertText("- [ ] [go](https://g.example)\n");
        h.cursor(0, 2);
        MarkdownTasks.followLinkOrTask();
        h.assertText("- [/] [go](https://g.example)\n");
    }

    @Test
    public void urlsAnywhere() {
        h = EditorHarness.create("visit https://example.org today\n")
            .mode(PlainTextMode.getMode());
        Editor.setCurrentEditor(h.editor());
        h.cursor(0, 10);
        FollowLink.followLink();
        assertEquals("[https://example.org]", browsed.toString());
    }

    @Test
    public void gxInVim() {
        on("[go](#end)\n# End\n").vim().cursor(0, 1);
        h.keys("gx");
        assertEquals(1, h.editor().getDotLineNumber());
    }

    @Test
    public void referenceLinksAndTheirDefinitions() {
        on(
            "See [the docs][docs] here.\n- [item][1] in a list\n"
                + "[Two Words][two words] and [x][Docs]\n\n"
                + "[docs]: https://d.example\n[1]: https://one.example \"Title\"\n"
                + "[two words]: https://two.example\n"
        );
        assertEquals("https://d.example", linkAt(0, 6));
        assertEquals("https://d.example", linkAt(0, 16));
        assertEquals("https://one.example", linkAt(1, 4));
        assertEquals("https://two.example", linkAt(2, 3));
        assertEquals("https://d.example", linkAt(2, 27));
    }

    @Test
    public void ctrlEnterOnAnUndefinedReferenceSaysSo() {
        on("See [text][ref] here.\n").cursor(0, 6);
        MarkdownTasks.followLinkOrTask();
        h.assertText("See [text][ref] here.\n");
        assertTrue(h.status(), h.status().contains("No definition of [ref]"));
    }

    @Test
    public void ctrlEnterLeavesALineOfTextAlone() {
        // Not a link in code, and not a task: as it was, not "- [ ] ...".
        on("  autolinks, `[text][ref]`, and more\n").cursor(0, 16);
        MarkdownTasks.followLinkOrTask();
        h.assertText("  autolinks, `[text][ref]`, and more\n");
        assertTrue(h.status(), h.status().contains("No link or task here"));
    }

    @Test
    public void ctrlEnterGivesAListItemABox() {
        on("- item\n").cursor(0, 3);
        MarkdownTasks.followLinkOrTask();
        h.assertText("- [ ] item\n");
    }

    @Test
    public void anAnchorNamesATag() {
        on("# Hello, World!\n## Notes\n## Notes\n");
        final java.util.List<LocalTag> tags = h.buffer().getTags(true);
        assertTrue(tags.get(0).isNamedBy("hello-world"));
        assertTrue(tags.get(0).isNamedBy("Hello-World"));
        assertTrue(tags.get(2).isNamedBy("notes-1"));
        assertTrue(!tags.get(1).isNamedBy("notes-1"));
    }

    @Test
    public void altEnterAndAltCAreTasksEvenOnALink() {
        on("- [ ] [go](#end)\n# End\n").cursor(0, 8);
        h.keys("<A-CR>");
        h.assertText("- [/] [go](#end)\n# End\n");
        assertEquals(0, h.editor().getDotLineNumber());
        h.keys("<A-c>");
        h.assertText("- [x] [go](#end)\n# End\n");
    }

    @Test
    public void anEmailAutolinkIsMailto() {
        on("<me@example.com>\n");
        assertEquals("mailto:me@example.com", linkAt(0, 3));
    }

    @Test
    public void aFileTargetKeepsHashAndPercentInItsPath() throws Exception {
        final Path dir = Files.createTempDirectory("j-C#");
        final Path p = dir.resolve("my%20notes.md");
        Files.write(p, "# One\n\n# Two\n".getBytes("UTF-8"));
        files.add(p);
        files.add(dir);
        final File file = File.getInstance(p.toString());
        final String target = FollowLink.fileTarget(file, "two");
        assertTrue(target, target.contains("%23") && target.contains("%2520"));
        on("x\n");
        FollowLink.follow(h.editor(), target);
        assertEquals("my%20notes.md", h.editor().getBuffer().getFile().getName());
        assertEquals(2, h.editor().getDotLineNumber());
    }
}
