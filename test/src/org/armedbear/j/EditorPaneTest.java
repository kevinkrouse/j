/*
 * EditorPaneTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.swingx.MultiSplitLayout;
import org.jdesktop.swingx.MultiSplitLayout.Divider;
import org.jdesktop.swingx.MultiSplitLayout.Node;
import org.jdesktop.swingx.MultiSplitLayout.Split;
import org.junit.jupiter.api.Test;

/**
 * The split tree windows live in: splitting again after closing the others,
 * which window takes a closed one's place, and evening the sizes out.
 */
public class EditorPaneTest {
    private static Node model(EditorPane pane) {
        return pane.getMultiSplitLayout().getModel();
    }

    @Test
    public void aSplitAfterOnlyIsOnScreen() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, false);
        pane.root(a);
        pane.split(a, c, false);
        // The model is the new split, not the one b was in.
        final List<Node> children = ((Split) model(pane)).getChildren();
        assertSame(a.getLayoutLeaf(), children.get(0));
        assertSame(c.getLayoutLeaf(), children.get(2));
    }

    @Test
    public void theNextWindowTakesAClosedOnesPlace() {
        // Two side by side over one: col[row[a, b], c].
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, c, false);
        pane.split(a, b, true);
        assertSame(b, pane.successor(a));
        // The last in its row: the one before.
        assertSame(a, pane.successor(b));
        // Beside a split: its first window, whatever the caret.
        assertSame(a, pane.successor(c));
        // A window on its own has none.
        final Editor alone = new Editor();
        assertEquals(null, new EditorPane(alone).successor(alone));
    }

    @Test
    public void aSplitAfterADragIsInTheMiddle() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, true);
        pane.setSize(304, 100);
        final MultiSplitLayout layout = pane.getMultiSplitLayout();
        // As a drag leaves it; then b closes and c splits from a.
        layout.setFloatingDividers(false);
        ((Divider) ((Split) model(pane)).getChildren().get(1))
                .setBounds(new java.awt.Rectangle(10, 0, pane.getDividerSize(), 100));
        pane.unsplit(b);
        pane.split(a, c, true);
        layout.layoutContainer(pane);
        final List<Node> children = ((Split) model(pane)).getChildren();
        final int each = (304 - pane.getDividerSize()) / 2;
        assertEquals(each, children.get(0).getBounds().width);
        assertEquals(each, children.get(2).getBounds().width);
    }

    @Test
    public void aCloseAfterADragEvensTheRestOut() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, true);
        pane.split(b, c, true);
        pane.setSize(304, 100);
        pane.getMultiSplitLayout().setFloatingDividers(false);
        ((Divider) ((Split) model(pane)).getChildren().get(1))
                .setBounds(new java.awt.Rectangle(10, 0, pane.getDividerSize(), 100));
        pane.unsplit(c);
        pane.getMultiSplitLayout().layoutContainer(pane);
        final List<Node> children = ((Split) model(pane)).getChildren();
        final int each = (304 - pane.getDividerSize()) / 2;
        assertEquals(each, children.get(0).getBounds().width);
        assertEquals(each, children.get(2).getBounds().width);
    }

    @Test
    public void balanceEvensOutDraggedDividers() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, false);
        pane.split(b, c, false);
        pane.setSize(100, 308);
        final MultiSplitLayout layout = pane.getMultiSplitLayout();
        // As a drag leaves it: dividers fixed where they were put.
        layout.setFloatingDividers(false);
        final List<Node> children = ((Split) model(pane)).getChildren();
        ((Divider) children.get(1)).setBounds(new java.awt.Rectangle(0, 10, 100, pane.getDividerSize()));
        pane.balance();
        layout.layoutContainer(pane);
        final int size = pane.getDividerSize();
        final int each = (308 - 2 * size) / 3;
        assertEquals(each, children.get(0).getBounds().height);
        assertEquals(each, children.get(2).getBounds().height);
        assertEquals(each, children.get(4).getBounds().height);
    }

    @Test
    public void thePanelGoesUnderAllTheWindows() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor p = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, true);
        final Node windows = model(pane);
        pane.openPanel(p);
        assertTrue(pane.isPanel(p));
        final Split column = (Split) model(pane);
        assertFalse(column.isRowLayout());
        assertSame(windows, column.getChildren().get(0));
        assertSame(p.getLayoutLeaf(), column.getChildren().get(2));
        // Closed, the windows are as they were.
        pane.unsplit(p);
        assertSame(windows, model(pane));
        assertFalse(pane.isPanel(p));
    }

    @Test
    public void aSplitWithThePanelOpenStaysAboveIt() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor p = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.openPanel(p);
        // The same direction as the panel's column, but not in it.
        pane.split(a, b, false);
        final List<Node> children = ((Split) model(pane)).getChildren();
        assertEquals(3, children.size());
        final Split windows = (Split) children.get(0);
        assertSame(a.getLayoutLeaf(), windows.getChildren().get(0));
        assertSame(b.getLayoutLeaf(), windows.getChildren().get(2));
        assertSame(p.getLayoutLeaf(), children.get(2));
        assertEquals(List.of(a, b), pane.getSiblings(a));
        pane.unsplit(b);
        assertSame(a.getLayoutLeaf(), ((Split) model(pane)).getChildren().get(0));
        assertTrue(pane.isPanel(p));
    }

    @Test
    public void thePanelKeepsItsShare() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor p = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, false);
        pane.openPanel(p);
        pane.setSize(100, 1000);
        pane.getMultiSplitLayout().setFloatingDividers(false);
        pane.balance();
        pane.getMultiSplitLayout().layoutContainer(pane);
        final int total = 1000 - pane.getDividerSize();
        assertEquals((int) (total * 0.3), p.getLayoutLeaf().getBounds().height);
    }

    @Test
    public void withTheLastWindowClosedThePanelIsAWindow() {
        final Editor a = new Editor();
        final Editor p = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.openPanel(p);
        pane.unsplit(a);
        assertSame(p.getLayoutLeaf(), model(pane));
        assertFalse(pane.isPanel(p));
    }

    // Every window inside the pane, with room, and none over another.
    private static void assertTiled(EditorPane pane, Editor... editors) {
        final java.awt.Rectangle all = new java.awt.Rectangle(0, 0, pane.getWidth(), pane.getHeight());
        for (int i = 0; i < editors.length; i++) {
            final java.awt.Rectangle r = editors[i].getBounds();
            assertTrue(r.width > 0 && r.height > 0, "window " + i + " has room: " + r);
            assertTrue(all.contains(r), "window " + i + " inside the pane: " + r);
            for (int j = 0; j < i; j++)
                assertFalse(r.intersects(editors[j].getBounds()), "windows " + j + " and " + i + " overlap");
        }
    }

    // As the mouse leaves it: dividers fixed, this one moved.
    private static void drag(EditorPane pane, Divider divider, int to) {
        pane.getMultiSplitLayout().setFloatingDividers(false);
        final java.awt.Rectangle r = divider.getBounds();
        if (divider.isVertical())
            r.x = to;
        else
            r.y = to;
        divider.setBounds(r);
    }

    @Test
    public void afterADragAResizeKeepsEachWindowsShare() {
        // a over b over c; the first divider dragged down, then the frame shrinks.
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, false);
        pane.split(b, c, false);
        pane.setSize(400, 600);
        pane.doLayout();
        drag(pane, (Divider) ((Split) model(pane)).getChildren().get(1), 300);
        pane.doLayout();
        final int aHeight = a.getHeight();
        pane.setSize(400, 300);
        pane.doLayout();
        assertTiled(pane, a, b, c);
        assertEquals(aHeight / 2.0, a.getHeight(), 3);
    }

    @Test
    public void aDividerDraggedPastTheWindowsBeyondItStillTiles() {
        // col[a, row-of(col[b, c], d)]: the outer divider dragged to the
        // bottom leaves no room for b and c as they were.
        final Editor a = new Editor();
        final Editor b = new Editor();
        final Editor c = new Editor();
        final Editor d = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, false);
        pane.split(b, d, true);
        pane.split(b, c, false);
        pane.setSize(600, 600);
        pane.doLayout();
        drag(pane, (Divider) ((Split) model(pane)).getChildren().get(1), 590);
        pane.doLayout();
        assertTiled(pane, a, b, c, d);
    }

    @Test
    public void aDragDoesNotSqueezeAWindowToNothing() {
        final Editor a = new Editor();
        final Editor b = new Editor();
        final EditorPane pane = new EditorPane(a);
        pane.split(a, b, false);
        pane.setSize(400, 600);
        pane.doLayout();
        drag(pane, (Divider) ((Split) model(pane)).getChildren().get(1), 0);
        pane.doLayout();
        assertTrue(a.getHeight() >= Display.getCharHeight() * 2, "a keeps two lines: " + a.getHeight());
        assertTiled(pane, a, b);
    }

    @Test
    public void nineWindowsInARowLayOut() {
        // Nine weights of 1/9, added up, are a hair over 1, which the layout refuses.
        final Editor first = new Editor();
        final EditorPane pane = new EditorPane(first);
        Editor last = first;
        for (int i = 1; i < 9; i++) {
            final Editor next = new Editor();
            pane.split(last, next, true);
            last = next;
        }
        pane.setSize(900, 100);
        pane.doLayout();
        double total = 0;
        for (Node n : ((Split) model(pane)).getChildren()) {
            if (!(n instanceof Divider))
                total += n.getWeight();
        }
        assertTrue(total <= 1.0, "weights add up to " + total);
    }
}
