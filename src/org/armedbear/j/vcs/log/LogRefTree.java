/*
 * LogRefTree.java
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

package org.armedbear.j.vcs.log;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.armedbear.j.Frame;
import org.armedbear.j.LocationBar;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.Sidebar;
import org.armedbear.j.SidebarTree;
import org.armedbear.j.transients.FilterBar;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;
import org.armedbear.j.vcs.VcsRepository;

/**
 * A log's refs in the sidebar's lower half, one tree for its list, files
 * and diff: HEAD when detached, the branches, the remotes by remote and the
 * tags, newest first and the newest {@value #TAGS} until more are asked
 * for; a node per repository when there are several. A name with a slash
 * is in a folder. A click goes to the ref's commit in the log; Enter or a
 * double click filters the log to it. Typing narrows the tree. One per frame
 * the log is in, a component having one parent; the refs they show are
 * {@link LogNames}', read once for all of them and the log's filters.
 */
public final class LogRefTree extends SidebarTree implements NavigationComponent, KeyListener, MouseListener {
    /** The tags shown until more are asked for. */
    static final int TAGS = 25;

    /** A repository's refs: its name, for several, and its tips. */
    record Part(VcsRepository repository, String name, List<LogRefTip> tips) {}

    /** A folder: a repository, a section, a remote or a name's part before a slash. */
    record Folder(String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A ref, shown by the last part of its name. */
    record Tip(VcsRepository repository, LogRefTip tip, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** The tags left out, asked for by a click or Enter. */
    record More(int count) {
        @Override
        public String toString() {
            return "… " + count + " more";
        }
    }

    private final LogBuffer log;
    private List<Part> parts = List.of();
    private String filter = "";
    private boolean allTags;
    // What was open before typing narrowed it, back when the narrowing goes.
    private Set<String> openBefore;

    LogRefTree(LogBuffer log) {
        super((TreeModel) null);
        this.log = log;
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setRootVisible(false);
        setShowsRootHandles(true);
        setCellRenderer(new Renderer());
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(this);
        setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
    }

    /**
     * The tree of parts, narrowed to the refs whose names have filter in
     * them, ignoring case: one part's sections at the top, several each
     * under its own name. The branches are the default branch, then the one
     * checked out, then the rest by name, or byDate the newest commit first,
     * a folder where its newest is. The tags past
     * {@value #TAGS} are a "more" node unless all are asked for or a filter
     * is set.
     */
    static DefaultMutableTreeNode build(List<Part> parts, String filter, boolean allTags, boolean byDate) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (Part p : parts) {
            final DefaultMutableTreeNode under =
                    parts.size() > 1 ? new DefaultMutableTreeNode(new Folder(p.name())) : root;
            sections(under, p, filter.toLowerCase(Locale.ROOT), allTags || !filter.isEmpty(), byDate);
            if (under != root && under.getChildCount() > 0)
                root.add(under);
        }
        return root;
    }

    private static void sections(DefaultMutableTreeNode under, Part p, String filter, boolean allTags, boolean byDate) {
        final List<LogRefTip> heads = new ArrayList<>();
        final List<LogRefTip> branches = new ArrayList<>();
        final List<LogRefTip> remotes = new ArrayList<>();
        final List<LogRefTip> tags = new ArrayList<>();
        for (LogRefTip t : p.tips()) {
            if (!t.ref().name().toLowerCase(Locale.ROOT).contains(filter))
                continue;
            switch (t.ref().kind()) {
                case HEAD -> heads.add(t);
                case BRANCH -> branches.add(t);
                case REMOTE -> remotes.add(t);
                case TAG -> tags.add(t);
            }
        }
        for (LogRefTip t : heads)
            under.add(new DefaultMutableTreeNode(new Tip(p.repository(), t, "HEAD")));
        // A folder is made where its first is, so by date it is where its newest is.
        final Comparator<LogRefTip> order = byDate
                ? Comparator.comparingLong(LogRefTip::time).reversed().thenComparing(LogRefTree::byName)
                : (Comparator<LogRefTip>) LogRefTree::byName;
        branches.sort(order);
        remotes.sort(order);
        if (!branches.isEmpty()) {
            final DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Folder("Branches"));
            // The default branch, then the one checked out, first, by their
            // whole names, over any folder; the rest in order.
            final List<LogRefTip> pinned = new ArrayList<>();
            branches.stream().filter(LogRefTip::primary).forEach(pinned::add);
            branches.stream().filter(t -> t.ref().head() && !t.primary()).forEach(pinned::add);
            for (LogRefTip t : pinned)
                section.add(new DefaultMutableTreeNode(new Tip(p.repository(), t, t.ref().name())));
            for (LogRefTip t : branches)
                if (!pinned.contains(t))
                    place(section, p.repository(), t, t.ref().name());
            under.add(section);
        }
        if (!remotes.isEmpty()) {
            final DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Folder("Remotes"));
            // By remote: "origin/feature/x" in origin, then feature.
            for (LogRefTip t : remotes)
                place(section, p.repository(), t, t.ref().name());
            under.add(section);
        }
        if (!tags.isEmpty()) {
            // Newest first, as read; the rest a node of their own.
            final DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Folder("Tags"));
            final int shown = allTags ? tags.size() : Math.min(TAGS, tags.size());
            for (LogRefTip t : tags.subList(0, shown))
                section.add(new DefaultMutableTreeNode(new Tip(p.repository(), t, t.ref().name())));
            if (shown < tags.size())
                section.add(new DefaultMutableTreeNode(new More(tags.size() - shown)));
            under.add(section);
        }
    }

    // By name ignoring case, as a listing is: alpha before Zed.
    private static int byName(LogRefTip a, LogRefTip b) {
        final int c = String.CASE_INSENSITIVE_ORDER.compare(a.ref().name(), b.ref().name());
        return c != 0 ? c : a.ref().name().compareTo(b.ref().name());
    }

    // A ref under its folders, one per part of path before a slash.
    private static void place(DefaultMutableTreeNode section, VcsRepository repository, LogRefTip t, String path) {
        final String[] names = path.split("/");
        DefaultMutableTreeNode at = section;
        for (int i = 0; i < names.length - 1; i++)
            at = folder(at, names[i]);
        at.add(new DefaultMutableTreeNode(new Tip(repository, t, names[names.length - 1])));
    }

    private static DefaultMutableTreeNode folder(DefaultMutableTreeNode parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            final DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() instanceof Folder f && f.name().equals(name))
                return child;
        }
        final DefaultMutableTreeNode child = new DefaultMutableTreeNode(new Folder(name));
        parent.add(child);
        return child;
    }

    // Reading: the log's names, which read the refs for the filters too.

    @Override
    public void refresh() {
        LogNames.read(log.getScope(), false, this::update);
    }

    /** Shows the refs as read now, if they changed; what was open and selected stays. */
    void update() {
        if (log.isDisposed())
            return;
        final LogScope scope = log.getScope();
        final List<Part> read = new ArrayList<>();
        for (VcsRepository r : scope.repositories())
            read.add(new Part(r, scope.name(r), LogNames.of(r).tips()));
        if (read.equals(parts))
            return;
        parts = List.copyOf(read);
        rebuild();
    }

    /** What is shown, for tests: each part's tips. */
    List<Part> parts() {
        return parts;
    }

    /** The text narrowing it, or "". */
    String filter() {
        return filter;
    }

    // The tree again from the parts, the filter and the tags asked for,
    // what was open and selected kept by name: at first the sections; while
    // narrowed, every folder.
    private void rebuild() {
        final boolean first = getRowCount() == 0;
        final Set<String> open = new HashSet<>();
        for (int row = 0; row < getRowCount(); row++)
            if (isExpanded(row))
                open.add(key(getPathForRow(row)));
        final String selected = getSelectionPath() == null ? null : key(getSelectionPath());
        final DefaultMutableTreeNode root = build(parts, filter, allTags, byDate());
        setModel(new DefaultTreeModel(root));
        if (!filter.isEmpty()) {
            for (int row = 0; row < getRowCount(); row++)
                expandRow(row);
            firstRef();
        } else {
            if (first)
                expandSections(root);
            else
                reopen(root, open);
            final TreePath path = selected == null ? null : find(root, selected);
            if (path != null)
                setSelectionPath(path);
        }
        // The label says what narrows it, in each sidebar showing it.
        for (int i = 0; i < Editor.getFrameCount(); i++) {
            final Sidebar sidebar = Editor.getFrame(i).getSidebar();
            if (sidebar != null && sidebar.getBottomComponent() == this)
                sidebar.setBottomLabelText(getLabelText());
        }
    }

    // A node's path by its names, as a rebuild finds it again.
    private static String key(TreePath path) {
        final StringBuilder sb = new StringBuilder();
        for (Object o : path.getPath())
            sb.append(((DefaultMutableTreeNode) o).getUserObject()).append('\0');
        return sb.toString();
    }

    // The folders whose paths were open, open again, outermost first.
    private void reopen(DefaultMutableTreeNode root, Set<String> open) {
        final Enumeration<TreeNode> nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final TreePath path = new TreePath(((DefaultMutableTreeNode) nodes.nextElement()).getPath());
            if (open.contains(key(path)))
                expandPath(path);
        }
    }

    private static TreePath find(DefaultMutableTreeNode root, String key) {
        final Enumeration<TreeNode> nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final TreePath path = new TreePath(((DefaultMutableTreeNode) nodes.nextElement()).getPath());
            if (key(path).equals(key))
                return path;
        }
        return null;
    }

    // Each repository's node and its branches open; the remotes and tags,
    // often many, closed.
    private void expandSections(DefaultMutableTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            final DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (parts.size() > 1) {
                // A repository's: it open, and its sections as one's are.
                expandPath(new TreePath(child.getPath()));
                for (int j = 0; j < child.getChildCount(); j++)
                    openBranches((DefaultMutableTreeNode) child.getChildAt(j));
            } else
                openBranches(child);
        }
    }

    private void openBranches(DefaultMutableTreeNode section) {
        if (section.getUserObject() instanceof Folder f && f.name().equals("Branches"))
            expandPath(new TreePath(section.getPath()));
    }

    // The first row that is a ref, selected.
    private void firstRef() {
        for (int row = 0; row < getRowCount(); row++)
            if (((DefaultMutableTreeNode) getPathForRow(row).getLastPathComponent()).getUserObject() instanceof Tip) {
                setSelectionRow(row);
                return;
            }
    }

    private boolean byDate() {
        return log.getRefsOrder().equals("date");
    }

    /** Laid out again in the log's order of refs now. */
    void reorder() {
        rebuild();
    }

    /**
     * Narrows the tree to the refs with text in their names; "" for all,
     * what was open before narrowing open again.
     */
    void narrow(String text) {
        if (filter.isEmpty() && !text.isEmpty()) {
            openBefore = new HashSet<>();
            for (int row = 0; row < getRowCount(); row++)
                if (isExpanded(row))
                    openBefore.add(key(getPathForRow(row)));
        }
        final boolean widened = !filter.isEmpty() && text.isEmpty();
        filter = text;
        rebuild();
        if (widened && openBefore != null) {
            reopen((DefaultMutableTreeNode) getModel().getRoot(), openBefore);
            openBefore = null;
        }
    }

    @Override
    public void updatePosition() {}

    @Override
    public String getLabelText() {
        return filter.isEmpty() ? "Refs" : "Refs: " + filter;
    }

    // Acting on a node.

    // A ref: the log's caret to its commit, if the log lists it.
    private void reveal(Tip t) {
        log.reveal(t.repository(), t.tip().id());
    }

    // A ref: the log filtered to it, as the branch chip has it, and to its
    // repository when there are several. By its whole name when another
    // ref there has the same short one, as a tag and a branch "release".
    private void filterTo(Tip t) {
        final LogRef ref = t.tip().ref();
        boolean ambiguous = false;
        for (Part p : parts)
            if (p.repository() == t.repository())
                for (LogRefTip other : p.tips())
                    ambiguous |= other.ref().name().equals(ref.name()) && other.ref().kind() != ref.kind();
        final String revision = ambiguous ? fullName(ref) : ref.name();
        LogQuery q = log.getQuery().withRevision(revision);
        if (log.getScope().isSeveral())
            q = q.withRepositories(List.of(log.getScope().name(t.repository())));
        log.setQuery(q);
        FilterBar.refreshAll(log);
    }

    /** "refs/heads/x", "refs/remotes/origin/x", "refs/tags/v1", or "HEAD". */
    static String fullName(LogRef ref) {
        return switch (ref.kind()) {
            case HEAD -> "HEAD";
            case BRANCH -> "refs/heads/" + ref.name();
            case REMOTE -> "refs/remotes/" + ref.name();
            case TAG -> "refs/tags/" + ref.name();
        };
    }

    // The tags all shown, their section open.
    private void more() {
        allTags = true;
        rebuild();
    }

    private Object selected() {
        final TreePath path = getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private Object at(MouseEvent e) {
        final TreePath path = getPathForLocation(e.getX(), e.getY());
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    // Keys: a character typed narrows it, Backspace takes one back, and
    // Escape, while it is narrowed, widens it; the rest as the sidebar's
    // other trees have them, Enter filtering to the ref selected.

    @Override
    public void keyPressed(KeyEvent e) {
        final Editor editor = Editor.currentEditor();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_BACK_SPACE -> {
                e.consume();
                if (!filter.isEmpty())
                    narrow(filter.substring(0, filter.length() - 1));
                return;
            }
            case KeyEvent.VK_ESCAPE -> {
                if (!filter.isEmpty()) {
                    e.consume();
                    narrow("");
                    return;
                }
            }
            default -> {}
        }
        sidebarKeyPressed(editor, e, () -> {
            switch (selected()) {
                case Tip t -> filterTo(t);
                case More m -> more();
                case null, default -> {}
            }
        }, this::updatePosition);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        tagKeyReleased(Editor.currentEditor(), e);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        e.consume();
        final char c = e.getKeyChar();
        // Shift is a character's own; AltGr, which comes as Ctrl and Alt, too.
        final boolean altGr = e.isControlDown() && e.isAltDown();
        if (!altGr && (e.isControlDown() || e.isAltDown() || e.isMetaDown()))
            return;
        if (c >= ' ' && c != KeyEvent.CHAR_UNDEFINED && c != 0x7f)
            narrow(filter + c);
    }

    // The mouse: a click goes to the ref, a double click filters to it; then
    // the keys are the log's again, as after a click in the sidebar's other
    // trees.

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || !Keys.isUnmodified(e))
            return;
        if (Editor.currentEditor().getFrame() != null)
            LocationBar.cancelInput();
        final Object at = at(e);
        switch (at) {
            case Tip t -> {
                if (e.getClickCount() == 2)
                    filterTo(t);
                else if (e.getClickCount() == 1)
                    reveal(t);
            }
            case More m -> more();
            case null, default -> {}
        }
        if (at instanceof Tip || at instanceof More)
            focusLog();
    }

    // The focus to the window on the log, from this tree.
    private void focusLog() {
        final Editor ed = Editor.windowShowing(log);
        if (ed != null && ed.getFrame() != null) {
            Editor.setCurrentEditor(ed);
            ed.setFocusToDisplay();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    // A ref by its name, the branch checked out bold, a local branch's
    // ahead and behind after it; a folder with the file tree's icons, a ref
    // with none.
    private static final class Renderer extends DefaultTreeCellRenderer {
        private final Color focusedSelection = getBackgroundSelectionColor();
        // The tree's font, plain and bold, made again when it changes.
        private Font plain;
        private Font bold;

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
            if (plain != tree.getFont()) {
                plain = tree.getFont();
                bold = plain.deriveFont(Font.BOLD);
            }
            final Object o = ((DefaultMutableTreeNode) value).getUserObject();
            setFont(o instanceof Tip t && t.tip().ref().head() ? bold : plain);
            if (o instanceof Tip t) {
                setIcon(null);
                final String track = t.tip().track();
                setText(track.isEmpty() ? t.label() : t.label() + "  " + track);
            } else if (o instanceof More) {
                setIcon(null);
            }
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
