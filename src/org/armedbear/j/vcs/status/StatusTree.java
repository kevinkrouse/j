/*
 * StatusTree.java
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

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.FileIcons;
import org.armedbear.j.Frame;
import org.armedbear.j.LocationBar;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.SidebarTree;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.StatusData.CommitItem;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusData.StashItem;

/**
 * A status's sections in the sidebar's lower half: each with its files
 * under their directories, a directory with one directory in it and no
 * files shown as one, or its commits or stashes; the untracked, unstaged
 * and staged files and the commits between branches closed to begin with;
 * a node per repository when there are several. A click goes to the
 * section, file, commit or stash in the status, or a directory's first
 * file, opening a closed section there; Enter or a double click visits a
 * file or shows a commit's or stash's details. What the caret is
 * on in the status is selected, as a tag is in a file's mode. One per
 * frame the status is in, a component having one parent.
 */
public final class StatusTree extends SidebarTree implements NavigationComponent, KeyListener, MouseListener {
    /** A repository, for several. */
    record Repository(String name, String key) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A section: its id, title and how many it has, keyed as the status's line. */
    record SectionNode(String id, String title, int count, String key) {
        @Override
        public String toString() {
            return title + " (" + count + ")";
        }
    }

    /** A directory: its name, its first file's key, and its section's key. */
    record DirNode(String name, String key, String first, String section) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A file: its letter and path, keyed as the status's line, and its section's key. */
    record FileNode(char letter, String path, String key, String section) {
        @Override
        public String toString() {
            return letter + "  " + path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** A commit or stash, keyed as the status's line, and its section's key. */
    record CommitNode(String text, String key, String section) {
        @Override
        public String toString() {
            return text;
        }
    }

    // The sections closed to begin with.
    private static boolean closed(String id) {
        return id.equals("untracked") || id.equals("unstaged") || id.equals("staged") || StatusData.RANGES.contains(id);
    }

    private final VcsStatusBuffer status;
    // The frame whose sidebar it is in; null in tests.
    private final Frame frame;
    // What it shows.
    private List<Part> parts = List.of();
    // Whether each node was left open or closed, by key, across rebuilds.
    private final TreeOpenState open;

    StatusTree(VcsStatusBuffer status, Frame frame) {
        super((TreeModel) null);
        this.status = status;
        this.frame = frame;
        // Not opened around the caret's node: the one shown around it is selected instead.
        setExpandsSelectedPaths(false);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setRootVisible(false);
        setShowsRootHandles(true);
        setCellRenderer(new Renderer());
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(this);
        open = new TreeOpenState(this,
                new HashMap<>(),
                StatusTree::key,
                o -> !(o instanceof SectionNode s && closed(s.id())));
        setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
    }

    /** One repository's sections, as read. */
    record Part(VcsRepository repository, String name, List<Section> sections) {}

    /** The tree of parts: one's sections at the top, several each under its own name. */
    static DefaultMutableTreeNode build(List<Part> parts) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (Part p : parts) {
            final String rk = p.repository().root().toString();
            final DefaultMutableTreeNode under =
                    parts.size() > 1 ? new DefaultMutableTreeNode(new Repository(p.name(), rk)) : root;
            for (Section s : p.sections()) {
                final String sk = rk + "|" + s.id();
                final DefaultMutableTreeNode section =
                        new DefaultMutableTreeNode(new SectionNode(s.id(), s.title(), s.total(), sk));
                final Dir files = new Dir();
                for (Item i : s.items())
                    switch (i) {
                        case FileItem f -> files.add(f.path(), f);
                        case CommitItem c -> {
                            section.add(new DefaultMutableTreeNode(new CommitNode(c.text(), sk + "|" + c.hash(), sk)));
                        }
                        case StashItem st -> section.add(
                            new DefaultMutableTreeNode(
                                    new CommitNode(st.name() + ": " + st.subject(), sk + "|" + st.hash(), sk)));
                    }
                files.addTo(section, "", sk);
                under.add(section);
            }
            if (under != root)
                root.add(under);
        }
        return root;
    }

    // A directory's files and directories, by name.
    private static final class Dir {
        final Map<String, Dir> dirs = new TreeMap<>();
        final List<FileItem> files = new ArrayList<>();

        void add(String rest, FileItem f) {
            final int slash = rest.indexOf('/');
            if (slash < 0)
                files.add(f);
            else
                dirs.computeIfAbsent(rest.substring(0, slash), k -> new Dir()).add(rest.substring(slash + 1), f);
        }

        // Its directories, then its files, under node; path its own, "" or ending in '/'.
        void addTo(DefaultMutableTreeNode node, String path, String sk) {
            for (Map.Entry<String, Dir> e : dirs.entrySet()) {
                String name = e.getKey();
                Dir d = e.getValue();
                while (d.files.isEmpty() && d.dirs.size() == 1) {
                    final Map.Entry<String, Dir> only = d.dirs.entrySet().iterator().next();
                    name += "/" + only.getKey();
                    d = only.getValue();
                }
                final String dir = path + name + "/";
                final DefaultMutableTreeNode child =
                        new DefaultMutableTreeNode(new DirNode(name, sk + "|" + dir, sk + "|" + d.first().path(), sk));
                d.addTo(child, dir, sk);
                node.add(child);
            }
            for (FileItem f : files)
                node.add(new DefaultMutableTreeNode(new FileNode(f.letter(), f.path(), sk + "|" + f.path(), sk)));
        }

        // The first file shown in it.
        FileItem first() {
            return dirs.isEmpty() ? files.get(0) : dirs.values().iterator().next().first();
        }
    }

    /** Shows the status as last read, what was open staying, and selects what the caret is on. */
    void update() {
        final List<Part> parts = new ArrayList<>();
        for (VcsRepository r : status.getRepositories()) {
            final StatusData d = status.getData(r);
            if (d != null)
                parts.add(new Part(r, r.name(), d.sections()));
        }
        // Nothing new, as after a section opens in the status: left as it is.
        if (parts.equals(this.parts)) {
            updatePosition();
            return;
        }
        this.parts = List.copyOf(parts);
        final DefaultMutableTreeNode root = build(parts);
        setModel(new DefaultTreeModel(root));
        open.restore(new TreePath(root));
        updatePosition();
    }

    // A node's key: the status line's, or a repository's root.
    private static String key(TreePath path) {
        return switch (((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()) {
            case Repository r -> r.key();
            case SectionNode s -> s.key();
            case DirNode d -> d.key();
            case FileNode f -> f.key();
            case CommitNode c -> c.key();
            case null, default -> "";
        };
    }

    // Rebuilt as the status is shown again.
    @Override
    public void refresh() {}

    /**
     * The node of what the caret is on in this frame's window on the status
     * selected, or the one around it that is shown.
     */
    @Override
    public void updatePosition() {
        final Editor ed = frame != null ? frame.getCurrentEditor() : Editor.windowShowing(status);
        if (ed == null || ed.getBuffer() != status || !(ed.getDotLine() instanceof StatusLine line))
            return;
        TreePath best = null;
        int length = -1;
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final TreePath path = new TreePath(((DefaultMutableTreeNode) nodes.nextElement()).getPath());
            final String key = key(path);
            if (!key.isEmpty()
                    && key.length() > length
                    && (line.key().equals(key) || line.key().startsWith(key + "|"))) {
                best = path;
                length = key.length();
            }
        }
        while (best != null && best.getParentPath() != null && !isVisible(best))
            best = best.getParentPath();
        if (best == null || best.getPathCount() < 2) {
            clearSelection();
            return;
        }
        if (!best.equals(getSelectionPath())) {
            setSelectionPath(best);
            // Up or down alone: a long commit's row would scroll the tree
            // sideways, hiding its folders.
            final Rectangle bounds = getPathBounds(best);
            if (bounds != null)
                scrollRectToVisible(new Rectangle(getVisibleRect().x, bounds.y, 1, bounds.height));
        }
    }

    @Override
    public String getLabelText() {
        return "Status";
    }

    // Acting on a node.

    // The status's caret to it; a file visited too, with visit.
    private void go(Object node, boolean visit) {
        switch (node) {
            case SectionNode s -> status.reveal(frame, s.key(), null);
            case DirNode d -> status.reveal(frame, d.section(), d.first());
            case FileNode f -> visit(f.section(), f.key(), visit);
            case CommitNode c -> visit(c.section(), c.key(), visit);
            case null, default -> {}
        }
    }

    private void visit(String section, String key, boolean visit) {
        final Editor ed = status.reveal(frame, section, key);
        if (visit && ed != null)
            VcsStatusMode.vcsStatusVisit(ed);
    }

    private Object selected() {
        final TreePath path = getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        sidebarKeyPressed(Editor.currentEditor(), e, () -> go(selected(), true), this::updatePosition);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        tagKeyReleased(Editor.currentEditor(), e);
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || !Keys.isUnmodified(e))
            return;
        if (Editor.currentEditor().getFrame() != null)
            LocationBar.cancelInput();
        final TreePath path = getPathForLocation(e.getX(), e.getY());
        if (path == null)
            return;
        final Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (e.getClickCount() <= 2)
            go(node, e.getClickCount() == 2);
    }

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    // A file with its file type's icon, a commit or stash with the commit's;
    // a section, directory or repository with the file tree's folders.
    private static final class Renderer extends DefaultTreeCellRenderer {
        private final Color focusedSelection = getBackgroundSelectionColor();

        private final Icon commit = Icons.getIconFromFile("commit");

        Renderer() {
            setOpenIcon(Icons.getIconFromFile("dir_open"));
            setClosedIcon(Icons.getIconFromFile("dir_close"));
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            final Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof FileNode f)
                setIcon(FileIcons.getIcon(f.path(), null));
            else if (o instanceof CommitNode)
                setIcon(commit);
            else if (o instanceof SectionNode || o instanceof DirNode)
                setIcon(expanded ? getOpenIcon() : getClosedIcon());
            final Frame frame = Editor.getCurrentFrame();
            setBackgroundSelectionColor(
                frame != null && frame.getFocusedComponent() == tree
                        ? focusedSelection
                        : NO_FOCUS_SELECTION_BACKGROUND);
            return this;
        }

        @Override
        public void paintComponent(Graphics g) {
            Display.setRenderingHints(g);
            super.paintComponent(g);
        }
    }
}
