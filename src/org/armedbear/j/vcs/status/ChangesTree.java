/*
 * ChangesTree.java
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
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.armedbear.j.Buffer;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.FileIcons;
import org.armedbear.j.Frame;
import org.armedbear.j.LocationBar;
import org.armedbear.j.Sidebar;
import org.armedbear.j.SidebarTree;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;
import org.armedbear.j.util.PickIcon;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Changelists.Changelist;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.armedbear.j.vcs.status.StatusLine.Member;

/**
 * The sidebar's Changes tab: the changed files of the repositories the
 * status of the current buffer would show, by changelist, else by
 * section, from their shared {@link RepositoryStatus}, held while shown;
 * a file split across lists under each, with how many of its hunks are
 * there. Each file has a box that picks it, as its box in the status; a
 * click on its name opens it, at its first change there; a file dragged
 * onto another list moves its changes there. A click on the tab's title
 * reads again.
 */
public final class ChangesTree extends SidebarTree implements KeyListener, MouseListener {
    /** A repository, for several. */
    record RepositoryNode(String name, String key) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A changelist, list its name, or a section, list null: its title, its files, and whether it is active. */
    record GroupNode(VcsRepository repository, String section, String list, String title, int count, boolean active,
            String key) {
        @Override
        public String toString() {
            return title + " (" + count + ")" + (active ? "  active" : "");
        }
    }

    /**
     * A file: its line as the status's, which picks it; its list, or null
     * under a section; of a file split across lists, "2/5 hunks", else
     * null; and its key here.
     */
    record FileNode(StatusLine line, String list, String hunks, String key) {
        FileItem file() {
            return line.file();
        }

        // Its directory, or "" at the top.
        String dir() {
            final String p = file().path();
            final int slash = p.lastIndexOf('/');
            return slash < 0 ? "" : p.substring(0, slash);
        }

        @Override
        public String toString() {
            final String p = file().path();
            return file().letter() + "  " + p.substring(p.lastIndexOf('/') + 1);
        }
    }

    /** A line of text alone: why there is nothing, or a repository unread. */
    record MessageNode(String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    /** One repository as read: its sections, and its unstaged files in its changelists, or null. */
    record Part(VcsRepository repository, String name, StatusData data, ListedChanges listed) {}

    // The sidebar it is in; null in tests.
    private final Sidebar sidebar;
    // The status it shows, held while shown; null for none, as before one is found.
    private RepositoryStatus status;
    private boolean shown;
    // The buffer followed last.
    private Buffer followed;
    // The repositories it showed as it was hidden, shown again for a buffer in none.
    private List<VcsRepository> hidden;
    // What it shows, and of which status.
    private List<Part> parts = List.of();
    private RepositoryStatus partsOf;
    private String partsSaid;
    private boolean built;
    // Whether each node was left open or closed, by key, across rebuilds,
    // kept by its frame across sidebars.
    private final TreeOpenState open;
    // Told by its status as it reads and picks.
    private final RepositoryStatus.Listener shows = new RepositoryStatus.Listener() {
        @Override
        public void read(int dropped, RepositoryStatus.Drop why) {
            update();
        }

        @Override
        public void picked() {
            update();
        }
    };
    // The file dragged, while one is.
    private FileNode dragged;

    /** In sidebar, null in tests; its nodes left open or closed kept in opened, by key. */
    public ChangesTree(Sidebar sidebar, Map<String, Boolean> opened) {
        super((TreeModel) null);
        this.sidebar = sidebar;
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setRootVisible(false);
        setShowsRootHandles(true);
        setCellRenderer(new Renderer());
        setFocusTraversalKeysEnabled(false);
        setToolTipText("");
        addKeyListener(this);
        addMouseListener(this);
        open = new TreeOpenState(this, opened, ChangesTree::key, o -> true);
        // Headless, as in tests, there is no dragging.
        if (!GraphicsEnvironment.isHeadless())
            setDragEnabled(true);
        setDropMode(DropMode.ON);
        setTransferHandler(new Mover());
        setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
    }

    /**
     * Shown in the sidebar or not: while it is, it follows the current
     * buffer, holding the status of its repositories; hidden, it lets go.
     */
    public void shown(boolean shown) {
        this.shown = shown;
        followed = null;
        if (shown) {
            follow();
            update();
        } else {
            if (status != null)
                hidden = status.getRepositories();
            show(null);
        }
    }

    /** Its tab's title chosen again: read again. */
    public void again() {
        if (status != null)
            status.refresh();
    }

    /** The status of the current buffer's repositories its, read if need be, while shown. */
    public void follow() {
        if (!shown)
            return;
        final Editor ed = editor();
        final Buffer b = ed == null ? null : ed.getBuffer();
        if (b == null)
            return;
        // Asked each time, a buffer's repositories may change: cached by repositoriesFor.
        List<VcsRepository> rs = VcsStatusMode.repositoriesFor(b);
        // Not in one, as a directory listing outside: what it showed stays.
        if (rs.isEmpty())
            rs = status != null ? status.getRepositories() : hidden != null ? hidden : List.of();
        if (!rs.isEmpty() && (status == null || !status.getRepositories().equals(rs)))
            show(RepositoryStatus.of(rs));
        else if (status == null && !built)
            update();
        if (b != followed) {
            followed = b;
            selectFile(b);
        }
    }

    // It shows s, held, or nothing for null.
    private void show(RepositoryStatus s) {
        if (s == status)
            return;
        final RepositoryStatus was = status;
        status = s;
        if (s != null)
            s.hold(this, shows);
        if (was != null)
            was.release(this, shows);
        update();
    }

    // The window of its frame it acts in.
    private Editor editor() {
        return sidebar != null ? sidebar.getEditor() : Editor.currentEditor();
    }

    /** The status it shows, or null. */
    RepositoryStatus status() {
        return status;
    }

    /** What its status last read, shown, the nodes open as they were left. */
    void update() {
        final List<Part> now = new ArrayList<>();
        if (status != null)
            for (VcsRepository r : status.getRepositories()) {
                final StatusData d = status.getData(r);
                if (d != null)
                    now.add(new Part(r, r.name(), d, status.listed(r)));
            }
        final String said = status == null ? "Not in a repository" : now.isEmpty() ? "Reading…" : null;
        // Nothing new, as after a pick: its boxes drawn again.
        if (built && status == partsOf && now.equals(parts) && Objects.equals(said, partsSaid)) {
            repaint();
            return;
        }
        built = true;
        parts = List.copyOf(now);
        partsOf = status;
        partsSaid = said;
        final String selected = key(getSelectionPath());
        final DefaultMutableTreeNode root = said != null ? message(said) : build(now);
        setModel(new DefaultTreeModel(root));
        open.restore(new TreePath(root));
        if (selected != null)
            select(selected);
        if (getSelectionPath() == null && followed != null)
            selectFile(followed);
    }

    private static DefaultMutableTreeNode message(String text) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (text != null)
            root.add(new DefaultMutableTreeNode(new MessageNode(text)));
        return root;
    }

    // The sections' order: what a commit is made of first, the untracked
    // last; any other after.
    private static final List<String> ORDER =
            List.of(StatusData.UNMERGED, StatusRenderer.UNSTAGED, "staged", "untracked");

    // Whether s is a section of files a commit takes: not commits nor stashes.
    private static boolean isFiles(Section s) {
        return !StatusData.isCommits(s.id()) && !StatusData.STASHES.equals(s.id());
    }

    /**
     * The tree of parts: one's lists and sections at the top, several's
     * each under its repository's name; the work tree's changes as their
     * lists if they are sorted into them, every list shown, so a file can
     * be dragged to it.
     */
    static DefaultMutableTreeNode build(List<Part> parts) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (Part p : parts) {
            final VcsRepository r = p.repository();
            final String rk = r.root().toString();
            final DefaultMutableTreeNode under =
                    parts.size() > 1 ? new DefaultMutableTreeNode(new RepositoryNode(p.name(), rk)) : root;
            if (p.data().error() != null)
                under.add(new DefaultMutableTreeNode(new MessageNode(p.data().error().lines().findFirst().orElse(""))));
            boolean any = false;
            final List<Section> sections = new ArrayList<>(p.data().sections());
            sections.removeIf(s -> !isFiles(s));
            sections.sort(Comparator.comparingInt(s -> ORDER.indexOf(s.id()) & Integer.MAX_VALUE));
            for (Section s : sections) {
                any = true;
                final String sk = rk + "|" + s.id();
                if (StatusRenderer.UNSTAGED.equals(s.id()) && p.listed() != null) {
                    lists(under, r, sk, s.id(), p.listed());
                    continue;
                }
                final DefaultMutableTreeNode section =
                        new DefaultMutableTreeNode(new GroupNode(r, s.id(), null, s.title(), s.total(), false, sk));
                for (Item i : s.items())
                    if (i instanceof FileItem f)
                        section.add(new DefaultMutableTreeNode(file(r, s.id(), sk, f, null, null)));
                under.add(section);
            }
            // The lists, empty, as nothing is changed.
            if (!any && p.listed() != null)
                lists(under, r, rk + "|" + StatusRenderer.UNSTAGED, StatusRenderer.UNSTAGED, p.listed());
            else if (!any && p.data().error() == null)
                under.add(new DefaultMutableTreeNode(new MessageNode("No changes")));
            if (under != root)
                root.add(under);
        }
        return root;
    }

    // listed's lists under node, each with its files.
    private static void lists(
            DefaultMutableTreeNode node,
            VcsRepository r,
            String sk,
            String section,
            ListedChanges listed) {
        for (Changelist l : listed.lists()) {
            final List<Member> members = listed.members().get(l.name());
            final String lk = sk + "||" + l.name();
            final DefaultMutableTreeNode list = new DefaultMutableTreeNode(new GroupNode(r,
                    section,
                    l.name(),
                    l.name(),
                    members.size(),
                    l.name().equals(listed.active()),
                    lk));
            for (Member m : members)
                list.add(new DefaultMutableTreeNode(file(r, section, lk, m.file(), m.only(), l.name())));
            node.add(list);
        }
    }

    // A file's node under the group keyed gk; only, its lines in list, by hunk id, or null for all.
    private static FileNode file(
            VcsRepository r,
            String section,
            String gk,
            FileItem f,
            Map<String, BitSet> only,
            String list) {
        final String key = StatusRenderer.fileKey(r, section, f.path());
        final StatusLine line = new StatusLine(Kind.FILE, f.path(), key, key, r, section).file(f).only(only);
        final String hunks =
                only == null || f.diff() == null ? null : only.size() + "/" + f.diff().hunks().size() + " hunks";
        return new FileNode(line, list, hunks, gk + "|" + f.path());
    }

    // A node's key, null for none.
    private static String key(TreePath path) {
        if (path == null)
            return null;
        return switch (((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()) {
            case RepositoryNode r -> r.key();
            case GroupNode g -> g.key();
            case FileNode f -> f.key();
            case null, default -> null;
        };
    }

    // The node keyed key selected, if it is shown.
    private void select(String key) {
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final TreePath path = new TreePath(((DefaultMutableTreeNode) nodes.nextElement()).getPath());
            if (key.equals(key(path))) {
                if (isVisible(path))
                    setSelectionPath(path);
                return;
            }
        }
    }

    // b's file's first node selected and shown, if it has one.
    private void selectFile(Buffer b) {
        final File file = b.getFile();
        if (file == null)
            return;
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
            if (n.getUserObject() instanceof FileNode f && file.canonicalPath().equals(fileOf(f).canonicalPath())) {
                final TreePath path = new TreePath(n.getPath());
                if (!isVisible(path))
                    return;
                setSelectionPath(path);
                final Rectangle bounds = getPathBounds(path);
                if (bounds != null)
                    scrollRectToVisible(new Rectangle(0, bounds.y, 1, bounds.height));
                return;
            }
        }
    }

    private static File fileOf(FileNode f) {
        return File.getInstance(f.line().repository().root().resolve(f.file().path()).toString());
    }

    // Acting on a node.

    /** Picks f in the status, or unpicks it if it is picked, as its box there. */
    void pick(FileNode f) {
        if (status != null && StatusPicks.pickable(f.line()))
            status.pick(List.of(f.line()));
    }

    /** How much of f the status has picked. */
    PickState pickState(FileNode f) {
        return status == null ? PickState.NONE : status.picks().state(f.line());
    }

    /** Opens f's file in the current window, at its first change in its list. */
    void open(FileNode f) {
        VcsStatusMode.visit(editor(), f.line().repository(), f.file(), firstLine(f));
    }

    // The new side's line of f's first change in its list, from 1; 0 for none.
    private int firstLine(FileNode f) {
        final Map<String, BitSet> only = f.line().only();
        if (only == null || f.file().diff() == null || status == null)
            return VcsStatusMode.lineNumber(f.line());
        final Map<Hunk, String> idOf = new IdentityHashMap<>();
        for (Map.Entry<String, Hunk> e : status.picks().ids(f.file().diff()).entrySet())
            idOf.put(e.getValue(), e.getKey());
        for (Hunk h : f.file().diff().hunks()) {
            final BitSet lines = only.get(idOf.get(h));
            if (lines != null && !lines.isEmpty())
                return newLine(h, lines.nextSetBit(0));
        }
        return VcsStatusMode.lineNumber(f.line());
    }

    // The new side's line, from 1, of line i of h: a removed line's where it was.
    private static int newLine(Hunk h, int i) {
        int n = h.newStart();
        for (int k = 0; k < i && k < h.lines().size(); k++)
            if (!h.lines().get(k).isRemoved())
                n++;
        return Math.max(1, n);
    }

    /** Moves f's changes in its list, or all of it, to the list to; what is said, in the status bar. */
    void move(FileNode f, String to) {
        final Editor ed = editor();
        if (status != null)
            ChangelistCommands
                    .moveFile(status, f.line().repository(), f.file().path(), f.line().only(), to, s -> ed.status(s));
    }

    // Whether f can be dropped onto g: a list of its repository, not its own.
    private static boolean canMove(FileNode f, GroupNode g) {
        return f.list() != null
                && g.list() != null
                && g.repository() == f.line().repository()
                && !g.list().equals(f.list());
    }

    private Object nodeAt(int x, int y) {
        final TreePath path = getPathForLocation(x, y);
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    // Whether x, y is on the box of the row it is in.
    private boolean onBox(int x, int y) {
        final TreePath path = getPathForLocation(x, y);
        final Rectangle bounds = path == null ? null : getPathBounds(path);
        return bounds != null && x < bounds.x + PickIcon.WIDTH;
    }

    private Object selected() {
        final TreePath path = getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (!(nodeAt(e.getX(), e.getY()) instanceof FileNode f))
            return null;
        if (onBox(e.getX(), e.getY()))
            return "Pick or unpick it";
        return f.file().change().origPath() != null
                ? f.file().change().origPath() + " → " + f.file().path()
                : f.file().path();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        final Editor editor = Editor.currentEditor();
        // Tab to the lower half, Space picks.
        if (e.getKeyCode() == KeyEvent.VK_TAB && Keys.keyModifiers(e) == 0) {
            e.consume();
            if (sidebar != null && sidebar.getBottomComponent() != null)
                editor.setFocus((JComponent) sidebar.getBottomComponent());
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_SPACE && Keys.keyModifiers(e) == 0) {
            e.consume();
            if (selected() instanceof FileNode f)
                pick(f);
            return;
        }
        sidebarKeyPressed(editor, e, () -> {
            if (selected() instanceof FileNode f)
                open(f);
        }, () -> {});
    }

    @Override
    public void keyReleased(KeyEvent e) {
        tagKeyReleased(Editor.currentEditor(), e);
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || !Keys.isUnmodified(e) || e.getClickCount() != 1)
            return;
        if (Editor.currentEditor().getFrame() != null)
            LocationBar.cancelInput();
        if (!(nodeAt(e.getX(), e.getY()) instanceof FileNode f))
            return;
        if (onBox(e.getX(), e.getY()))
            pick(f);
        else
            open(f);
    }

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    // A file dragged onto another list of its repository: its changes moved there.
    private final class Mover extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return selected() instanceof FileNode f && f.list() != null ? MOVE : NONE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            if (!(selected() instanceof FileNode f) || f.list() == null)
                return null;
            dragged = f;
            return new StringSelection(f.file().path());
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            dragged = null;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || dragged == null)
                return false;
            final TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();
            return path != null
                    && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof GroupNode g
                    && canMove(dragged, g);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support))
                return false;
            final TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();
            move(dragged, ((GroupNode) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()).list());
            return true;
        }
    }

    // A file with its box, then its type's icon, its directory and hunks
    // after its name, dimmed; a list with the changelist icon; a section
    // or repository with the file tree's folders.
    private final class Renderer extends DefaultTreeCellRenderer {
        private final Color focusedSelection = getBackgroundSelectionColor();
        private final Icon list = Icons.getIconFromFile("changelist");
        // Dimmed after the name, or null.
        private String after;

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
            after = null;
            switch (o) {
                case FileNode f -> {
                    setIcon(new Boxed(pickState(f), FileIcons.getIcon(f.file().path(), null)));
                    final String dir = f.dir();
                    after = f.hunks() == null ? dir : dir.isEmpty() ? f.hunks() : f.hunks() + "  " + dir;
                }
                case GroupNode g -> setIcon(g.list() != null ? list : expanded ? getOpenIcon() : getClosedIcon());
                case RepositoryNode r -> setIcon(expanded ? getOpenIcon() : getClosedIcon());
                case null, default -> setIcon(null);
            }
            final Frame frame = Editor.getCurrentFrame();
            setBackgroundSelectionColor(
                frame != null && frame.getFocusedComponent() == tree
                        ? focusedSelection
                        : NO_FOCUS_SELECTION_BACKGROUND);
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            final Dimension d = super.getPreferredSize();
            if (d != null && after != null && !after.isEmpty())
                d.width += gap() + getFontMetrics(getFont()).stringWidth(after);
            return d;
        }

        private int gap() {
            return getFontMetrics(getFont()).stringWidth("  ");
        }

        @Override
        public void paintComponent(Graphics g) {
            Display.setRenderingHints(g);
            super.paintComponent(g);
            if (after == null || after.isEmpty())
                return;
            final FontMetrics fm = g.getFontMetrics(getFont());
            final Icon icon = getIcon();
            final int x = (icon == null ? 0 : icon.getIconWidth() + getIconTextGap())
                    + fm.stringWidth(Objects.toString(getText(), "")) + gap();
            final Color dim = UIManager.getColor("Label.disabledForeground");
            g.setColor(dim != null ? dim : Color.GRAY);
            g.setFont(getFont());
            g.drawString(after, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    // A file's box, as the status's, then its icon.
    private final class Boxed implements Icon {
        private final PickState state;
        private final Icon icon;

        Boxed(PickState state, Icon icon) {
            this.state = state;
            this.icon = icon;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            final Color picked = editor() == null ? Color.BLUE : editor().getFormatter().getHoverLinkColor();
            PickIcon.paint((Graphics2D) g, state, picked, x, y, getIconHeight());
            if (icon != null)
                icon.paintIcon(c, g, x + PickIcon.WIDTH + 2, y + (getIconHeight() - icon.getIconHeight()) / 2);
        }

        @Override
        public int getIconWidth() {
            return PickIcon.WIDTH + 2 + (icon == null ? 0 : icon.getIconWidth());
        }

        @Override
        public int getIconHeight() {
            return Math.max(PickIcon.WIDTH, icon == null ? 0 : icon.getIconHeight());
        }
    }
}
