/*
 * ChangesTreeTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.ChangelistStore;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Change;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.armedbear.j.vcs.status.ChangesTree.FileNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ChangesTreeTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private ChangesTree tree;
    // What the tree keeps across sidebars, as a frame does.
    private final ChangesTree.Kept kept = new ChangesTree.Kept();

    private static final String SIXTY;

    static {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++)
            sb.append("line ").append(i).append('\n');
        SIXTY = sb.toString();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (tree != null)
            onEdt(() -> tree.shown(false));
        final List<Buffer> buffers = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            buffers.add(b);
        onEdt(() -> {
            for (Buffer b : buffers)
                if (b instanceof VcsStatusBuffer) {
                    b.dispose();
                    Editor.getBufferList().remove(b);
                }
        });
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        SwingUtilities.invokeAndWait(RepositoryStatus::closeAll);
        GitFixture.reset();
    }

    // In git, under root: a.txt of sixty lines with lines 3 and 50
    // changed, b.txt staged, c.txt new.
    private static GitFixture repository(Path root) throws Exception {
        final GitFixture g = GitFixture.init(root);
        g.write("a.txt", SIXTY);
        g.write("b.txt", "b\n");
        g.commitAll("first");
        g.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n"));
        g.write("b.txt", "b changed\n");
        g.git("add", "b.txt");
        g.write("c.txt", "new\n");
        return g;
    }

    // a.txt of root shown, and the tree shown, following it.
    private void show(Path root) throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            final Buffer a = Editor.getBuffer(File.getInstance(root.resolve("a.txt").toString()));
            if (!a.initialized())
                a.initialize();
            a.load();
            h.showBuffer(a);
            tree = new ChangesTree(null, kept);
            tree.shown(true);
        });
        until(() -> !rows().isEmpty() && !rows().get(0).equals("Reading…"));
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    // The TreePath whose user object is o, or null.
    private javax.swing.tree.TreePath pathTo(Object o) {
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
            if (n.getUserObject() == o)
                return new javax.swing.tree.TreePath(n.getPath());
        }
        throw new AssertionError("no node " + o + " in " + rows());
    }

    // The menu for the node whose user object is o.
    private javax.swing.JPopupMenu menuFor(Object o) throws Exception {
        final javax.swing.JPopupMenu[] out = new javax.swing.JPopupMenu[1];
        onEdt(() -> out[0] = tree.menuFor(pathTo(o)));
        assertNotNull(out[0], "no menu for " + o);
        // Every item is a real j command, as the feature promises.
        for (String name : commands(out[0]))
            assertNotNull(org.armedbear.j.CommandTable.getCommand(name), "command " + name + " is registered");
        return out[0];
    }

    // The item whose action command is command, or null.
    private static org.armedbear.j.MenuItem item(javax.swing.JPopupMenu menu, String command) {
        for (java.awt.Component c : menu.getComponents())
            if (c instanceof org.armedbear.j.MenuItem mi && command.equals(mi.getActionCommand()))
                return mi;
        return null;
    }

    // The commands of the menu, in order.
    private static List<String> commands(javax.swing.JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (java.awt.Component c : menu.getComponents())
            if (c instanceof javax.swing.JMenuItem mi)
                out.add(mi.getActionCommand());
        return out;
    }

    @Test
    public void aFileNodeMenuOffersTheFileItemsWithTheirKeys() throws Exception {
        git = repository(tmp);
        show(tmp);
        final FileNode a = file("Unstaged", "a.txt");
        final javax.swing.JPopupMenu menu = menuFor(a);
        assertTrue(
            commands(menu).containsAll(
                List.of(
                    "vcsCommitFiles",
                    "vcsDiscard",
                    "vcsStage",
                    "vcsUnstage",
                    "vcsChangelistMove",
                    "vcsShowDiff",
                    "vcsLogFileAt",
                    "vcsBlame",
                    "vcsStatusVisit",
                    "vcsStatusPick",
                    "vcsCopyPath")),
            commands(menu).toString());
        // The tree's own Ctrl D for the diff; the status mode's S/U/X for the rest.
        assertEquals("Ctrl D", item(menu, "vcsShowDiff").getAcceleratorText());
        assertEquals("S", item(menu, "vcsStage").getAcceleratorText());
        assertEquals("X", item(menu, "vcsDiscard").getAcceleratorText());
        // Unstaged file: Stage live, Unstage dimmed.
        assertTrue(item(menu, "vcsStage").isEnabled());
        assertFalse(item(menu, "vcsUnstage").isEnabled());
    }

    @Test
    public void anUntrackedFileNodeSaysDeleteAndOffersIgnore() throws Exception {
        git = repository(tmp);
        show(tmp);
        final FileNode c = file("Untracked", "c.txt");
        final javax.swing.JPopupMenu menu = menuFor(c);
        assertEquals("Add to VCS", item(menu, "vcsStage").getText());
        assertTrue(item(menu, "vcsStage").isEnabled(), "an untracked file can be added");
        assertNull(item(menu, "vcsUnstage"));
        assertEquals("Delete…", item(menu, "vcsDiscard").getText());
        assertNotNull(item(menu, "vcsIgnore"));
        assertFalse(item(menu, "vcsBlame").isEnabled());
    }

    @Test
    public void aListNodeOffersTheChangelistItems() throws Exception {
        git = repository(tmp);
        show(tmp);
        splitAcrossLists();
        // The Feature list's node: not active, not Default, all live.
        final ChangesTree.GroupNode feature = group("Feature");
        final javax.swing.JPopupMenu menu = menuFor(feature);
        assertTrue(
            commands(menu).containsAll(
                List.of(
                    "vcsCommitList",
                    "vcsChangelistActive",
                    "vcsChangelistNew",
                    "vcsChangelistRename",
                    "vcsChangelistDelete")),
            commands(menu).toString());
        assertTrue(item(menu, "vcsChangelistActive").isEnabled());
        assertTrue(item(menu, "vcsChangelistRename").isEnabled());
        assertTrue(item(menu, "vcsChangelistDelete").isEnabled());
        // Default: already active, and it can't be renamed or deleted.
        final javax.swing.JPopupMenu def = menuFor(group("Default"));
        assertFalse(item(def, "vcsChangelistActive").isEnabled(), "Default is active");
        assertEquals("Already active", item(def, "vcsChangelistActive").getToolTipText());
        assertFalse(item(def, "vcsChangelistRename").isEnabled(), "Default can't be renamed");
        assertEquals("Default can't be renamed", item(def, "vcsChangelistRename").getToolTipText());
        assertFalse(item(def, "vcsChangelistDelete").isEnabled(), "Default stays");
        assertEquals("Default stays", item(def, "vcsChangelistDelete").getToolTipText());
        // Commit and New still offered on Default.
        assertTrue(item(def, "vcsCommitList").isEnabled());
        assertTrue(item(def, "vcsChangelistNew").isEnabled());
    }

    @Test
    public void aSectionNodeStagesUnstagesDiscardsAll() throws Exception {
        git = repository(tmp);
        show(tmp);
        final ChangesTree.GroupNode unstaged = group("Unstaged changes");
        final javax.swing.JPopupMenu menu = menuFor(unstaged);
        assertEquals("Stage all", item(menu, "vcsStage").getText());
        assertEquals("Unstage all", item(menu, "vcsUnstage").getText());
        assertEquals("Discard all…", item(menu, "vcsDiscard").getText());
    }

    @Test
    public void aRepositoryNodeRefreshesAndOpensStatus() throws Exception {
        final Path a = Files.createDirectories(tmp.resolve("a"));
        final Path b = Files.createDirectories(tmp.resolve("b"));
        git = repository(a);
        final GitFixture other = GitFixture.init(b);
        other.write("x.txt", "x\n");
        other.commitAll("first");
        other.write("x.txt", "x changed\n");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository ra = Repositories.forDirectory(a);
        final VcsRepository rb = Repositories.forDirectory(b);
        onEdt(() -> {
            final VcsStatusBuffer status = VcsStatusBuffer.of(List.of(ra, rb));
            h.showBuffer(status);
            tree = new ChangesTree(null, kept);
            tree.shown(true);
        });
        until(() -> rows().contains("  Unstaged changes (1)"));
        final ChangesTree.RepositoryNode node = repoNode();
        final javax.swing.JPopupMenu menu = menuFor(node);
        assertTrue(commands(menu).containsAll(List.of("vcsStatusRefresh", "vcsStatus")), commands(menu).toString());
    }

    // The group node whose title starts with title.
    private ChangesTree.GroupNode group(String title) {
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final Object o = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
            if (o instanceof ChangesTree.GroupNode g && g.toString().startsWith(title))
                return g;
        }
        throw new AssertionError("no group " + title + " in " + rows());
    }

    // The repository node.
    private ChangesTree.RepositoryNode repoNode() {
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final Object o = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
            if (o instanceof ChangesTree.RepositoryNode r)
                return r;
        }
        throw new AssertionError("no repository node in " + rows());
    }

    // Several files selected: the menu takes them all, "Commit files…".
    @Test
    public void severalFilesSelectedCommitFilesAndGroupsStaySingle() throws Exception {
        git = repository(tmp);
        show(tmp);
        final FileNode a = file("Unstaged", "a.txt");
        final FileNode b = file("Staged", "b.txt");
        onEdt(() -> {
            tree.addSelectionPath(pathTo(a));
            tree.addSelectionPath(pathTo(b));
        });
        final javax.swing.JPopupMenu[] out = new javax.swing.JPopupMenu[1];
        onEdt(() -> out[0] = tree.menuFor(pathTo(a)));
        // Two files: "Commit files…".
        assertEquals("Commit files…", item(out[0], "vcsCommitFiles").getText());
        // A group added to files selects it alone.
        onEdt(() -> tree.addSelectionPath(pathTo(group("Unstaged changes"))));
        onEdt(() -> {
            final javax.swing.tree.TreePath[] sel = tree.getSelectionPaths();
            assertEquals(1, sel.length, "a group alone");
            assertTrue(
                sel[0].getLastPathComponent() instanceof DefaultMutableTreeNode n
                        && n.getUserObject() instanceof ChangesTree.GroupNode);
        });
    }

    // The tree's rows, each indented by its depth, a split file's hunks after it.
    private List<String> rows() {
        final List<String> out = new ArrayList<>();
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
            if (n.isRoot())
                continue;
            String row = "  ".repeat(n.getLevel() - 1) + n.getUserObject();
            if (n.getUserObject() instanceof FileNode f && f.hunks() != null)
                row += " " + f.hunks();
            out.add(row);
        }
        return out;
    }

    // The file node under the group whose title starts with group.
    private FileNode file(String group, String path) {
        boolean in = false;
        final Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            final Object o = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
            if (o instanceof ChangesTree.GroupNode g)
                in = g.toString().startsWith(group);
            else if (in && o instanceof FileNode f && f.file().path().equals(path))
                return f;
        }
        throw new AssertionError("no " + path + " under " + group + " in " + rows());
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + rows());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    // Lists Default and Feature in git's repository, line 50's change in
    // Feature: a.txt split across the two.
    private void splitAcrossLists() throws Exception {
        final String fifty = "line fifty";
        onEdt(() -> {
            assertTrue(RepositoryLists.of(repo()).update(c -> c.withList("Feature")));
            SignsUpdater.listsChanged(repo());
        });
        until(() -> rows().contains("Feature (0)"));
        // Line 50's hunk, all its lines, moved to Feature.
        until(() -> file("Default", "a.txt").file().diff() != null);
        final FileNode a = file("Default", "a.txt");
        final java.util.Map<String, java.util.BitSet> only = new java.util.HashMap<>();
        final RepositoryStatus status = tree.status();
        onEdt(() -> {
            for (java.util.Map.Entry<String, org.armedbear.j.vcs.diff.Hunk> e : status.picks()
                    .ids(a.file().diff())
                    .entrySet())
                if (e.getValue().lines().stream().anyMatch(l -> l.text().equals(fifty))) {
                    final java.util.BitSet bits = new java.util.BitSet();
                    bits.set(0, e.getValue().lines().size());
                    only.put(e.getKey(), bits);
                }
            ChangelistCommands.moveFile(status, repo(), "a.txt", only, "Feature", s -> {});
        });
        until(() -> rows().contains("Feature (1)") && rows().contains("Default (1)  active"));
    }

    @Test
    public void oneRepositorysFilesAreByListASplitFileUnderEachWithItsHunks() throws Exception {
        git = repository(tmp);
        show(tmp);
        // No lists besides the default: by section.
        assertEquals(
            List.of(
                "Unstaged changes (1)",
                "  M  a.txt",
                "Staged changes (1)",
                "  M  b.txt",
                "Untracked files (1)",
                "  ?  c.txt"),
            rows());
        splitAcrossLists();
        assertEquals(
            List.of(
                "Default (1)  active",
                "  M  a.txt 1/2 hunks",
                "Feature (1)",
                "  M  a.txt 1/2 hunks",
                "Staged changes (1)",
                "  M  b.txt",
                "Untracked files (1)",
                "  ?  c.txt"),
            rows());
        // Opened from under each list: at its change there.
        onEdt(() -> {
            tree.open(file("Feature", "a.txt"));
            assertEquals("line fifty", h.editor().getDotLine().getText());
            tree.open(file("Default", "a.txt"));
            assertEquals("line three", h.editor().getDotLine().getText());
        });
    }

    @Test
    public void severalRepositoriesAreEachUnderItsName() throws Exception {
        final Path a = Files.createDirectories(tmp.resolve("a"));
        final Path b = Files.createDirectories(tmp.resolve("b"));
        git = repository(a);
        final GitFixture other = GitFixture.init(b);
        other.write("x.txt", "x\n");
        other.commitAll("first");
        other.write("x.txt", "x changed\n");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository ra = Repositories.forDirectory(a);
        final VcsRepository rb = Repositories.forDirectory(b);
        onEdt(() -> {
            // The status of both, as the project's: the tree shows it.
            final VcsStatusBuffer status = VcsStatusBuffer.of(List.of(ra, rb));
            h.showBuffer(status);
            tree = new ChangesTree(null, kept);
            tree.shown(true);
        });
        until(() -> rows().contains("  Unstaged changes (1)") && rows().contains("    M  x.txt"));
        assertEquals(
            List.of(
                ra.name(),
                "  Unstaged changes (1)",
                "    M  a.txt",
                "  Staged changes (1)",
                "    M  b.txt",
                "  Untracked files (1)",
                "    ?  c.txt",
                rb.name(),
                "  Unstaged changes (1)",
                "    M  x.txt"),
            rows());
    }

    @Test
    public void aFileDroppedOnAListMovesThereAndItsBoxPicksItInTheStatus() throws Exception {
        git = repository(tmp);
        show(tmp);
        splitAcrossLists();
        // The split file's part under Default, moved: all of it in Feature.
        onEdt(() -> tree.move(file("Default", "a.txt"), "Feature"));
        until(() -> rows().contains("Default (0)  active"));
        assertTrue(rows().contains("  M  a.txt"), "whole under Feature: " + rows());
        final Assignment kept = ChangelistStore.of(repo()).read().assignment("a.txt");
        assertTrue(kept.changes().stream().map(Change::list).allMatch("Feature"::equals));
        // A box: the file picked in the status, as there.
        final FileNode a = file("Feature", "a.txt");
        onEdt(() -> {
            assertSame(PickState.NONE, tree.pickState(a));
            tree.pick(a);
            assertSame(PickState.ALL, tree.pickState(a));
            assertTrue(tree.status().picks().has(StatusRenderer.fileKey(repo(), StatusRenderer.UNSTAGED, "a.txt")));
            tree.pick(a);
            assertSame(PickState.NONE, tree.pickState(a));
        });
    }

    @Test
    public void anEditShowsAndAStatusOpenedSharesItsReadAndPicks() throws Exception {
        git = repository(tmp);
        show(tmp);
        final RepositoryStatus status = tree.status();
        assertNotNull(status);
        for (Buffer b : Editor.getBufferList())
            assertFalse(b instanceof VcsStatusBuffer, "no status buffer");
        // b.txt changed again: the work tree's change shown with the staged.
        git.write("b.txt", "b changed again\n");
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> rows().contains("Unstaged changes (2)"));
        assertTrue(rows().contains("  M  b.txt"));
        // Picked here, then the status opened: the same, picked there.
        onEdt(() -> tree.pick(file("Unstaged", "a.txt")));
        final VcsStatusBuffer[] opened = new VcsStatusBuffer[1];
        onEdt(() -> {
            opened[0] = VcsStatusBuffer.of(List.of(repo()));
            assertSame(status, opened[0].model());
            assertTrue(opened[0].picks().has(StatusRenderer.fileKey(repo(), StatusRenderer.UNSTAGED, "a.txt")));
        });
        // The tab hidden and the status closed, what is picked keeps it.
        onEdt(() -> {
            tree.shown(false);
            opened[0].dispose();
            Editor.getBufferList().remove(opened[0]);
            assertFalse(status.isDisposed(), "kept for its pick");
            status.unpickAll();
            assertTrue(status.isDisposed(), "closed with nothing to keep it");
        });
    }

    @Test
    public void vcsDiffsComparisonIsInItsFilesRepositoriesAndANewTreeShowsWhatTheLastDid() throws Exception {
        git = repository(tmp);
        show(tmp);
        onEdt(() -> org.armedbear.j.vcs.VcsDiffCommands.vcsDiff(h.editor()));
        // Made once what is staged is read.
        final org.armedbear.j.mode.compare.CompareBuffer[] found = new org.armedbear.j.mode.compare.CompareBuffer[1];
        until(() -> {
            for (Buffer b : Editor.getBufferList())
                if (b instanceof org.armedbear.j.mode.compare.CompareBuffer c)
                    found[0] = c;
            return found[0] != null;
        });
        onEdt(() -> {
            assertNull(found[0].getFile(), "a buffer of no file");
            assertEquals(List.of(repo()), VcsStatusMode.repositoriesFor(found[0]));
            found[0].kill();
        });
        // A new tree, as the sidebar is shown again, over a buffer in no
        // repository: the repositories the last one kept.
        onEdt(() -> {
            tree.shown(false);
            h.showBuffer(h.buffer());
            tree = new ChangesTree(null, kept);
            tree.shown(true);
        });
        until(() -> rows().contains("Unstaged changes (1)"));
    }
}
