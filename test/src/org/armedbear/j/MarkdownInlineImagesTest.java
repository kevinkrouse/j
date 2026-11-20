/*
 * MarkdownInlineImagesTest.java
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

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.armedbear.j.mode.markdown.MarkdownInlineImages;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A standalone {@code ![alt](src)} line in a Markdown buffer becomes a
 * REPLACE that draws the picture, revealed to source when the caret
 * enters. The picture is read off the event thread, so the assertions
 * wait for it to land.
 */
public class MarkdownInlineImagesTest {
    private EditorHarness h;
    private java.io.File imageFile;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
        if (imageFile != null && imageFile.exists())
            imageFile.delete();
    }

    // A markdown buffer whose directory holds a red 40x20 PNG named
    // "pic.png", with the caret on the first line.
    private void markdownWithImage() throws Exception {
        final String text = "before\n![a red box](pic.png)\nafter\n";
        h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(10);
        Editor.setCurrentEditor(h.editor());
        // The picture must sit in the buffer's own directory for resolve()
        // to find it.
        final java.nio.file.Path dir =
                java.nio.file.Path.of(h.buffer().getCurrentDirectory().getAbsolutePath());
        imageFile = dir.resolve("pic.png").toFile();
        final BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 40, 20);
        g.dispose();
        ImageIO.write(img, "png", imageFile);
    }

    private Line line(int n) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < n; i++)
            l = l.next();
        return l;
    }

    // Waits up to two seconds for the async load to register a REPLACE on
    // the image line, then returns it.
    private VirtualText awaitReplace(Line imageLine) throws Exception {
        for (int i = 0; i < 200; i++) {
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
            final VirtualText r = h.buffer().virtualTextIfAny() == null
                    ? null
                    : h.buffer().virtualTextIfAny().replaceFor(imageLine);
            if (r != null)
                return r;
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    public void aStandaloneImageLineBecomesAReplace() throws Exception {
        markdownWithImage();
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, true);
        MarkdownInlineImages.onActivate(h.editor());
        final VirtualText r = awaitReplace(line(1));
        assertNotNull(r, "the image line got a REPLACE");
        assertEquals(VirtualText.Placement.REPLACE, r.placement());
        // The painter reports a positive height for the 40x20 picture.
        assertTrue(r.painter().getHeight() > 0, "the painter has a height");
    }

    @Test
    public void theReplaceIsOnlyOnTheImageLine() throws Exception {
        markdownWithImage();
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, true);
        MarkdownInlineImages.onActivate(h.editor());
        assertNotNull(awaitReplace(line(1)), "the image line");
        // The lines around it are untouched.
        assertNull(h.buffer().virtualTextIfAny().replaceFor(line(0)), "before is not replaced");
        assertNull(h.buffer().virtualTextIfAny().replaceFor(line(2)), "after is not replaced");
    }

    @Test
    public void offMeansNoReplace() throws Exception {
        markdownWithImage();
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, false);
        MarkdownInlineImages.onActivate(h.editor());
        Thread.sleep(50);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        final VirtualTextLayer layer = h.buffer().virtualTextIfAny();
        assertTrue(layer == null || layer.replaceFor(line(1)) == null, "nothing drawn when off");
    }

    @Test
    public void aToggleTurnsItOnAndOff() throws Exception {
        markdownWithImage();
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, false);
        MarkdownInlineImages.toggle(h.editor());
        assertTrue(h.buffer().getBooleanProperty(Property.RENDER_INLINE_IMAGES), "on after toggle");
        assertNotNull(awaitReplace(line(1)), "drawn when on");
        MarkdownInlineImages.toggle(h.editor());
        assertFalse(h.buffer().getBooleanProperty(Property.RENDER_INLINE_IMAGES), "off after second toggle");
        // Cleared.
        final VirtualTextLayer layer = h.buffer().virtualTextIfAny();
        assertTrue(layer == null || layer.replaceFor(line(1)) == null, "cleared when off");
    }

    @Test
    public void aMissingFileKeepsItsLink() throws Exception {
        final String text = "before\n![nothing](nope.png)\nafter\n";
        h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(10);
        Editor.setCurrentEditor(h.editor());
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, true);
        MarkdownInlineImages.onActivate(h.editor());
        Thread.sleep(50);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        final VirtualTextLayer layer = h.buffer().virtualTextIfAny();
        assertTrue(layer == null || layer.replaceFor(line(1)) == null, "a missing file is not drawn");
    }

    @Test
    public void aRemoteImageKeepsItsLink() throws Exception {
        markdownWithImage();
        // Rewrite the image line to a remote source.
        h.buffer().getFirstLine().next().setText("![remote](https://example.com/x.png)");
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, true);
        MarkdownInlineImages.onActivate(h.editor());
        Thread.sleep(50);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        final VirtualTextLayer layer = h.buffer().virtualTextIfAny();
        assertTrue(layer == null || layer.replaceFor(line(1)) == null, "a remote image is not drawn");
    }

    @Test
    public void anImageInsideAFenceIsNotDrawn() throws Exception {
        final String text = "```\n![fenced](pic.png)\n```\n";
        h = EditorHarness.create(text).mode(MarkdownMode.getMode()).rows(10);
        Editor.setCurrentEditor(h.editor());
        // Put pic.png beside the buffer so resolve would find it if not fenced.
        final java.io.File beside =
                new java.io.File(h.buffer().getCurrentDirectory().getAbsolutePath(), "pic.png");
        final BufferedImage img = new BufferedImage(30, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", beside);
        imageFile = beside;
        h.buffer().setProperty(Property.RENDER_INLINE_IMAGES, true);
        MarkdownInlineImages.onActivate(h.editor());
        Thread.sleep(50);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        final VirtualTextLayer layer = h.buffer().virtualTextIfAny();
        assertTrue(layer == null || layer.replaceFor(line(1)) == null, "a fenced image is not drawn");
    }
}
