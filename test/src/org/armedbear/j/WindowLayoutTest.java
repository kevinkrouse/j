/*
 * WindowLayoutTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class WindowLayoutTest {
    private final List<EditorHarness> harnesses = new ArrayList<>();

    @AfterEach
    public void tearDown() {
        for (EditorHarness h : harnesses)
            h.close();
    }

    private Editor editor(String text) {
        final EditorHarness h = EditorHarness.create(text);
        harnesses.add(h);
        return h.editor();
    }

    private static String ten(String name) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++)
            sb.append(name).append(i).append('\n');
        return sb.toString();
    }

    // a | (b over c), the caret in b.
    private EditorPane threeWindows(Editor a, Editor b, Editor c) {
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, true);
        pane.split(b, c, false);
        return pane;
    }

    private static String shape(WindowLayout.Node node) {
        if (node instanceof WindowLayout.Window w)
            return w.buffer().getLine(0).getText() + "@" + w.dotLine() + "," + w.dotOffset() + (w.focused() ? "*" : "");
        final WindowLayout.Split s = (WindowLayout.Split) node;
        final StringBuilder sb = new StringBuilder(s.row() ? "row(" : "column(");
        for (int i = 0; i < s.children().size(); i++) {
            if (i > 0)
                sb.append(' ');
            sb.append(shape(s.children().get(i))).append(':').append(Math.round(s.shares().get(i) * 100));
        }
        return sb.append(')').toString();
    }

    @Test
    public void capturesTheSplitsAndEachWindowsBufferAndCaret() {
        final Editor a = editor(ten("a"));
        final Editor b = editor(ten("b"));
        final Editor c = editor(ten("c"));
        final EditorPane pane = threeWindows(a, b, c);
        b.setDot(b.getBuffer().getLine(3), 2);
        assertEquals("row(a0@0,0:50 column(b0@3,2*:50 c0@0,0:50):50)", shape(pane.capture(b)));
    }

    @Test
    public void showingPutsABufferInTheFocusedWindowOnly() {
        final Editor a = editor(ten("a"));
        final Editor b = editor(ten("b"));
        final Editor c = editor(ten("c"));
        final WindowLayout.Node was = threeWindows(a, b, c).capture(c);
        final Editor x = editor(ten("x"));
        assertEquals("row(a0@0,0:50 column(b0@0,0:50 x0@0,0*:50):50)", shape(WindowLayout.showing(was, x.getBuffer())));
        // None focused: the first.
        final WindowLayout.Node none = threeWindows(editor(ten("a")), editor(ten("b")), editor(ten("c"))).capture(null);
        assertEquals("row(x0@0,0:50 column(b0@0,0:50 c0@0,0:50):50)", shape(WindowLayout.showing(none, x.getBuffer())));
    }

    @Test
    public void restoresTheSameShapeWithOtherEditors() {
        final Editor a = editor(ten("a"));
        final Editor b = editor(ten("b"));
        final Editor c = editor(ten("c"));
        final WindowLayout.Node was = threeWindows(a, b, c).capture(c);
        // Another pane, as the frame's after the log: one window, then the rest new.
        final Editor x = editor(ten("a"));
        final Editor y = editor(ten("b"));
        final Editor z = editor(ten("c"));
        y.setBufferDirectly(b.getBuffer());
        z.setBufferDirectly(c.getBuffer());
        x.setBufferDirectly(a.getBuffer());
        final EditorPane pane = new EditorPane(x);
        pane.restore(was, List.of(x, y, z));
        assertEquals(shape(was).replace("*", ""), shape(pane.capture(null)));
        assertTrue(pane.checkEditorLeafCount());
    }

    @Test
    public void keepsEachSplitsShares() {
        final Editor a = editor(ten("a"));
        final Editor b = editor(ten("b"));
        final WindowLayout.Split uneven = new WindowLayout.Split(true,
                List.of(
                    new WindowLayout.Window(a.getBuffer(), 0, 0, 0, true),
                    new WindowLayout.Window(b.getBuffer(), 0, 0, 0, false)),
                List.of(0.25, 0.75));
        final EditorPane pane = new EditorPane(a);
        pane.restore(uneven, List.of(a, b));
        assertEquals("row(a0@0,0:25 b0@0,0:75)", shape(pane.capture(null)), "by the weights");
        pane.setSize(1000 + pane.getDividerSize(), 600);
        pane.doLayout();
        assertEquals("row(a0@0,0:25 b0@0,0:75)", shape(pane.capture(null)), "laid out");
        assertEquals(250, a.getWidth(), 1);
    }

    @Test
    public void leavesOutWindowsOfBuffersClosedSince() {
        final Editor a = editor(ten("a"));
        final Editor b = editor(ten("b"));
        final Editor c = editor(ten("c"));
        final WindowLayout.Node layout = threeWindows(a, b, c).capture(a);
        final WindowLayout.Node kept = WindowLayout.keeping(layout, buf -> buf != b.getBuffer());
        assertEquals("row(a0@0,0*:50 c0@0,0:50)", shape(kept), "b's column is c alone");
        assertNull(WindowLayout.keeping(layout, buf -> false));
        final WindowLayout.Node one = WindowLayout.keeping(layout, buf -> buf == c.getBuffer());
        assertEquals("c0@0,0", shape(one));
    }

    @Test
    public void putsTheCaretAndTopLineBackAsNearAsTheBufferAllows() {
        final Editor a = editor(ten("a"));
        final WindowLayout.Window w = new WindowLayout.Window(a.getBuffer(), 4, 99, 2, true);
        w.applyTo(a);
        assertEquals(4, a.getDotLineNumber());
        assertEquals(a.getDotLine().length(), a.getDotOffset(), "past the end of the line: its end");
        assertSame(a.getBuffer().getLine(2), a.getTopLine());
        new WindowLayout.Window(a.getBuffer(), 500, 0, 500, true).applyTo(a);
        assertSame(a.getBuffer().getLastLine(), a.getDotLine(), "past the end of the buffer: its last line");
        final Editor other = editor("x");
        new WindowLayout.Window(a.getBuffer(), 1, 0, 0, true).applyTo(other);
        assertFalse(
            other.getDotLineNumber() == 1 && other.getBuffer() == a.getBuffer(),
            "another buffer's: left alone");
    }

    @Test
    public void aBufferShownInNoWindowIsWhereItsLastOneLeftIt() {
        final Editor a = editor(ten("a"));
        a.setDot(a.getBuffer().getLine(4), 1);
        a.saveView();
        assertEquals("a0@4,1*", shape(WindowLayout.Window.of(a.getBuffer(), true)));
        assertEquals("a0@4,1", shape(WindowLayout.Window.of(a, false)), "and a window's, as it is");
    }
}
