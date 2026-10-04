/*
 * SidebarTagTree.java
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

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.function.ToIntFunction;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Keys;

/**
 * A buffer's tags as an outline: each tag under the nearest one before it
 * at a lower level, as a document's headings nest. The mode says what level
 * a tag is at. It follows the caret, and going to a tag is a click or Enter.
 */
public class SidebarTagTree extends SidebarTree implements NavigationComponent,
    KeyListener, MouseListener {
    private final Editor editor;
    private final ToIntFunction<LocalTag> level;
    private List<LocalTag> tags;

    public SidebarTagTree(Editor editor, ToIntFunction<LocalTag> level) {
        super((TreeModel) null);
        this.editor = editor;
        this.level = level;
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setRootVisible(false);
        setShowsRootHandles(true);
        setCellRenderer(new TreeCellRenderer());
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(this);
        setToolTipText("");
    }

    /** The tree of tags, each under the last before it at a lower level. */
    static DefaultMutableTreeNode buildTree(
        List<LocalTag> tags,
        ToIntFunction<LocalTag> level
    ) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        final Deque<DefaultMutableTreeNode> open = new ArrayDeque<>();
        final Deque<Integer> levels = new ArrayDeque<>();
        for (LocalTag tag : tags) {
            final int n = level.applyAsInt(tag);
            while (!levels.isEmpty() && levels.peek() >= n) {
                levels.pop();
                open.pop();
            }
            final DefaultMutableTreeNode node = new DefaultMutableTreeNode(tag);
            (open.isEmpty() ? root : open.peek()).add(node);
            open.push(node);
            levels.push(n);
        }
        return root;
    }

    @Override
    public void refresh() {
        final Buffer buffer = editor.getBuffer();
        final List<LocalTag> bufferTags = buffer.getTags();
        if (tags != null && tags == bufferTags)
            return; // Nothing to do.
        Background.start("SidebarTagTree.refresh()", () -> refreshInternal(buffer, bufferTags));
    }

    void refreshInternal(Buffer buffer, List<LocalTag> bufferTags) {
        List<LocalTag> newTags = bufferTags;
        if (newTags == null) {
            // The tagger reads the lines: not while they change.
            try {
                buffer.lockRead();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return;
            }
            try {
                newTags = buffer.getTags(true); // Runs the tagger.
            }
            finally {
                buffer.unlockRead();
            }
        }
        if (newTags == null)
            return;
        final List<LocalTag> finalTags = newTags;
        final TreeModel model = new DefaultTreeModel(buildTree(finalTags, level));
        SwingUtilities.invokeLater(() -> {
            // Not if another buffer is shown now: its outline is its own.
            if (editor.getBuffer() != buffer)
                return;
            setModel(model);
            tags = finalTags;
            for (int row = 0; row < getRowCount(); row++)
                expandRow(row);
            updatePosition();
        });
    }

    @Override
    public void updatePosition() {
        final TreeModel model = getModel();
        if (model == null || tags == null)
            return;
        final LocalTag tag = findTag(editor.getDotCopy());
        if (tag == null) {
            clearSelection();
            scrollRowToVisible(0);
            return;
        }
        final DefaultMutableTreeNode node =
            findNode((DefaultMutableTreeNode) model.getRoot(), tag);
        if (node == null)
            return;
        final TreePath path = getSelectionPath();
        if (path == null || path.getLastPathComponent() != node)
            scrollNodeToCenter(node);
    }

    // The last tag at or before pos's line.
    private LocalTag findTag(Position pos) {
        if (pos == null)
            return null;
        final int lineNumber = pos.lineNumber();
        LocalTag found = null;
        for (LocalTag tag : tags) {
            if (tag.lineNumber() > lineNumber)
                break;
            found = tag;
        }
        return found;
    }

    private static DefaultMutableTreeNode findNode(
        DefaultMutableTreeNode root,
        LocalTag tag
    ) {
        final Enumeration<TreeNode> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            final DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() == tag)
                return node;
        }
        return null;
    }

    @Override
    public final String getLabelText() {
        final File file = editor.getBuffer().getFile();
        return file != null ? file.getName() : null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        final LocalTag tag = getTagAtPoint(e.getPoint());
        return tag != null ? tag.getToolTipText() : null;
    }

    private LocalTag getTagAtPoint(Point point) {
        final TreePath path = getPathForLocation(point.x, point.y);
        return path != null ? tagOf(path) : null;
    }

    private static LocalTag tagOf(TreePath path) {
        final Object obj =
            ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return obj instanceof LocalTag localTag ? localTag : null;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        final TreePath path = getSelectionPath();
        tagKeyPressed(editor, e, path != null ? tagOf(path) : null, this::updatePosition);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        tagKeyReleased(editor, e);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        e.consume();
    }

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {
        LocationBar.cancelInput();
        editor.ensureActive();
        final int button = e.getButton();
        final boolean unmodified = Keys.isUnmodified(e);
        if (unmodified && (button == MouseEvent.BUTTON1 || button == MouseEvent.BUTTON2)) {
            final LocalTag tag = getTagAtPoint(e.getPoint());
            if (tag != null)
                tag.gotoTag(editor);
        } else {
            e.consume();
        }
        editor.setFocusToDisplay();
    }

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {
        giveBackFocus(editor);
    }

    private static class TreeCellRenderer extends DefaultTreeCellRenderer {
        private final Color oldBackgroundSelectionColor;

        TreeCellRenderer() {
            oldBackgroundSelectionColor = getBackgroundSelectionColor();
        }

        @Override
        public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                hasFocus
            );
            setForeground(
                selected
                    ? getTextSelectionColor()
                    : getTextNonSelectionColor()
            );
            final Frame frame = Editor.getCurrentFrame();
            if (frame != null && frame.getFocusedComponent() == tree)
                setBackgroundSelectionColor(oldBackgroundSelectionColor);
            else
                setBackgroundSelectionColor(NO_FOCUS_SELECTION_BACKGROUND);
            final Object obj = ((DefaultMutableTreeNode) value).getUserObject();
            if (obj instanceof LocalTag tag) {
                setIcon(tag.getIcon());
                setText(tag.getSidebarText());
            }
            return this;
        }

        @Override
        public void paintComponent(Graphics g) {
            Display.setRenderingHints(g);
            super.paintComponent(g);
        }
    }
}
