/*
 * TreeOpenState.java
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

package org.armedbear.j.vcs.status;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/**
 * Which nodes of a sidebar tree rebuilt from each read were left open or
 * closed, by key, so a rebuild opens them as they were: those never
 * opened nor closed as openByDefault says.
 */
final class TreeOpenState {
    private final JTree tree;
    private final Map<String, Boolean> open;
    // A node's key, null for one not kept.
    private final Function<TreePath, String> key;
    // Whether a node, by its user object, is open to begin with.
    private final Predicate<Object> openByDefault;

    /** Kept in open, which may outlive tree, as each node opens and closes. */
    TreeOpenState(
            JTree tree,
            Map<String, Boolean> open,
            Function<TreePath, String> key,
            Predicate<Object> openByDefault) {
        this.tree = tree;
        this.open = open;
        this.key = key;
        this.openByDefault = openByDefault;
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                remember(e.getPath(), true);
                restore(e.getPath());
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                remember(e.getPath(), false);
            }
        });
    }

    private void remember(TreePath path, boolean expanded) {
        final String k = key.apply(path);
        if (k != null && !k.isEmpty())
            open.put(k, expanded);
    }

    /** The nodes in the open one at path opened as they were left, or to begin with; those in each as it opens. */
    void restore(TreePath path) {
        final DefaultMutableTreeNode n = (DefaultMutableTreeNode) path.getLastPathComponent();
        for (int i = 0; i < n.getChildCount(); i++) {
            final DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
            if (c.isLeaf())
                continue;
            final TreePath p = path.pathByAddingChild(c);
            final Boolean was = open.get(key.apply(p));
            if (was != null ? was : openByDefault.test(c.getUserObject()))
                tree.expandPath(p);
        }
    }
}
