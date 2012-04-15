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

import org.jdesktop.swingx.JXMultiSplitPane;
import org.jdesktop.swingx.MultiSplitLayout;
import org.jdesktop.swingx.MultiSplitLayout.Divider;
import org.jdesktop.swingx.MultiSplitLayout.Leaf;
import org.jdesktop.swingx.MultiSplitLayout.Node;
import org.jdesktop.swingx.MultiSplitLayout.Split;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EditorPane extends JXMultiSplitPane {

    public EditorPane(Editor editor) {
        super();
        setContinuousLayout(true);
        setBorder(null);

        assert editor.getLayoutLeaf() == null;
        LayoutLeaf leaf = new LayoutLeaf();
        editor.setLayoutLeaf(leaf);
        root(editor);
    }

    // Removes all editors and splits by creating a new model from the editor.
    public void root(Editor editor)
    {
        assert editor.getLayoutLeaf() != null;
        removeAll();
        add(editor, editor.getLayoutLeaf().getName());
        setModel(editor.getLayoutLeaf());
    }

    public void splitHoriz(Editor splitAtEditor, Editor newEditor)
    {
        split(splitAtEditor, newEditor, false);
    }

    public void splitVert(Editor splitAtEditor, Editor newEditor)
    {
        split(splitAtEditor, newEditor, true);
    }

    public void split(Editor splitAtEditor, Editor newEditor, boolean vertical)
    {
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

            if (vertical == split.isRowLayout() || children.size() == 1) {
                children.add(index+1, new Divider());
                children.add(index+2, newLeaf);
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

        adjustWeights();

        // Editor is bound to the LayoutLeaf the unique leaf name.
        add(newEditor, newLeaf.getName());

        assert checkEditorLeafCount() : "count mismatch after split";
        revalidate();
    }

    public void unsplit(Editor editor)
    {
        assert checkEditorLeafCount() : "count mismatch before unsplit";
        Leaf leaf = editor.getLayoutLeaf();
        Split split = leaf.getParent();
        //Log.debug("unsplit on " + leaf.getName());

        assert split != null : "Can't unsplit last Editor";

        getMultiSplitLayout().removeLayoutNode(leaf.getName());
        adjustWeights();
        remove(editor);
        editor.setLayoutLeaf(null);

        assert checkEditorLeafCount() : "count mismatch after unsplit";
        revalidate();
    }

    // Get the list of Editor siblings (other Editors in the same row or column) including the argument editor.
    public List<Editor> getSiblings(Editor editor)
    {
        MultiSplitLayout.Split split = editor.getLayoutLeaf().getParent();
        if (split == null)
            return Collections.emptyList();

        MultiSplitLayout layout = getMultiSplitLayout();
        List<Editor> editors = new ArrayList<Editor>();
        for (MultiSplitLayout.Node n : split.getChildren()) {
            if (n instanceof MultiSplitLayout.Leaf) {
                Editor ed = (Editor)layout.getComponentForNode(n);
                editors.add(ed);
            }
        }

        return editors;
    }

    void adjustWeights() {
        Node model = getMultiSplitLayout().getModel();
        if (model instanceof Leaf)
            model.setWeight(1.0);
        else if (model instanceof Split)
            adjustWeights(((Split)model).getChildren());
    }

    // evenly distribute weights among nodes, skipping dividers
    void adjustWeights(List<Node> children) {
        Debug.bugIfNot(children.size() % 2 == 1, "Expect odd number of leaves and splits; each leaf or split should be separated by divider.");
        int count = (1+children.size()) / 2;

        double weight = 1 / (double)count;
        for (Node n : children) {
            if (n instanceof Leaf || n instanceof Split)
                n.setWeight(weight);
            if (n instanceof Split)
                adjustWeights(((Split)n).getChildren());
        }
    }

    boolean checkEditorLeafCount() {
        List<Component> editors = new ArrayList<Component>();
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

//        dumpModel();
        
        return editorCount == leafCount;
    }

    void dumpModel() {
        System.out.println("model:");
        MultiSplitLayout.printModel(getMultiSplitLayout().getModel());
        System.out.println();
    }

    int leafCount(Node n)
    {
        if (n instanceof Leaf)
            return 1;
        if (n instanceof Split)
        {
            int count = 0;
            for (Node child : ((Split)n).getChildren())
                count += leafCount(child);
            return count;
        }
        return 0;
    }

    private static class LayoutLeaf extends Leaf
    {
        LayoutLeaf()
        {
            super();
            setName("EditorPane.LayoutLeaf-" + hashCode());
        }
    }

}
