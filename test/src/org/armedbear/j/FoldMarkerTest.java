/*
 * FoldMarkerTest.java
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class FoldMarkerTest {
    private static final String JAVA = "class A {\n    int x;\n\n    void f() {\n        g();\n    }\n}\n";

    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text, Mode mode) {
        h = EditorHarness.create(text).mode(mode).rows(10);
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    private Line line(int n) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < n; i++)
            l = l.next();
        return l;
    }

    private boolean starts(int n) {
        return h.editor().getDisplay().isFoldStart(line(n));
    }

    private int foldColumn() {
        return h.buffer().getBooleanProperty(Property.SHOW_CHANGE_MARKS)
                ? Display.getGutterWidth(h.buffer()) - Display.getCharWidth()
                : 0;
    }

    // The lines to be seen, "|" between.
    private String visible() {
        final StringBuilder sb = new StringBuilder();
        for (Line l = h.buffer().getFirstLine(); l != null; l = l.next()) {
            if (l.isHidden())
                continue;
            if (sb.length() > 0)
                sb.append('|');
            sb.append(l.getText().strip());
        }
        return sb.toString();
    }

    @Test
    public void aLineWithMoreIndentedLinesUnderItStartsAFold() {
        on(JAVA, JavaMode.getMode());
        assertTrue(starts(0));
        assertFalse(starts(1));
        assertFalse(starts(2), "a blank line");
        assertTrue(starts(3));
        assertFalse(starts(4));
        assertFalse(starts(6));
    }

    @Test
    public void clickingAMarkerFoldsTheIndentedLinesAndClickingAgainUnfolds() {
        on(JAVA, JavaMode.getMode());
        h.editor().getMode().toggleFoldAt(h.editor(), line(3));
        assertEquals("class A {|int x;||void f() {|}|}", visible());
        assertTrue(h.editor().getMode().isFolded(line(3)));
        assertTrue(h.editor().getDisplay().isFoldMarkerAt(foldColumn(), line(3)), "a closed fold's, always");
        assertFalse(h.editor().getDisplay().isFoldMarkerAt(foldColumn(), line(0)), "an open one's, on hover");
        h.editor().getDisplay().setMouseInGutter(true, -1);
        assertTrue(h.editor().getDisplay().isFoldMarkerAt(foldColumn(), line(0)));
        assertFalse(
            h.editor().getDisplay().isFoldMarkerAt(foldColumn() + Display.getCharWidth(), line(0)),
            "beside the fold column: a line number, say");
        h.editor().getDisplay().setMouseInGutter(false, -1);
        h.editor().getMode().toggleFoldAt(h.editor(), line(0));
        assertEquals("class A {|}", visible(), "the blank line inside goes too");
        h.editor().getMode().toggleFoldAt(h.editor(), line(0));
        assertEquals("class A {|int x;||void f() {|}|}", visible(), "the inner fold stays folded");
    }

    @Test
    public void anEditForgetsTheAnswers() {
        on("a\n    b;\n}\n", JavaMode.getMode());
        assertFalse(starts(0));
        h.buffer().insertString(new Position(line(0), 1), " {");
        assertTrue(starts(0));
    }

    @Test
    public void markdownFoldsStartAtHeadingsListItemsAndFences() {
        on("# A\ntext\n\n- item\n  more\n- last\n\n```\ncode\n```\nend\n", MarkdownMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        assertTrue(starts(0), "a heading over a section");
        assertFalse(starts(1));
        assertTrue(starts(3), "an item with a line under it");
        assertFalse(starts(5), "an item with none");
        assertTrue(starts(7), "a fence's first line");
        assertFalse(starts(8), "inside the fence");
        assertArrayEquals(new Line[] { line(8), line(9) }, h.editor().getMode().getFoldBelow(h.buffer(), line(7)));
        assertNull(h.editor().getMode().getFoldBelow(h.buffer(), line(10)));
    }

    @Test
    public void openMarkersShowAsThePreferenceSays() {
        on(JAVA, JavaMode.getMode());
        h.editor().setDot(line(6), 0); // Off the rows compared, which line 3 starts a fold and line 4 not.
        final Display display = h.editor().getDisplay();
        final BufferedImage plain = paint();
        assertEquals(column(plain, 4), column(plain, 3), "hover: none until the mouse is in the gutter");
        display.setMouseInGutter(true, -1);
        final BufferedImage hover = paint();
        assertNotEquals(column(hover, 4), column(hover, 3), "the mouse is in the gutter");
        display.setMouseInGutter(false, -1);
        h.buffer().setProperty(Property.FOLD_MARKERS, "always");
        final BufferedImage always = paint();
        assertNotEquals(column(always, 4), column(always, 3), "always");
        h.buffer().setProperty(Property.FOLD_MARKERS, "never");
        display.setMouseInGutter(true, -1);
        final BufferedImage never = paint();
        assertEquals(column(never, 4), column(never, 3), "never");
        h.editor().getMode().toggleFoldAt(h.editor(), line(3));
        final BufferedImage closed = paint();
        assertNotEquals(column(closed, 4), column(closed, 3), "a closed fold's marker shows anyway");
    }

    @Test
    public void theFoldAroundALineIsTheInnermostOpenOneThroughItsClosingBrace() {
        on(JAVA, JavaMode.getMode());
        final Display display = h.editor().getDisplay();
        assertArrayEquals(new Line[] { line(3), line(5) }, display.foldAround(line(4)), "the method, its brace");
        assertArrayEquals(new Line[] { line(3), line(5) }, display.foldAround(line(3)), "its first line");
        assertArrayEquals(new Line[] { line(0), line(6) }, display.foldAround(line(1)), "the class");
        assertArrayEquals(new Line[] { line(0), line(6) }, display.foldAround(line(6)), "the class's brace");
        h.editor().getMode().toggleFoldAt(h.editor(), line(3));
        assertNull(display.foldAround(line(3)), "a closed fold's first line");
        assertArrayEquals(new Line[] { line(0), line(6) }, display.foldAround(line(5)), "its brace, the class's");
        h.editor().getMode().toggleFoldAt(h.editor(), line(3));
        assertArrayEquals(new Line[] { line(3), line(5) }, display.foldAround(line(5)), "opened: the method's again");
    }

    @Test
    public void aFoldStartingPastTheReachIsNotLookedFor() {
        final int fields = Display.FOLD_AROUND_REACH + 10;
        on("class A {\n" + "    int x;\n".repeat(fields) + "}\n", JavaMode.getMode());
        final Display display = h.editor().getDisplay();
        assertArrayEquals(new Line[] { line(0), line(fields + 1) }, display.foldAround(line(5)), "near its start");
        assertNull(display.foldAround(line(fields)), "too far below it");
    }

    @Test
    public void aClosedFoldsMarkerBrightensBesideTheMouse() {
        on(JAVA, JavaMode.getMode());
        h.editor().setDot(line(6), 0);
        h.editor().getMode().toggleFoldAt(h.editor(), line(3));
        final Display display = h.editor().getDisplay();
        display.setMouseInGutter(true, rowY(1));
        final BufferedImage away = paint();
        display.setMouseInGutter(true, rowY(3));
        final BufferedImage beside = paint();
        assertNotEquals(column(away, 3), column(beside, 3));
    }

    @Test
    public void belowTheLastLineTheMouseIsBesideNoFold() {
        on(JAVA, JavaMode.getMode());
        h.editor().setDot(line(6), 0);
        final Display display = h.editor().getDisplay();
        display.setMouseInGutter(true, -1);
        final BufferedImage none = paint();
        display.setMouseInGutter(true, rowY(9));
        final BufferedImage below = paint();
        assertEquals(column(none, 1), column(below, 1), "not the last line's, the class's, brighter");
    }

    @Test
    public void anExtentEndingOnAnotherFoldsMarkerReachesIt() {
        on(
            "class A {\n    void f() {\n        if (a) {\n            b();\n        } else {\n            c();\n        }\n    }\n}\n",
            JavaMode.getMode());
        h.buffer().setProperty(Property.FOLD_MARKERS, "always");
        h.editor().setDot(line(7), 0);
        final BufferedImage inMethod = paint();
        h.editor().setDot(line(3), 0);
        final BufferedImage inIf = paint();
        assertNotEquals(column(inMethod, 4), column(inIf, 4), "the if's extent down to the else's marker");
    }

    // The top of the n'th row shown, a pixel in.
    private static int rowY(int n) {
        return n * Display.getCharHeight() + 1;
    }

    @Test
    public void theCaretsFoldShowsItsMarkerAndExtentTheHoveredOnesBrighter() {
        on(JAVA, JavaMode.getMode());
        final Display display = h.editor().getDisplay();
        // The caret on the method's brace, off the rows compared.
        h.editor().setDot(line(5), 0);
        final BufferedImage inMethod = paint();
        assertNotEquals(column(inMethod, 1), column(inMethod, 3), "the method's marker");
        assertNotEquals(column(inMethod, 1), column(inMethod, 4), "its extent");
        assertEquals(column(inMethod, 1), column(inMethod, 6), "not the class's");
        h.editor().setDot(line(1), 0);
        final BufferedImage inClass = paint();
        assertEquals(column(inClass, 3), column(inClass, 4), "the class's extent past the method's line, no marker");
        assertNotEquals(column(inMethod, 3), column(inClass, 3));
        h.editor().setDot(line(5), 0);
        display.setMouseInGutter(true, rowY(4));
        final BufferedImage hovered = paint();
        assertNotEquals(column(inMethod, 4), column(hovered, 4), "brighter under the mouse");
        display.setMouseInGutter(false, -1);
        h.buffer().setProperty(Property.FOLD_MARKERS, "never");
        final BufferedImage never = paint();
        assertEquals(column(never, 1), column(never, 3), "never: no marker");
        assertEquals(column(never, 1), column(never, 4), "nor extent");
    }

    // The fold column's pixels on the n'th row shown, as a string.
    private static String column(BufferedImage image, int row) {
        final int ch = Display.getCharHeight();
        final int x0 = Display.getGutterWidth(Editor.currentEditor().getBuffer()) - Display.getCharWidth();
        final StringBuilder sb = new StringBuilder();
        for (int y = row * ch; y < (row + 1) * ch; y++)
            for (int x = Math.max(0, x0 - 4); x < x0 + Display.getCharWidth(); x++)
                sb.append(Integer.toHexString(image.getRGB(x, y))).append(',');
        return sb.toString();
    }

    private BufferedImage paint() {
        final Display display = h.editor().getDisplay();
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
