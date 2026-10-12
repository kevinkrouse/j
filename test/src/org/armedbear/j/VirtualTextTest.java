/*
 * VirtualTextTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.armedbear.j.VirtualText.Placement;
import org.armedbear.j.VirtualText.Run;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Text the display draws and the buffer does not hold. */
public class VirtualTextTest {
    private EditorHarness h;
    private Display display;
    private VirtualTextLayer layer;
    private int ch;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    // n numbered lines in a window ten rows high.
    private void lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(i).append('\n');
        h = EditorHarness.create(sb.toString()).rows(10);
        display = h.editor().getDisplay();
        layer = h.buffer().getVirtualText();
        ch = Display.getCharHeight();
        Editor.setCurrentEditor(h.editor());
    }

    // Sets an owner's rows and waits for the repaint it posts, which reframes
    // the window and would otherwise land after what the test does next.
    private void set(String owner, List<VirtualText> list) {
        layer.set(owner, list);
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Line line(int n) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < n; i++)
            l = l.next();
        return l;
    }

    private static List<List<Run>> rows(String... texts) {
        return java.util.Arrays.stream(texts).map(t -> List.of(Run.of(t))).toList();
    }

    private void top(int line, int pixels) {
        assertEquals(line, display.getTopLine().lineNumber(), "top line");
        assertEquals(pixels, display.getPixelsAboveTopLine(), "pixels above the top line");
    }

    @Test
    public void anOwnerReplacesAndClearsItsOwn() {
        lines(5);
        set("a", List.of(VirtualText.eol(line(1), List.of(Run.of("one")))));
        set("b", List.of(VirtualText.below(line(1), rows("b1", "b2"))));
        set("a", List.of(VirtualText.eol(line(2), List.of(Run.of("two")))));
        assertTrue(layer.on(line(1), Placement.EOL).isEmpty());
        assertEquals("two", VirtualText.text(layer.on(line(2), Placement.EOL).get(0).rows().get(0)));
        assertEquals(2, layer.rowsBelow(line(1)));
        layer.clear("b");
        assertEquals(0, layer.rowsBelow(line(1)));
        assertEquals(1, layer.get("a").size());
        layer.clear("a");
        assertTrue(layer.isEmpty());
        assertNull(h.buffer().virtualTextIfAny());
    }

    @Test
    public void priorityOrdersALinesText() {
        lines(3);
        set("late", List.of(VirtualText.eol(line(0), List.of(Run.of("late"))).withPriority(10)));
        set("early", List.of(VirtualText.eol(line(0), List.of(Run.of("early"))).withPriority(-1)));
        final List<VirtualText> eol = layer.on(line(0), Placement.EOL);
        assertEquals("early", VirtualText.text(eol.get(0).rows().get(0)));
        assertEquals("late", VirtualText.text(eol.get(1).rows().get(0)));
    }

    @Test
    public void placementsCheckTheirShape() {
        lines(2);
        assertThrows(
            IllegalArgumentException.class,
            () -> new VirtualText(line(0), Placement.TOP, rows("x"), 0, null, false));
        assertThrows(
            IllegalArgumentException.class,
            () -> new VirtualText(line(0), Placement.EOL, rows("x", "y"), 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> VirtualText.above(null, rows("x")));
    }

    @Test
    public void textStaysWithItsLineThroughEdits() {
        lines(5);
        final Line three = line(3);
        set("t", List.of(VirtualText.below(three, rows("under three"))));
        // Typing on the line keeps the line.
        h.cursor(3, 0).keys("abc");
        assertEquals(1, layer.rowsBelow(three));
        assertSame(three, line(3));
        // A line inserted before it moves it down, rows and all.
        h.cursor(0, 0).keys("<Enter>");
        assertSame(three, line(4));
        h.buffer().renumber();
        assertEquals(5 * ch, h.buffer().getY(line(5)) - ch);
        assertEquals(1, layer.totalRows());
    }

    @Test
    public void textOfADeletedLineGoesWithIt() {
        lines(6);
        final Line four = line(4);
        set("t", List.of(VirtualText.above(four, rows("over four")), VirtualText.eol(line(1), List.of(Run.of("e")))));
        assertEquals(1, layer.totalRows());
        // Joining 3 and 4 keeps line 3 and takes line 4 out of the buffer.
        h.editor().setDot(line(3), line(3).length());
        h.editor().setMark(new Position(four, four.length()));
        h.editor().deleteRegion();
        h.buffer().renumber();
        assertTrue(!h.buffer().contains(four));
        assertEquals(0, layer.totalRows());
        assertEquals(
            List.of(line(1)),
            layer.get("t").stream().map(VirtualText::line).toList(),
            "the other line's is kept");
        assertEquals(5 * ch, h.buffer().getDisplayHeight());
    }

    @Test
    public void rowsCountInTheBuffersHeightAndYs() {
        lines(10);
        set("top", List.of(VirtualText.top(rows("hint"))));
        set("t", List.of(VirtualText.above(line(4), rows("a", "b")), VirtualText.below(line(6), rows("c"))));
        assertEquals((10 + 1 + 2 + 1) * ch, h.buffer().getDisplayHeight(), "ten lines and four rows");
        assertEquals(0, h.buffer().getY(line(0)));
        assertEquals(2 * ch, h.buffer().getY(line(1)));
        assertEquals((4 + 1) * ch, h.buffer().getY(line(4)), "the top of line 4's rows");
        assertEquals((5 + 3) * ch, h.buffer().getY(line(5)));
        assertEquals((7 + 4) * ch, h.buffer().getY(line(7)));
    }

    @Test
    public void foldedLinesRowsAreNotCounted() {
        lines(10);
        set("t", List.of(VirtualText.below(line(5), rows("c", "d"))));
        FoldCommands.hideLines(h.editor(), line(4), line(7));
        assertEquals(0, layer.totalRows());
        assertEquals(4 * ch, h.buffer().getY(line(7)), "lines 4 to 6 are hidden");
    }

    @Test
    public void yToLineStepsOverRows() {
        lines(30);
        set("top", List.of(VirtualText.top(rows("hint"))));
        set("t", List.of(VirtualText.below(line(1), rows("x", "y"))));
        assertSame(line(0), display.lineFromY(0), "the hint's row is the first line's");
        assertSame(line(0), display.lineFromY(ch + 1));
        assertSame(line(1), display.lineFromY(2 * ch));
        assertSame(line(1), display.lineFromY(4 * ch + 1), "a row under line 1 is line 1's");
        assertSame(line(2), display.lineFromY(5 * ch));
    }

    @Test
    public void scrollingStopsWithTheLastRowAtTheBottom() {
        lines(30);
        set("t", List.of(VirtualText.below(line(3), rows("x", "y"))));
        // 30 lines and two rows in a ten row window.
        assertEquals((30 + 2 - 10) * ch, display.scrollByPixels(100 * ch));
    }

    @Test
    public void aLineAndItsRowsAreOneStep() {
        lines(30);
        set("top", List.of(VirtualText.top(rows("hint"))));
        set("t", List.of(VirtualText.below(line(1), rows("x", "y"))));
        display.windowDown();
        top(1, 0);
        display.windowDown();
        top(2, 0);
        display.windowUp();
        top(1, 0);
        display.windowUp();
        top(0, 0);
    }

    @Test
    public void theCaretSkipsRows() {
        lines(30);
        set("t", List.of(VirtualText.below(line(1), rows("x", "y")), VirtualText.above(line(2), rows("z"))));
        h.cursor(1, 0);
        display.down(false);
        assertEquals(2, h.editor().getDotLineNumber());
        display.up(false);
        assertEquals(1, h.editor().getDotLineNumber());
        h.keys("<Down><Down>");
        assertEquals(3, h.editor().getDotLineNumber());
    }

    @Test
    public void downOntoALineAtTheBottomShowsItsText() {
        lines(30);
        set("t", List.of(VirtualText.below(line(10), rows("x", "y"))));
        h.cursor(9, 0);
        display.down(false);
        assertEquals(10, h.editor().getDotLineNumber());
        top(1, 0);
    }

    @Test
    public void runsAreFoundWhereTheyAreDrawn() {
        lines(10);
        final AtomicInteger clicks = new AtomicInteger();
        final Run link = Run.of("[link]").withAction(clicks::incrementAndGet, "a link");
        set("top", List.of(VirtualText.top(List.of(List.of(Run.of("Hint: "), link)))));
        set("eol", List.of(VirtualText.eol(line(2), List.of(Run.of("blame")))));
        final int gutter = Display.getGutterWidth(h.buffer());
        final int cw = Display.getCharWidth();
        // A monospaced font: "Hint: " is six characters.
        assertSame(link, display.virtualRunAt(gutter + 7 * cw, ch / 2));
        assertEquals("a link", display.virtualRunAt(gutter + 7 * cw, ch / 2).tooltip());
        assertNull(display.virtualRunAt(gutter + 30 * cw, ch / 2));
        // Line 2 is "2", in the fourth row: the hint, 0, 1, 2. Its text
        // starts a space after the line's end.
        final Run blame = display.virtualRunAt(gutter + 3 * cw, 3 * ch + 1);
        assertEquals("blame", blame.text());
        assertNull(display.virtualRunAt(gutter + cw / 2, 3 * ch + 1), "the line's own text");
    }

    @Test
    public void aTopRowPushesTheTextDownARow() {
        lines(5);
        final BufferedImage without = paint();
        set("top", List.of(VirtualText.top(rows("hint"))));
        final BufferedImage with = paint();
        for (int y = 0; y < 4 * ch; y++)
            for (int x = 0; x < without.getWidth(); x++)
                assertEquals(without.getRGB(x, y), with.getRGB(x, y + ch), "pixel " + x + "," + y);
        boolean drawn = false;
        for (int x = 0; x < with.getWidth() && !drawn; x++)
            for (int y = 0; y < ch && !drawn; y++)
                drawn = with.getRGB(x, y) != with.getRGB(with.getWidth() - 1, y);
        assertTrue(drawn, "the hint's row has text");
    }

    @Test
    public void scrollingPastOnlyTheRowsOverTheCaretsLineLeavesTheCaret() {
        lines(30);
        set("top", List.of(VirtualText.top(rows("hint"))));
        h.cursor(0, 0);
        display.scrollByPixels(ch);
        h.editor().maybeScrollCaret();
        assertEquals(0, h.editor().getDotLineNumber(), "line 0's text is all on screen");
        display.setUpdateFlag(Constants.REFRAME);
        display.reframe();
        top(0, ch);
    }

    @Test
    public void theCaretsLineIsOnScreenThoughItsRowsBelowAreNot() {
        lines(30);
        set("t", List.of(VirtualText.below(line(9), rows("a", "b", "c"))));
        h.cursor(9, 0);
        h.editor().maybeScrollCaret();
        assertEquals(9, h.editor().getDotLineNumber());
        assertSame(line(9), display.getBottomLine());
    }

    @Test
    public void downOntoALineWithMoreRowsThanTheWindowShowsItsText() {
        lines(30);
        final String[] many = new String[12];
        java.util.Arrays.fill(many, "row");
        set("t", List.of(VirtualText.below(line(9), rows(many))));
        h.cursor(8, 0);
        display.down(false);
        assertEquals(9, h.editor().getDotLineNumber());
        assertSame(line(9), display.getBottomLine(), "its text at the bottom, its rows under the window");
    }

    @Test
    public void rowsGoingWhileScrolledLeaveTheTopInsideALine() throws Exception {
        lines(30);
        set("top", List.of(VirtualText.top(rows("a", "b"))));
        display.scrollByPixels(2 * ch);
        top(0, 2 * ch);
        layer.clear("top");
        javax.swing.SwingUtilities.invokeAndWait(() -> {}); // The repaint it posts.
        assertTrue(display.getPixelsAboveTopLine() < line(display.getTopLineNumber()).getHeight());
    }

    @Test
    public void topRowsGoOverTheFirstLineShown() {
        lines(10);
        set("top", List.of(VirtualText.top(rows("hint"))));
        FoldCommands.hideLines(h.editor(), line(0), line(2));
        assertTrue(layer.isTopLine(line(2)));
        assertEquals(1, layer.rowsAbove(line(2)));
        assertEquals(0, h.buffer().getY(line(2)), "the first line shown starts the buffer");
        assertEquals(2 * ch, h.buffer().getY(line(3)));
        assertEquals((8 + 1) * ch, h.buffer().getDisplayHeight());
    }

    @Test
    public void rowsUnderTheLastLineScrollIntoView() {
        lines(30);
        set("t", List.of(VirtualText.below(line(29), rows("x", "y"))));
        assertEquals((30 + 2 - 10) * ch, display.scrollByPixels(100 * ch));
        assertSame(line(29), display.lineFromY(9 * ch), "the last row is line 29's");
    }

    @Test
    public void toBottomCountsRows() {
        lines(30);
        set("t", List.of(VirtualText.below(line(12), rows("x", "y"))));
        h.cursor(15, 0);
        display.toBottom();
        // 15 at the bottom row, with 12's two rows above it: top is 8.
        top(8, 0);
        assertSame(line(15), display.getBottomLine());
    }

    @Test
    public void aPageIsAWindowOfRowsNotOfLines() {
        lines(30);
        for (int n = 1; n <= 3; n++)
            set("below" + n, List.of(VirtualText.below(line(n), rows("a", "b"))));
        h.editor().setDot(line(0), 0);
        MotionCommands.pageDown(h.editor());
        top(3, 0); // Lines 0 to 3 filled the ten rows; 3 is the overlap.
        MotionCommands.pageUp(h.editor());
        top(0, 0);
    }

    @Test
    public void aPageUpCountsRowsOverTheTopLineOnce() {
        lines(30);
        set("t", List.of(VirtualText.above(line(12), rows("a", "b"))));
        h.editor().setDot(line(20), 0);
        display.setTopLine(line(12));
        // Line 12's rows and text are three rows; seven more lines fill the window.
        assertSame(line(5), display.pageAbove());
    }

    private BufferedImage paint() {
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
}
