/*
 * ControlCharacterTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A raw control character in a buffer line is shown as a picture in one
 * fixed plain-font cell, never its own zero-width glyph, and never a
 * ligature crossing it. These tests pin the geometry through the public
 * {@link Display#measure} and the offscreen paint, and force the
 * unsupported-glyph box fallback deterministically.
 *
 * <p>Fixtures are built from explicit char values: Java would otherwise
 * process a backslash-u escape in the source into a different character.
 */
public class ControlCharacterTest {
    private static final char NUL = (char) 0x00;
    private static final char BEL = (char) 0x07;
    private static final char ESC = (char) 0x1B;
    private static final char DEL = (char) 0x7F;
    // The escape picture a user might type: ordinary printable text.
    private static final char ESC_PICTURE = '\u241B';

    private EditorHarness h;

    @BeforeEach
    public void setUp() {
        // A forced fallback in one test must not leak into the next.
        Display.clearControlGlyphCache();
    }

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private Line line(String text) {
        h = EditorHarness.create(text);
        Editor.setCurrentEditor(h.editor());
        return h.buffer().getFirstLine();
    }

    private Display display() {
        return h.editor().getDisplay();
    }

    private static int cell() {
        return Display.getCharWidth();
    }

    @Test
    public void controlIsOneFixedCell() {
        // Each control advances exactly one plain cell, whatever its own
        // glyph's advance would be.
        Line l = line("a" + ESC + "b");
        Display d = display();
        int cw = cell();
        assertEquals(0, d.measure(l, 0));
        assertEquals(cw, d.measure(l, 1), "the 'a'");
        assertEquals(2 * cw, d.measure(l, 2), "the control is one cell");
        assertEquals(3 * cw, d.measure(l, 3), "the 'b' after it");
    }

    @Test
    public void adjacentControlsEachTakeACell() {
        Line l = line("" + BEL + ESC + DEL);
        Display d = display();
        int cw = cell();
        assertEquals(cw, d.measure(l, 1));
        assertEquals(2 * cw, d.measure(l, 2));
        assertEquals(3 * cw, d.measure(l, 3));
    }

    @Test
    public void nulIsAControlCell() {
        Line l = line("x" + NUL + "y");
        Display d = display();
        int cw = cell();
        assertEquals(2 * cw, d.measure(l, 2), "NUL takes a cell, not zero width");
    }

    @Test
    public void tabIsNotAControlCell() {
        // Tabs keep their own detabbing; they are not pictures.
        assertFalse(Display.isControlCell('\t'));
        assertTrue(Display.isControlCell(ESC));
        assertTrue(Display.isControlCell(NUL));
        assertTrue(Display.isControlCell(DEL));
    }

    @Test
    public void typedPictureGlyphIsOrdinaryText() {
        // A user-typed escape picture is printable, not a control: it is
        // measured by its own glyph advance, not forced to the cell.
        assertFalse(Display.isControlCell(ESC_PICTURE));
        final String s = "a" + ESC_PICTURE + "b";
        h = EditorHarness.create(s);
        Editor.setCurrentEditor(h.editor());
        Line l = h.buffer().getFirstLine();
        Display d = display();
        // The width is the ordinary run's natural width, computed here
        // independently of the control-handling code. If the picture were
        // wrongly normalized to the cell, this would not match.
        assertEquals(naturalWidth(s), d.measure(l, 3), "the picture is measured by its natural advance");
    }

    @Test
    public void controlIsNormalizedToCell() {
        // A control is forced to the fixed cell, whatever the picture's
        // natural advance would be.
        h = EditorHarness.create("a" + ESC + "b");
        Editor.setCurrentEditor(h.editor());
        Line l = h.buffer().getFirstLine();
        Display d = display();
        assertEquals(3 * cell(), d.measure(l, 3), "control line is three fixed cells");
    }

    @Test
    public void hiddenControlDoesNotHangMeasurement() {
        // A control inside concealed markup, caret elsewhere: measuring
        // the line must terminate, not spin on the hidden control.
        Editor.preferences().setProperty("conceal", "markup");
        try {
            h = EditorHarness.create("[label](a" + ESC + "b)\nsecond\n");
            h.mode(MarkdownMode.getMode());
            Editor.setCurrentEditor(h.editor());
            h.buffer().getFormatter().parseBuffer();
            Display d = display();
            Line l = h.buffer().getFirstLine();
            h.editor().setDot(l.next(), 0);
            // Preemptive: a spin fails instead of wedging the suite.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () -> d.measure(l, l.length()),
                    "measuring a line with a concealed control must terminate");
        }
        finally {
            Editor.preferences().removeProperty("conceal");
        }
    }

    // The plain font's natural width of a whole string, measured the way
    // the headless measure path measures an ordinary run.
    private static int naturalWidth(String s) {
        java.awt.font.FontRenderContext frc =
                org.armedbear.j.util.Utilities.getFontMetrics(Display.getPlainFont()).getFontRenderContext();
        java.awt.font.GlyphVector gv =
                Display.getPlainFont().createGlyphVector(frc, s.toCharArray());
        return (int) gv.getLogicalBounds().getWidth();
    }

    @Test
    public void pictureIsDrawnWhenFontHasIt() {
        // Which fonts are installed varies; skip where the picture is
        // absent -- the box fallback is covered by its own test.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Display.getPlainFont().canDisplay(Display.pictureFor(ESC)),
                "plain font lacks the escape picture");
        Line l = line("" + ESC);
        Display d = display();
        BufferedImage img = paint(d);
        assertTrue(inkBeyondShade(img, 0), "the escape picture draws over the shade");
    }

    @Test
    public void boxFallbackWhenFontLacksPicture() {
        Line l = line("" + ESC);
        Display d = display();
        // Force the fallback for the escape picture in the plain font.
        Display.forceControlFallback(Display.getPlainFont(), ESC);
        BufferedImage img = paint(d);
        assertTrue(inkBeyondShade(img, 0), "the outlined box draws over the shade");
        // Still exactly one cell wide.
        assertEquals(cell(), d.measure(l, 1));
    }

    @Test
    public void pictureAndBoxAreDistinctRenderings() {
        // The picture and the fallback box are different shapes, so the
        // two paths render the cell differently -- proving the fallback
        // is a real alternative, not a silent no-op.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Display.getPlainFont().canDisplay(Display.pictureFor(ESC)),
                "plain font lacks the escape picture");
        h = EditorHarness.create("" + ESC);
        Editor.setCurrentEditor(h.editor());
        Display d = display();
        final String picture = cellRegion(paint(d), 0);
        Display.forceControlFallback(Display.getPlainFont(), ESC);
        final String box = cellRegion(paint(d), 0);
        assertNotEquals(picture, box, "the box fallback differs from the picture");
    }

    @Test
    public void controlShadeSitsUnderSelection() {
        // The cell shade is painted before the selection, so a selection
        // over a control still shows its own background.
        Line l = line("a" + ESC + "b");
        Display d = display();
        h.editor().setDot(l, 1);
        h.editor().setMark(new Position(l, 2));
        BufferedImage withSel = paint(d);
        h.editor().setMark(null);
        BufferedImage noSel = paint(d);
        // The control cell differs between selected and not: the
        // selection paints over the shade.
        assertNotEquals(cellRegion(withSel, 1), cellRegion(noSel, 1), "selection covers the shade");
    }

    // Paint the display offscreen.
    private BufferedImage paint(Display display) {
        final boolean ready = Editor.displayReady();
        Editor.setDisplayReady(true);
        try {
            display.setUpdateFlag(Constants.REPAINT);
            final BufferedImage image =
                    new BufferedImage(display.getWidth(), display.getHeight(), BufferedImage.TYPE_INT_RGB);
            final java.awt.Graphics2D g = image.createGraphics();
            g.setClip(0, 0, image.getWidth(), image.getHeight());
            display.paintComponent(g);
            g.dispose();
            return image;
        }
        finally {
            Editor.setDisplayReady(ready);
        }
    }

    // Whether the cell at column col on the first row has ink that is
    // neither the plain background nor the control's shade: the picture
    // or box itself.
    private boolean inkBeyondShade(BufferedImage img, int col) {
        final int ch = Display.getCharHeight();
        final int cw = cell();
        final int x0 = Display.getGutterWidth(h.buffer()) + col * cw;
        final Formatter f = h.editor().getFormatter();
        final int bg = f.getBackgroundColor().getRGB();
        final Color shadeColor = f.getControlBackground();
        final int shade = shadeColor == null ? bg : shadeColor.getRGB();
        for (int y = 0; y < ch; y++)
            for (int x = x0; x < x0 + cw; x++) {
                final int rgb = img.getRGB(x, y);
                if (rgb != bg && rgb != shade)
                    return true;
            }
        return false;
    }

    // The pixels of the cell at column col on the first row, as a string.
    private String cellRegion(BufferedImage img, int col) {
        final int ch = Display.getCharHeight();
        final int cw = cell();
        final int x0 = Display.getGutterWidth(h.buffer()) + col * cw;
        final StringBuilder sb = new StringBuilder();
        for (int y = 0; y < ch; y++)
            for (int x = x0; x < x0 + cw; x++)
                sb.append(Integer.toHexString(img.getRGB(x, y))).append(',');
        return sb.toString();
    }
}
