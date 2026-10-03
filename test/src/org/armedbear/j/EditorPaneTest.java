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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.List;
import org.jdesktop.swingx.MultiSplitLayout;
import org.jdesktop.swingx.MultiSplitLayout.Divider;
import org.jdesktop.swingx.MultiSplitLayout.Node;
import org.jdesktop.swingx.MultiSplitLayout.Split;
import org.junit.Test;

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
        ((Divider) ((Split) model(pane)).getChildren().get(1)).setBounds(
            new java.awt.Rectangle(10, 0, pane.getDividerSize(), 100)
        );
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
        ((Divider) ((Split) model(pane)).getChildren().get(1)).setBounds(
            new java.awt.Rectangle(10, 0, pane.getDividerSize(), 100)
        );
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
        ((Divider) children.get(1)).setBounds(
            new java.awt.Rectangle(0, 10, 100, pane.getDividerSize())
        );
        pane.balance();
        layout.layoutContainer(pane);
        final int size = pane.getDividerSize();
        final int each = (308 - 2 * size) / 3;
        assertEquals(each, children.get(0).getBounds().height);
        assertEquals(each, children.get(2).getBounds().height);
        assertEquals(each, children.get(4).getBounds().height);
    }
}
