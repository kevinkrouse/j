/*
 * EditorPane.java
 *
 * Copyright (C) 2009 Kevin Krouse
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

import java.awt.Component;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jdesktop.swingx.JXMultiSplitPane;
import org.jdesktop.swingx.MultiSplitLayout;
import org.jdesktop.swingx.MultiSplitLayout.Divider;
import org.jdesktop.swingx.MultiSplitLayout.Leaf;
import org.jdesktop.swingx.MultiSplitLayout.Node;
import org.jdesktop.swingx.MultiSplitLayout.Split;

public class EditorPane extends JXMultiSplitPane {
    /** The transient panel's share of the height. */
    private static final double PANEL_WEIGHT = 0.3;

    /**
     * The transient panel's leaf, or null. When there is one the model is a
     * column of the windows' tree over it.
     */
    private Leaf panel;

    public EditorPane(Editor editor) {
        super();
        setContinuousLayout(true);
        setBorder(null);
        // The windows share the space by their weights alone, not by what
        // they show: a directory's hint would otherwise push a divider over,
        // and in a small frame windows that cannot all have their minimum
        // size would be laid over each other. As in vim, they shrink instead.
        getMultiSplitLayout().setLayoutMode(MultiSplitLayout.NO_MIN_SIZE_LAYOUT);

        assert editor.getLayoutLeaf() == null;
        LayoutLeaf leaf = new LayoutLeaf();
        editor.setLayoutLeaf(leaf);
        root(editor);
    }

    // Removes all editors and splits by creating a new model from the editor.
    public void root(Editor editor) {
        assert editor.getLayoutLeaf() != null;
        // Out of the split it was in, or the next split would add to that
        // one, which is no longer on screen.
        editor.getLayoutLeaf().setParent(null);
        panel = null;
        removeAll();
        add(editor, editor.getLayoutLeaf().getName());
        setModel(editor.getLayoutLeaf());
    }

    public void splitHoriz(Editor splitAtEditor, Editor newEditor) {
        split(splitAtEditor, newEditor, false);
    }

    public void splitVert(Editor splitAtEditor, Editor newEditor) {
        split(splitAtEditor, newEditor, true);
    }

    public void split(Editor splitAtEditor, Editor newEditor, boolean vertical) {
        assert checkEditorLeafCount() : "count mismatch before split";
        Leaf leaf = splitAtEditor.getLayoutLeaf();
        Split split = leaf.getParent();
        //Log.debug((vertical ? "v" : "h") + "split on " + leaf.getName());

        Debug.bugIf(newEditor.getLayoutLeaf() != null);
        Leaf newLeaf = new LayoutLeaf();
        newEditor.setLayoutLeaf(newLeaf);

        if (split == null) {
            split = new Split();
            split.setRowLayout(vertical);
            split.setChildren(leaf, new Divider(), newLeaf);
            setModel(split);
        } else {
            List<Node> children = split.getChildren();
            int index = children.indexOf(leaf);

            // The panel's column is not a row of windows to add to.
            if (split != panelSplit() && (vertical == split.isRowLayout() || children.size() == 1)) {
                children.add(index + 1, new Divider());
                children.add(index + 2, newLeaf);
                split.setRowLayout(vertical);
                split.setChildren(children);
            } else {
                Split newSplit = new Split();
                children.set(index, newSplit);
                split.setChildren(children);
                newSplit.setRowLayout(vertical);
                newSplit.setChildren(Arrays.asList(leaf, new Divider(), newLeaf));
            }
        }

        evenOut();

        // Editor is bound to the LayoutLeaf the unique leaf name.
        add(newEditor, newLeaf.getName());

        assert checkEditorLeafCount() : "count mismatch after split";
        revalidate();
    }

    public void unsplit(Editor editor) {
        assert checkEditorLeafCount() : "count mismatch before unsplit";
        Leaf leaf = editor.getLayoutLeaf();
        Split split = leaf.getParent();
        //Log.debug("unsplit on " + leaf.getName());

        assert split != null : "Can't unsplit last Editor";

        getMultiSplitLayout().removeLayoutNode(leaf.getName());
        // The panel goes with its leaf, or is all that is left.
        if (leaf == panel || getMultiSplitLayout().getModel() == panel)
            panel = null;
        evenOut();
        remove(editor);
        editor.setLayoutLeaf(null);

        assert checkEditorLeafCount() : "count mismatch after unsplit";
        revalidate();
    }

    /**
     * Puts a window along the bottom, across all the others, for transient
     * buffers.
     */
    public void openPanel(Editor editor) {
        Debug.bugIf(panel != null);
        Debug.bugIf(editor.getLayoutLeaf() != null);
        final Leaf leaf = new LayoutLeaf();
        editor.setLayoutLeaf(leaf);
        final Split split = new Split();
        split.setRowLayout(false);
        split.setChildren(getMultiSplitLayout().getModel(), new Divider(), leaf);
        panel = leaf;
        setModel(split);
        evenOut();
        add(editor, leaf.getName());
        assert checkEditorLeafCount() : "count mismatch after opening the panel";
        revalidate();
    }

    /** The panel goes on as an ordinary window, along the bottom. */
    public void releasePanel() {
        panel = null;
        evenOut();
        revalidate();
    }

    public boolean isPanel(Editor editor) {
        return panel != null && editor.getLayoutLeaf() == panel;
    }

    private Split panelSplit() {
        return panel == null ? null : panel.getParent();
    }

    /**
     * The editor that takes an editor's place when it closes, as vim gives
     * the space to: the next window in its row or column, or the one before
     * when it is the last. A split there gives its first editor, the top
     * left one, as nvim does whatever the caret.
     */
    public Editor successor(Editor editor) {
        final Split split = editor.getLayoutLeaf().getParent();
        if (split == null)
            return null;
        final List<Node> children = split.getChildren();
        final int index = children.indexOf(editor.getLayoutLeaf());
        // Leaves and splits alternate with dividers.
        final boolean last = index + 2 >= children.size();
        Node node = children.get(last ? index - 2 : index + 2);
        while (node instanceof Split)
            node = ((Split) node).getChildren().get(0);
        return (Editor) getMultiSplitLayout().getComponentForNode(node);
    }

    /**
     * Makes every window in each row or column the same size again, as
     * after a split, however the dividers have been dragged since.
     */
    public void balance() {
        evenOut();
        revalidate();
        repaint();
    }

    /**
     * Every window in each row or column the same size, as vim's
     * equalalways keeps them after a split or a close. Until a divider is
     * dragged the layout follows the weights; the drag turns floating
     * dividers off for good (JXMultiSplitPane never turns them back on),
     * after which it keeps the dividers where they are, and a new one has
     * no place yet, so they are all put back here.
     */
    private void evenOut() {
        adjustWeights();
        final MultiSplitLayout layout = getMultiSplitLayout();
        if (!layout.getFloatingDividers()) {
            final Insets insets = getInsets();
            place(
                layout.getModel(),
                new Rectangle(insets.left,
                        insets.top,
                        getWidth() - insets.left - insets.right,
                        getHeight() - insets.top - insets.bottom));
        }
    }

    /**
     * After a divider is dragged the layout keeps every divider where it is,
     * which leaves them outside the windows' space when the frame shrinks:
     * the windows would overlap. On a resize they are put where the same
     * shares of the new space are.
     */
    @Override
    public void doLayout() {
        final MultiSplitLayout layout = getMultiSplitLayout();
        final Node model = layout.getModel();
        if (!layout.getFloatingDividers() && model != null) {
            final Insets insets = getInsets();
            final Rectangle now = new Rectangle(insets.left,
                    insets.top,
                    getWidth() - insets.left - insets.right,
                    getHeight() - insets.top - insets.bottom);
            // Nothing moved since the last fit: an ordinary layout.
            if (fitted != null && fitted.equals(snapshot(model, now))) {
                super.doLayout();
                return;
            }
            final Rectangle was = model.getBounds();
            if (was.width > 0 && was.height > 0 && now.width > 0 && now.height > 0 && !now.equals(was))
                scale(model, was, now);
            // A divider dragged past the windows beyond it leaves theirs
            // behind: the layout honors the drag, then each split's windows
            // are fitted to their space by their shares, and laid out again.
            super.doLayout();
            if (now.width > 0 && now.height > 0) {
                fit(model, now);
                super.doLayout();
            }
            fitted = snapshot(model, now);
            return;
        }
        super.doLayout();
    }

    /**
     * Bounds for node, its windows sharing them as they share what they have
     * now, but none smaller than a window can be used at -- two lines high,
     * ten characters wide -- where there is room: a drag past that stops there,
     * as vim's winminheight has it.
     */
    private void fit(Node node, Rectangle bounds) {
        node.setBounds(bounds);
        if (!(node instanceof Split split))
            return;
        final boolean row = split.isRowLayout();
        final int divider = getDividerSize();
        final List<Node> windows = new ArrayList<>();
        for (Node child : split.getChildren()) {
            if (!(child instanceof Divider))
                windows.add(child);
        }
        final int n = windows.size();
        final int total = Math.max(0, (row ? bounds.width : bounds.height) - divider * (n - 1));
        // Each one's floor: the smallest window, times the windows stacked
        // along the split in it; less for all where there is no room.
        int stacked = 0;
        for (Node w : windows)
            stacked += stacked(w, row);
        final int smallest = row ? Display.getCharWidth() * 10 : Display.getCharHeight() * 2;
        final int unit = Math.min(smallest, total / Math.max(1, stacked));
        final int[] floor = new int[n];
        final long[] share = new long[n];
        for (int i = 0; i < n; i++) {
            floor[i] = unit * stacked(windows.get(i), row);
            share[i] = share(windows.get(i), row);
        }
        // By their shares; one that would be under its floor gets the floor,
        // and the rest share what is left, until none is under.
        final int[] size = new int[n];
        final boolean[] floored = new boolean[n];
        for (int pass = 0; pass < n; pass++) {
            int free = total;
            long shares = 0;
            for (int i = 0; i < n; i++) {
                if (floored[i])
                    free -= floor[i];
                else
                    shares += share[i];
            }
            boolean under = false;
            long before = 0;
            for (int i = 0; i < n; i++) {
                if (floored[i]) {
                    size[i] = floor[i];
                    continue;
                }
                size[i] = (int) ((before + share[i]) * free / shares - before * free / shares);
                before += share[i];
                if (size[i] < floor[i]) {
                    floored[i] = true;
                    under = true;
                }
            }
            if (!under)
                break;
        }
        int at = row ? bounds.x : bounds.y;
        int k = 0;
        for (Node child : split.getChildren()) {
            final int length = child instanceof Divider ? divider : size[k++];
            final Rectangle r = row
                    ? new Rectangle(at, bounds.y, length, bounds.height)
                    : new Rectangle(bounds.x, at, bounds.width, length);
            if (child instanceof Divider)
                child.setBounds(r);
            else
                fit(child, r);
            at += length;
        }
    }

    // The windows one after another along a split in node: a split the same
    // way adds its children's, one across takes the most of any child's.
    private static int stacked(Node node, boolean row) {
        if (!(node instanceof Split split))
            return 1;
        int n = 0;
        for (Node child : split.getChildren()) {
            if (child instanceof Divider)
                continue;
            n = split.isRowLayout() == row ? n + stacked(child, row) : Math.max(n, stacked(child, row));
        }
        return Math.max(1, n);
    }

    // A node's size along a split, at least 1, so one squeezed to nothing still has a share.
    private static long share(Node node, boolean row) {
        final Rectangle r = node.getBounds();
        return Math.max(1, row ? r.width : r.height);
    }

    // The pane's space and every divider's place when the windows were last
    // fitted to them; null before.
    private List<Rectangle> fitted;

    private static List<Rectangle> snapshot(Node model, Rectangle space) {
        final List<Rectangle> rects = new ArrayList<>();
        rects.add(space);
        dividers(model, rects);
        return rects;
    }

    private static void dividers(Node node, List<Rectangle> out) {
        if (node instanceof Divider)
            out.add(node.getBounds());
        else if (node instanceof Split split) {
            for (Node child : split.getChildren())
                dividers(child, out);
        }
    }

    // Each node's bounds in to as they were in from, by the same shares.
    private static void scale(Node node, Rectangle from, Rectangle to) {
        final Rectangle r = node.getBounds();
        final Rectangle scaled = new Rectangle(to.x + (int) Math.round((r.x - from.x) * (double) to.width / from.width),
                to.y + (int) Math.round((r.y - from.y) * (double) to.height / from.height),
                (int) Math.round(r.width * (double) to.width / from.width),
                (int) Math.round(r.height * (double) to.height / from.height));
        if (node instanceof Split split) {
            for (Node child : split.getChildren()) {
                // A divider keeps its thickness, only moving.
                if (child instanceof Divider) {
                    final Rectangle d = child.getBounds();
                    final Rectangle moved = new Rectangle(
                            to.x + (int) Math.round((d.x - from.x) * (double) to.width / from.width),
                            to.y + (int) Math.round((d.y - from.y) * (double) to.height / from.height),
                            split.isRowLayout() ? d.width : (int) Math.round(d.width * (double) to.width / from.width),
                            split.isRowLayout()
                                    ? (int) Math.round(d.height * (double) to.height / from.height)
                                    : d.height);
                    child.setBounds(moved);
                } else
                    scale(child, from, to);
            }
        }
        node.setBounds(scaled);
    }

    /** Shares the space out evenly, row or column, all the way down. */
    private void place(Node node, Rectangle bounds) {
        node.setBounds(bounds);
        if (!(node instanceof Split split))
            return;
        final boolean row = split.isRowLayout();
        final int divider = getDividerSize();
        final List<Node> children = split.getChildren();
        final int count = (children.size() + 1) / 2;
        final int total = (row ? bounds.width : bounds.height) - divider * (count - 1);
        final int panelSize = split == panelSplit() ? (int) (total * PANEL_WEIGHT) : 0;
        int at = row ? bounds.x : bounds.y;
        int i = 0;
        for (Node child : children) {
            final int size;
            if (child instanceof Divider)
                size = divider;
            else if (split == panelSplit())
                size = child == panel ? panelSize : total - panelSize;
            else
                size = total * (i + 1) / count - total * i++ / count;
            final Rectangle r = row
                    ? new Rectangle(at, bounds.y, size, bounds.height)
                    : new Rectangle(bounds.x, at, bounds.width, size);
            if (child instanceof Divider)
                child.setBounds(r);
            else
                place(child, r);
            at += size;
        }
    }

    // Get the list of Editor siblings (other Editors in the same row or column) including the argument editor.
    public List<Editor> getSiblings(Editor editor) {
        MultiSplitLayout.Split split = editor.getLayoutLeaf().getParent();
        if (split == null)
            return Collections.emptyList();

        MultiSplitLayout layout = getMultiSplitLayout();
        List<Editor> editors = new ArrayList<>();
        for (MultiSplitLayout.Node n : split.getChildren()) {
            if (n instanceof MultiSplitLayout.Leaf && n != panel) {
                Editor ed = (Editor) layout.getComponentForNode(n);
                editors.add(ed);
            }
        }

        return editors;
    }

    void adjustWeights() {
        Node model = getMultiSplitLayout().getModel();
        if (model instanceof Leaf)
            model.setWeight(1.0);
        else if (model == panelSplit()) {
            // The windows' tree over the panel, which keeps its share.
            final Node windows = ((Split) model).getChildren().get(0);
            windows.setWeight(1 - PANEL_WEIGHT);
            panel.setWeight(PANEL_WEIGHT);
            if (windows instanceof Split split)
                adjustWeights(split.getChildren());
        } else if (model instanceof Split split)
            adjustWeights(split.getChildren());
    }

    // evenly distribute weights among nodes, skipping dividers
    void adjustWeights(List<Node> children) {
        Debug.bugIfNot(
            children.size() % 2 == 1,
            "Expect odd number of leaves and splits; each leaf or split should be separated by divider.");
        int count = (1 + children.size()) / 2;

        // 1/count each, the last what is left: count of them can add up to a
        // hair over 1, which the layout refuses.
        double weight = 1 / (double) count;
        double left = 1;
        int i = 0;
        for (Node n : children) {
            if (n instanceof Leaf || n instanceof Split)
                n.setWeight(++i == count ? Math.max(0, left) : weight);
            left -= n instanceof Leaf || n instanceof Split ? weight : 0;
            if (n instanceof Split split)
                adjustWeights(split.getChildren());
        }
    }

    boolean checkEditorLeafCount() {
        List<Component> editors = new ArrayList<>();
        for (Component c : getComponents())
            if (c instanceof Editor)
                editors.add(c);

        MultiSplitLayout layout = getMultiSplitLayout();
        int leafCount = leafCount(layout.getModel());

        int editorCount = editors.size();
        if (editorCount != leafCount) {
            Log.debug("  editor count = " + editorCount + ", leaf count = " + leafCount);
            for (Component c : editors)
                Log.debug("  > " + c.toString());
        }

        return editorCount == leafCount;
    }

    int leafCount(Node n) {
        if (n instanceof Leaf)
            return 1;
        if (n instanceof Split split) {
            int count = 0;
            for (Node child : split.getChildren())
                count += leafCount(child);
            return count;
        }
        return 0;
    }

    private static class LayoutLeaf extends Leaf {
        LayoutLeaf() {
            super();
            setName("EditorPane.LayoutLeaf-" + hashCode());
        }
    }

}
