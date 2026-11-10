/*
 * VcsStatusBufferTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.KeyMap;
import org.armedbear.j.KeyMapping;
import org.armedbear.j.Line;
import org.armedbear.j.Menu;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.TransientKeys;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.log.CommitDetails;
import org.armedbear.j.vcs.log.LogCommands;
import org.armedbear.j.vcs.log.RevisionBuffer;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class VcsStatusBufferTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    // A window on the comparison or on a file, closed after.
    private EditorHarness other;
    // More windows, closed after.
    private final List<EditorHarness> windows = new ArrayList<>();
    private Buffer file;
    private VcsStatusBuffer status;

    @AfterEach
    public void tearDown() throws Exception {
        if (status != null) {
            final VcsStatusBuffer b = status;
            SwingUtilities.invokeAndWait(() -> {
                b.closeDiff(null);
                b.dispose();
            });
            if (Editor.getBufferList().contains(status))
                Editor.getBufferList().remove(status);
        }
        for (EditorHarness w : windows)
            SwingUtilities.invokeAndWait(() -> {
                w.showBuffer(w.buffer());
                w.close();
            });
        if (other != null) {
            final EditorHarness o = other;
            SwingUtilities.invokeAndWait(() -> {
                o.showBuffer(o.buffer());
                o.close();
            });
        }
        if (file != null)
            Editor.getBufferList().remove(file);
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        SwingUtilities.invokeAndWait(RepositoryStatus::closeAll);
        GitFixture.reset();
    }

    private static final String TEN = "one\ntwo\nthree blind mice\nfour\nfive\nsix\nseven\neight\nnine\nten\n";

    // a.txt changed, b.txt changed and staged, c.txt new.
    private void repository() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", TEN);
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", TEN.replace("mice", "cats"));
        git.write("b.txt", "b changed\n");
        git.git("add", "b.txt");
        git.write("c.txt", "new\n");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private void open() throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> !status.getFirstLine().getText().startsWith("Reading"));
    }

    // Waits for the background read and the render it posts.
    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + text());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private List<String> text() {
        final List<String> list = new ArrayList<>();
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            list.add(l.getText());
        return list;
    }

    private StatusLine line(String text) {
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l.getText().equals(text))
                return (StatusLine) l;
        throw new AssertionError("no line " + text + " in " + text());
    }

    private String head() {
        return git.git("log", "-1", "--format=%h").strip();
    }

    @Test
    public void itsTreeHasItsSectionsAndFilesAndAClickGoesToOne() throws Exception {
        repository();
        open();
        onEdt(() -> {
            final StatusTree tree = status.tree(null);
            final List<String> rows = new ArrayList<>();
            for (int i = 0; i < tree.getRowCount(); i++)
                rows.add(tree.getPathForRow(i).getLastPathComponent() + (tree.isExpanded(i) ? " +" : ""));
            assertEquals(
                List.of(
                    "Untracked files (1)",
                    "Unstaged changes (1)",
                    "Staged changes (1)",
                    "Recent commits (1) +",
                    head() + " first"),
                rows.stream().map(r -> r.replaceAll("^(\\w+) \\(.*\\) ", "$1 ")).toList(),
                "the files' sections closed, the commits open");
            final String unstaged = git.root + "|unstaged";
            status.setExpanded(line("Unstaged changes (1)"), false);
            assertSame(h.editor(), status.reveal(null, unstaged, unstaged + "|a.txt"));
            assertEquals("  M  a.txt", h.editor().getDotLine().getText(), "its section opened again");
            tree.updatePosition();
            assertEquals(
                "Unstaged changes (1)",
                String.valueOf(tree.getSelectionPath().getLastPathComponent()),
                "the caret's file in a closed section: the section selected");
            tree.expandRow(1);
            tree.updatePosition();
            assertEquals("M  a.txt", String.valueOf(tree.getSelectionPath().getLastPathComponent()));
            status.reveal(null, git.root + "|staged", null);
            tree.updatePosition();
            assertEquals("Staged changes (1)", String.valueOf(tree.getSelectionPath().getLastPathComponent()));
            assertEquals("Staged changes (1)", h.editor().getDotLine().getText());
        });
    }

    @Test
    public void itsTreeGroupsTheFilesByDirectoryOneWithOnlyADirectoryShownWithIt() throws Exception {
        git = GitFixture.init(tmp);
        for (String f : List.of("docs/r.md", "src/org/a/X.java", "src/org/a/Y.java", "src/org/b/Z.java", "top.txt"))
            git.write(f, "x\n");
        git.commitAll("first");
        for (String f : List.of("docs/r.md", "src/org/a/X.java", "src/org/a/Y.java", "src/org/b/Z.java", "top.txt"))
            git.write(f, "y\n");
        open();
        onEdt(() -> {
            final StatusTree tree = status.tree(null);
            tree.expandRow(0);
            final List<String> rows = new ArrayList<>();
            for (int i = 0; i < tree.getRowCount(); i++) {
                final TreePath p = tree.getPathForRow(i);
                rows.add(" ".repeat(p.getPathCount() - 2) + p.getLastPathComponent());
            }
            assertEquals(
                List.of(
                    "Unstaged changes (5)",
                    " docs",
                    "  M  r.md",
                    " src/org",
                    "  a",
                    "   M  X.java",
                    "   M  Y.java",
                    "  b",
                    "   M  Z.java",
                    " M  top.txt",
                    "Recent commits (1)"),
                rows.subList(0, 11));
            tree.collapseRow(3);
            assertNotNull(status.reveal(null, git.root + "|unstaged", git.root + "|unstaged|src/org/b/Z.java"));
            tree.updatePosition();
            assertEquals(
                "src/org",
                String.valueOf(tree.getSelectionPath().getLastPathComponent()),
                "a file in a closed directory: the directory selected");
            final Rectangle docs = tree.getRowBounds(1);
            tree.mouseClicked(
                new MouseEvent(tree,
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        docs.x + 2,
                        docs.y + 2,
                        1,
                        false,
                        MouseEvent.BUTTON1));
            assertEquals("  M  docs/r.md", h.editor().getDotLine().getText(), "a directory's click: its first file");
        });
    }

    @Test
    public void theVcsMenuIsTheRepositorysAndTheFilesAsTheyApply() throws Exception {
        repository();
        open();
        onEdt(() -> {
            final Menu menu = new Menu("VCS");
            VcsStatusMode.populateVcsMenu(h.editor(), menu);
            final List<String> items = new ArrayList<>();
            for (Component c : menu.getMenuComponents())
                if (c instanceof JMenuItem item)
                    items.add(item.getText() + (item.isEnabled() ? "" : " (off)"));
            assertEquals(
                List.of("Status", "Log", "Diff with What Is Staged (off)", "Blame (off)", "Commit...", "Command Log"),
                items,
                "the status is no file to diff");
        });
    }

    @Test
    public void thePopupsKeysAreTheStatussOwn() throws Exception {
        repository();
        open();
        onEdt(() -> {
            for (boolean vim : new boolean[] { false, true }) {
                if (vim) {
                    h.vim();
                    h.editor().getBuffer().setProperty(Property.EDIT_MODE, "vim");
                }
                final Transient t = VcsStatusMode.getMode().getTransient(h.editor());
                assertEquals(
                    List.of("Show", "Go", "Changes", "Pick", "Commands"),
                    t.groups().stream().map(Transient.Group::label).toList());
                final Buffer b = h.editor().getBuffer();
                final KeyMap km =
                        vim ? VcsStatusMode.getMode().getModalKeyMap(b) : VcsStatusMode.getMode().getKeyMap(b);
                for (Transient.Group g : t.groups())
                    for (Transient.Item item : g.items())
                        // ]] and [[ are vim's own, going by sectionFrom.
                        if (item instanceof Transient.Command c && !(vim && c.key().length() == 2)) {
                            final KeyMapping m = TransientKeys.lookup(km, c.key());
                            assertNotNull(m, (vim ? "vim: " : "") + c.key() + " is no key in the status");
                            assertEquals(c.command(), m.getCommand(), (vim ? "vim: " : "") + c.key());
                        }
            }
        });
    }

    @Test
    public void aSelectionIsNotBroughtBackOntoOtherLinesAfterARender() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            h.editor().setMark(new Position(line("  M  b.txt"), 0));
            other = EditorHarness.create();
            // As switching to another buffer does.
            h.editor().saveView();
            h.showBuffer(other.buffer());
            status.render();
            h.showBufferRestored(status);
            assertNull(h.editor().getMark(), "a selection of lines laid out again");
        });
    }

    @Test
    public void showsTheHeadAndASectionPerKindOfChange() throws Exception {
        repository();
        open();
        assertEquals(
            List.of(
                "Head:     main  " + head() + " first",
                "",
                "Untracked files (1)",
                "  ?  c.txt",
                "",
                "Unstaged changes (1)",
                "  M  a.txt",
                "",
                "Staged changes (1)",
                "  M  b.txt",
                "",
                "Recent commits (1)",
                "  " + head() + " (HEAD -> main) first"),
            text());
    }

    @Test
    public void expandingAFileReadsItsDiffAndMarksTheChangedWords() throws Exception {
        repository();
        open();
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> text().contains("  -three blind mice"));
        final List<String> text = text();
        final int at = text.indexOf("  M  a.txt");
        assertEquals("  @@ -1,6 +1,6 @@", text.get(at + 1));
        assertEquals("   one", text.get(at + 2));
        final StatusLine removed = line("  -three blind mice");
        final StatusLine added = line("  +three blind cats");
        assertEquals(3, removed.oldLine());
        assertEquals(3, added.newLine());
        assertEquals(1, removed.words().size());
        assertEquals("mice", removed.getText().substring(removed.words().get(0)[0], removed.words().get(0)[1]));
        assertEquals("cats", added.getText().substring(added.words().get(0)[0], added.words().get(0)[1]));
        assertTrue(line("  M  a.txt").isOpen());
        assertFalse(line("  M  b.txt").isOpen(), "the others stay closed");
    }

    @Test
    public void aRefreshKeepsTheCaretOnItsThing() throws Exception {
        repository();
        open();
        onEdt(() -> h.editor().setDot(line("  M  b.txt"), 3));
        git.write("d.txt", "another\n"); // A line more above it.
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("  ?  d.txt"));
        onEdt(() -> {
            assertSame(line("  M  b.txt"), h.editor().getDotLine());
            assertEquals(3, h.editor().getDotOffset());
        });
    }

    @Test
    public void closingASectionLeavesTheCaretOnItsHeading() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            // zc on a file that is closed closes the section around it, as vim's does.
            assertTrue(VcsStatusMode.getMode().setFoldAt(h.editor(), h.editor().getDotLine(), false));
        });
        onEdt(() -> {
            assertFalse(text().contains("  M  a.txt"));
            assertSame(line("Unstaged changes (1)"), h.editor().getDotLine());
            assertTrue(VcsStatusMode.getMode().isFolded(line("Unstaged changes (1)")));
            assertTrue(VcsStatusMode.getMode().isFoldStart(status, line("Staged changes (1)")));
            assertFalse(VcsStatusMode.getMode().isFoldStart(status, line("  ?  c.txt")), "nothing under it");
        });
    }

    @Test
    public void depthsOpenSectionsThenFilesThenHunks() throws Exception {
        repository();
        open();
        onEdt(() -> status.setDepth(1));
        assertEquals(
            List.of("Untracked files (1)", "Unstaged changes (1)", "Staged changes (1)", "Recent commits (1)"),
            text().stream().filter(s -> s.endsWith(")") && !s.startsWith(" ")).toList());
        assertFalse(text().contains("  ?  c.txt"));
        onEdt(() -> status.setDepth(3));
        until(() -> text().contains("  @@ -1 +1 @@") && text().contains("  @@ -1,6 +1,6 @@"));
        assertFalse(text().contains("  -b"), "hunks closed at 3");
        onEdt(() -> status.setDepth(4));
        until(() -> text().contains("  -three blind mice") && text().contains("  -b"));
    }

    @Test
    public void bracketCGoesByTheHunksShown() throws Exception {
        repository();
        open();
        onEdt(() -> status.setDepth(3));
        until(() -> text().contains("  @@ -1 +1 @@") && text().contains("  @@ -1,6 +1,6 @@"));
        onEdt(() -> {
            h.editor().setDot(status.getFirstLine(), 0);
            org.armedbear.j.ChangeMarks.nextChange(h.editor());
            assertEquals(Kind.HUNK, ((StatusLine) h.editor().getDotLine()).kind());
            final Line first = h.editor().getDotLine();
            org.armedbear.j.ChangeMarks.nextChange(h.editor());
            assertEquals(Kind.HUNK, ((StatusLine) h.editor().getDotLine()).kind());
            assertFalse(first == h.editor().getDotLine(), "the next hunk");
            org.armedbear.j.ChangeMarks.nextChange(h.editor());
            assertSame(first, h.editor().getDotLine(), "around to the first");
        });
    }

    @Test
    public void aCleanRepositorySaysSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        open();
        assertTrue(text().contains("Nothing to commit, working tree clean"), text().toString());
    }

    @Test
    public void anEmptyRepositoryHasNoCommitsYet() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        open();
        assertEquals("Head:     main  (no commits yet)", text().get(0));
        assertFalse(text().stream().anyMatch(s -> s.startsWith("Recent commits")));
    }

    // The header's first lines, up to the blank one.
    private List<String> header() {
        final List<String> text = text();
        final int blank = text.indexOf("");
        assertTrue(blank > 0, text.toString());
        return text.subList(0, blank);
    }

    private GitFixture origin;

    // A clone of a repository with one commit, on x made from origin/main.
    private void cloned() throws Exception {
        origin = GitFixture.init(Files.createDirectory(tmp.resolve("origin")));
        origin.commitAll("one");
        git = GitFixture.init(Files.createDirectory(tmp.resolve("work")));
        git.git("remote", "add", "origin", origin.root.toString());
        git.git("fetch", "-q", "origin");
        git.git("switch", "-q", "-c", "x", "origin/main");
    }

    @Test
    public void anUpstreamThatIsTheParentIsTheParentLine() throws Exception {
        cloned();
        git.git("config", "push.default", "current");
        git.commitAll("two");
        open();
        assertEquals(
            List.of(
                "Head:     x  " + head() + " two",
                "Upstream: origin/main  ↑1 ↓0",
                "Push:     origin/x  gone",
                "Parent:   origin/main  (upstream)"),
            header());
    }

    @Test
    public void aRecordedParentShowsItsSourceThoughTheUpstreamNamesIt() throws Exception {
        cloned();
        git.git("config", "branch.x.gh-merge-base", "origin/main");
        open();
        assertEquals(
            List.of(
                "Head:     x  " + head() + " one",
                "Upstream: origin/main  ↑0 ↓0",
                "Parent:   origin/main  (gh-merge-base)"),
            header());
    }

    @Test
    public void theParentOfABranchPushedToItsUpstream() throws Exception {
        cloned();
        git.git("push", "-q", "-u", "origin", "x");
        git.commitAll("two");
        open();
        assertEquals(
            List.of("Head:     x  " + head() + " two", "Upstream: origin/x  ↑1 ↓0", "Parent:   origin/main  (reflog)"),
            header());
    }

    @Test
    public void aGoneUpstreamSaysSo() throws Exception {
        cloned();
        git.git("push", "-q", "-u", "origin", "x");
        git.git("push", "-q", "origin", "--delete", "x");
        git.git("fetch", "-q", "-p");
        open();
        assertEquals("Upstream: origin/x  gone", header().get(1));
    }

    // The sections' titles after the files'.
    private List<String> sectionTitles() {
        final List<String> titles = new ArrayList<>();
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.SECTION)
                titles.add(s.getText());
        return titles;
    }

    @Test
    public void justAfterBranchingTheRecentCommitsShow() throws Exception {
        cloned();
        open();
        assertEquals(List.of("Recent commits (1)"), sectionTitles());
    }

    @Test
    public void theCommitsBetweenTheBranchAndItsOthersEachOnceAndClosed() throws Exception {
        cloned();
        git.git("push", "-q", "-u", "origin", "x");
        git.commitAll("two");
        origin.commitAll("theirs");
        git.git("fetch", "-q", "origin");
        open();
        // The parent, origin/main from the reflog, isn't the upstream: its
        // own sections. No recent commits, as some are on x alone.
        assertEquals(
            List.of("Unpushed to origin/x (1)", "Unmerged from origin/main (1)", "Not in origin/main (1)"),
            sectionTitles());
        assertFalse(line("Not in origin/main (1)").isOpen());
        onEdt(() -> status.setExpanded(line("Not in origin/main (1)"), true));
        assertTrue(text().contains("  " + head() + " (HEAD -> x) two"), text().toString());
    }

    @Test
    public void aParentThatIsTheUpstreamHasNoSectionsOfItsOwn() throws Exception {
        cloned();
        git.commitAll("two");
        origin.commitAll("theirs");
        git.git("fetch", "-q", "origin");
        open();
        assertEquals(List.of("Unpulled from origin/main (1)", "Unpushed to origin/main (1)"), sectionTitles());
    }

    @Test
    public void aSectionShowsTheNewestTenAndHowManyMore() throws Exception {
        cloned();
        for (int i = 1; i <= 12; i++)
            git.commitAll("c" + i);
        open();
        onEdt(() -> status.setExpanded(line("Unpushed to origin/main (12)"), true));
        final List<String> text = text();
        final int at = text.indexOf("Unpushed to origin/main (12)");
        assertEquals("  " + head() + " (HEAD -> x) c12", text.get(at + 1));
        assertEquals("  … 2 more", text.get(at + 11));
    }

    @Test
    public void aConflictIsUnmergedAndTheMergeIsInProgress() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "theirs\n");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
        open();
        assertTrue(text().contains("Merge in progress (1 conflict)"), text().toString());
        assertTrue(text().contains("  U  a.txt (both modified)"), text().toString());
        assertTrue(line("  U  a.txt (both modified)").isExpandable(), "its combined diff");
    }

    @Test
    public void aFileDeletedByThemSaysSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.git("rm", "-q", "a.txt");
        git.commitAll("gone");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
        open();
        assertTrue(text().contains("  U  a.txt (deleted by them)"), text().toString());
    }

    @Test
    public void aPathWithSpacesAndAccentsReadsItsDiff() throws Exception {
        git = GitFixture.init(tmp);
        java.nio.file.Files.createDirectories(git.root.resolve("dir"));
        java.nio.file.Files.writeString(git.root.resolve("dir/naïve [1].txt"), "old\n");
        git.commitAll("first");
        java.nio.file.Files.writeString(git.root.resolve("dir/naïve [1].txt"), "new\n");
        open();
        onEdt(() -> status.toggle(line("  M  dir/naïve [1].txt")));
        until(() -> text().contains("  +new"));
    }

    @Test
    public void visitingADiffLineOpensTheFileThere() throws Exception {
        repository();
        open();
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> text().contains("  +three blind cats"));
        onEdt(() -> {
            h.editor().setDot(line("  +three blind cats"), 0);
            VcsStatusMode.vcsStatusVisit(h.editor());
        });
        onEdt(() -> {
            final Buffer visited = h.editor().getBuffer();
            assertEquals(File.getInstance(git.root.resolve("a.txt").toString()), visited.getFile());
            assertEquals(2, h.editor().getDotLineNumber());
            Editor.getBufferList().remove(visited);
        });
    }

    // a.txt both modified; d.txt deleted by them, changed by us.
    private void conflicted() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "base\n");
        git.write("d.txt", "d\n");
        git.commitAll("first");
        git.git("checkout", "-q", "-b", "other");
        git.write("a.txt", "theirs\n");
        git.git("rm", "-q", "d.txt");
        git.commitAll("theirs");
        git.git("checkout", "-q", "main");
        git.write("a.txt", "ours\n");
        git.write("d.txt", "d ours\n");
        git.commitAll("ours");
        git.run("merge", "other");
    }

    @Test
    public void visitingAnUnmergedFileOpensIt() throws Exception {
        conflicted();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  U  a.txt (both modified)"), 0);
            VcsStatusMode.vcsStatusVisit(h.editor());
        });
        onEdt(() -> {
            final Buffer visited = h.editor().getBuffer();
            assertEquals(File.getInstance(git.root.resolve("a.txt").toString()), visited.getFile());
            Editor.getBufferList().remove(visited);
        });
    }

    @Test
    public void anUnmergedFileShowsItsCombinedDiffReadOnly() throws Exception {
        conflicted();
        open();
        onEdt(() -> status.toggle(line("  U  a.txt (both modified)")));
        // The working tree against ours and theirs, a column for each.
        until(() -> text().contains("  ++<<<<<<< HEAD"));
        assertTrue(text().contains("   +ours"), text().toString());
        assertTrue(text().contains("  + theirs"), text().toString());
        assertTrue(text().stream().anyMatch(t -> t.startsWith("  @@@ ")), text().toString());
        // Ours' numbers from the first column: its line, and theirs' none in ours.
        onEdt(() -> {
            assertEquals(1, line("   +ours").oldLine());
            assertEquals(0, line("  + theirs").oldLine());
        });
        // No part of it stages: the file is resolved whole.
        onEdt(() -> {
            h.editor().setDot(line("   +ours"), 0);
            StatusActions.run(h.editor(), StatusActions.Op.STAGE);
        });
        assertEquals("Not in part: x on the unmerged file resolves it", h.status());
        // A side that deleted it: no combined diff, and why.
        onEdt(() -> status.toggle(line("  U  d.txt (deleted by them)")));
        until(() -> text().contains("  A side has no file: = shows the other"));
    }

    @Test
    public void aSidesLineOfAConflictIsTheWorkingTreesThroughADiffOfThem() {
        // Ours "1 Y 3 4 5"; the working tree with the markers, theirs' 4
        // deleted cleanly below: git's combined diff leaves that out.
        final List<String> ours = List.of("1", "Y", "3", "4", "5");
        final List<String> working = List.of("1", "<<<<<<< ours", "Y", "=======", "X", ">>>>>>> theirs", "3", "5");
        assertEquals(3, VcsStatusMode.sideToWorking(ours, working, 1), "Y");
        assertEquals(7, VcsStatusMode.sideToWorking(ours, working, 2), "3");
        assertEquals(7, VcsStatusMode.sideToWorking(ours, working, 3), "4, gone: the line before");
        assertEquals(8, VcsStatusMode.sideToWorking(ours, working, 4), "5, past the clean deletion");
    }

    @Test
    public void sideBySideAnUnmergedFileIsOursAgainstTheirs() throws Exception {
        conflicted();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  U  a.txt (both modified)"));
        until(() -> "a.txt (ours)".equals(c.getOld().getTitle()));
        onEdt(() -> {
            assertEquals(List.of("ours"), lines(c.getOld()));
            assertEquals("a.txt (theirs)", c.getTitle());
            assertEquals(List.of("theirs"), lines(c));
            assertTrue(c.isReadOnly() && c.getOld().isReadOnly(), "read only");
        });
        // A side deleted: nothing there, and the title says why.
        caretOn(line("  U  d.txt (deleted by them)"));
        until(() -> "d.txt (deleted by them)".equals(c.getTitle()));
        onEdt(() -> {
            assertEquals("d.txt (ours)", c.getOld().getTitle());
            assertEquals(List.of("d ours"), lines(c.getOld()));
        });
    }

    @Test
    public void aFileAtACommitIsItsRepositorys() throws Exception {
        repository();
        final RevisionBuffer revision = new RevisionBuffer(repo(), "a.txt", "HEAD", "one\n");
        assertEquals(List.of(repo()), VcsStatusMode.repositoriesFor(revision));
    }

    // a.txt of sixty numbered lines, line 50 changed.
    private void sixty() throws Exception {
        git = GitFixture.init(tmp);
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++)
            sb.append("line ").append(i).append('\n');
        git.write("a.txt", sb.toString());
        git.commitAll("first");
        git.write("a.txt", sb.toString().replace("line 50\n", "line fifty\n"));
    }

    @Test
    public void aChangeAboveAHunkLeavesItClosedAndTheCaretOnIt() throws Exception {
        sixty();
        open();
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> text().contains("  -line 50"));
        onEdt(() -> {
            status.toggle(line("  @@ -47,7 +47,7 @@ line 46"));
            h.editor().setDot(line("  @@ -47,7 +47,7 @@ line 46"), 4);
        });
        git.write("a.txt", git.read("a.txt").replace("line 5\n", "line five\n"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("  -line 5"));
        onEdt(() -> {
            assertFalse(text().contains("  -line 50"), "the hunk closed stays closed");
            assertSame(line("  @@ -47,7 +47,7 @@ line 46"), h.editor().getDotLine());
            assertEquals(4, h.editor().getDotOffset());
        });
    }

    @Test
    public void aRefreshKeepsTheSelection() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setMark(new org.armedbear.j.Position(line("  M  a.txt"), 2));
            h.editor().setDot(line("  M  b.txt"), 0);
            status.refresh(); // Nothing changed.
        });
        Thread.sleep(300);
        onEdt(() -> assertSame(line("  M  a.txt"), h.editor().getMark().getLine()));
        git.write("d.txt", "another\n");
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("  ?  d.txt"));
        onEdt(() -> {
            assertSame(line("  M  a.txt"), h.editor().getMark().getLine(), "and when something did");
            assertEquals(2, h.editor().getMark().getOffset());
        });
    }

    @Test
    public void aRenameWithNoChangeSaysSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("mv", "a.txt", "b.txt");
        open();
        onEdt(() -> status.toggle(line("  R  a.txt -> b.txt")));
        until(() -> text().contains("  Renamed, with no change to its content"));
    }

    @Test
    public void aBinaryFileSaysSo() throws Exception {
        git = GitFixture.init(tmp);
        java.nio.file.Files.write(git.root.resolve("b.bin"), new byte[] { 0, 1, 2 });
        git.commitAll("first");
        java.nio.file.Files.write(git.root.resolve("b.bin"), new byte[] { 0, 1, 3 });
        open();
        onEdt(() -> status.toggle(line("  M  b.bin")));
        until(() -> text().contains("  Binary file"));
    }

    @Test
    public void aRepositoryThatIsGoneShowsGitsError() throws Exception {
        repository();
        open();
        try (var paths = java.nio.file.Files.walk(git.root.resolve(".git"))) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().get(0).startsWith("Error: "));
    }

    @Test
    public void aDetachedHeadSaysSo() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("checkout", "-q", "--detach");
        open();
        assertTrue(text().get(0).startsWith("Head:     (detached)  "), text().get(0));
    }

    @Test
    public void anEmptySideStartsAfterTheLineItNames() {
        // -5,0 +6,2: two lines added after old line 5.
        final List<Hunk> added = List.of(new Hunk(5, 0, 6, 2, "", List.of()));
        assertEquals(5, VcsStatusMode.throughHunks(added, 5));
        assertEquals(8, VcsStatusMode.throughHunks(added, 6));
    }

    @Test
    public void aStagedLineMapsThroughTheChangesSince() {
        // The working tree has two lines more before line 10, and line 20 is in a hunk.
        final List<Hunk> unstaged = List.of(new Hunk(3, 1, 3, 3, "", List.of()), new Hunk(19, 3, 21, 1, "", List.of()));
        assertEquals(2, VcsStatusMode.throughHunks(unstaged, 2));
        assertEquals(12, VcsStatusMode.throughHunks(unstaged, 10));
        assertEquals(21, VcsStatusMode.throughHunks(unstaged, 20));
        assertEquals(30, VcsStatusMode.throughHunks(unstaged, 30), "two lines more, then two fewer");
    }

    @Test
    public void sectionsAreWhereVimsSectionMotionsGo() throws Exception {
        repository();
        open();
        onEdt(() -> {
            final Line top = status.getFirstLine();
            assertEquals("Untracked files (1)", VcsStatusMode.getMode().sectionFrom(top, true).getText());
            final Line staged = line("Staged changes (1)");
            assertEquals("Unstaged changes (1)", VcsStatusMode.getMode().sectionFrom(staged, false).getText());
        });
        assertNotNull(status.getData(repo()));
    }

    @Test
    public void severalRepositoriesEachGetAHeading() {
        final VcsRepository a = Repositories.forDirectory(tmp); // Not a repository: null.
        assertEquals(null, a);
        final StatusData data = new StatusData(List.of(new StatusData.Field("Head", "main")), null, List.of(), null);
        final List<StatusLine> lines = StatusRenderer.render(
            List.of(new StatusRenderer.Part(fake("/r/one"), data, 0), new StatusRenderer.Part(fake("/r/two"), data, 1)),
            (key, kind) -> true,
            key -> true,
            StatusRenderer::hunkIds);
        final List<String> text = lines.stream().map(Line::getText).toList();
        assertEquals("one  /r/one", text.get(0));
        assertEquals(Kind.REPOSITORY, lines.get(0).kind());
        assertTrue(text.contains("two  /r/two"));
        assertEquals(1, lines.get(text.indexOf("two  /r/two")).repositoryColor());
    }

    // The diff beside.

    private List<String> paneText() {
        final List<String> list = new ArrayList<>();
        for (Line l = status.getDiff().getFirstLine(); l != null; l = l.next())
            list.add(l.getText());
        return list;
    }

    // The caret on line, and the diff beside told, as after the caret rests.
    private void caretOn(StatusLine line) throws Exception {
        onEdt(() -> {
            h.editor().setDot(line, 0);
            status.followCaret();
        });
    }

    @Test
    public void theDiffBesideIsAPartOfTheStatusAndFollowsItsCaret() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        final StatusDiffBuffer diff = status.getDiff();
        assertEquals(List.of(diff), status.getChildren());
        assertSame(status, status.getFrameView());
        assertSame(status, diff.getPrimary());
        assertFalse(diff.isPrimary());
        // A file the status has closed: its diff is read for the pane.
        caretOn(line("  M  a.txt"));
        until(() -> paneText().contains("  +three blind cats"));
        assertTrue(paneText().contains("  -three blind mice"));
        // The staged one's is the next commit's.
        caretOn(line("  M  b.txt"));
        until(() -> paneText().contains("  +b changed"));
        // A line of no file leaves it as it is.
        caretOn((StatusLine) status.getFirstLine());
        assertTrue(paneText().contains("  +b changed"));
    }

    @Test
    public void theDiffBesideOfAClosedFileIsReadAgainOnARefresh() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(line("  M  a.txt"));
        until(() -> paneText().contains("  +three blind cats"));
        // Still M a.txt, so the status shows nothing new; the diff is.
        git.write("a.txt", TEN.replace("mice", "dogs"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> paneText().contains("  +three blind dogs"));
    }

    @Test
    public void theDiffBesideKeepsTheStatusesHunkKeys() throws Exception {
        repository();
        open();
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> text().contains("  +three blind cats"));
        StatusLine hunk = null;
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.HUNK)
                hunk = s;
        assertNotNull(hunk);
        final String key = hunk.key();
        onEdt(() -> status.openDiff(null));
        caretOn(hunk);
        until(() -> paneText().contains("  +three blind cats"));
        boolean found = false;
        for (Line l = status.getDiff().getFirstLine(); l != null; l = l.next())
            found |= l instanceof StatusLine s && s.key().equals(key);
        assertTrue(found, "the hunk keyed as the status's, to scroll to");
    }

    @Test
    public void closingTheDiffBesideKillsItAndLeavesTheStatus() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        final StatusDiffBuffer diff = status.getDiff();
        onEdt(() -> status.closeDiff(null));
        assertNull(status.getDiff());
        assertNull(status.getFrameView());
        assertFalse(Editor.getBufferList().contains(diff));
        // Killed on its own, as from the buffer list, it closes as D does.
        onEdt(() -> status.openDiff(null));
        final StatusDiffBuffer again = status.getDiff();
        onEdt(again::kill);
        assertNull(status.getDiff());
        assertFalse(Editor.getBufferList().contains(again));
        assertTrue(Editor.getBufferList().contains(status));
    }

    @Test
    public void closingTheStatusClosesTheDiffBeside() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        final StatusDiffBuffer diff = status.getDiff();
        onEdt(status::kill);
        assertFalse(Editor.getBufferList().contains(diff));
        assertFalse(Editor.getBufferList().contains(status));
    }

    // Stashes.

    // a.txt's change stashed, the status read again: the stash's line.
    private StatusLine stashed() throws Exception {
        git.git("stash", "push", "-q", "-m", "saved", "--", "a.txt");
        repo().invalidate();
        repo().refresh();
        until(() -> text().contains("  stash@{0}: On main: saved"));
        return line("  stash@{0}: On main: saved");
    }

    @Test
    public void aStashIsAfterStagedAndOpensToItsFilesAndTheirDiffs() throws Exception {
        repository();
        git.git("stash", "push", "-q", "-m", "saved", "--", "a.txt");
        open();
        assertEquals(
            List.of("Untracked files (1)", "Staged changes (1)", "Stashes (1)"),
            sectionTitles().subList(0, 3));
        final StatusLine stash = line("  stash@{0}: On main: saved");
        assertFalse(stash.isOpen(), "closed to begin with");
        assertEquals(git.git("rev-parse", "stash@{0}").strip(), stash.stash().hash());
        onEdt(() -> status.toggle(stash));
        until(() -> text().contains("  M  a.txt"));
        final StatusLine file = line("  M  a.txt");
        assertEquals(StatusData.STASHES, file.section());
        onEdt(() -> status.toggle(file));
        until(() -> text().contains("  -three blind mice"));
        assertTrue(text().contains("  +three blind cats"));
        // Nothing in it is staged or discarded.
        assertEquals("Nothing to stage here", StatusActions.refuse(StatusActions.Op.STAGE, files(file)));
        assertEquals("Nothing to discard here", StatusActions.refuse(StatusActions.Op.DISCARD, files(file)));
    }

    private static Target.Files files(StatusLine l) {
        return new Target.Files(l.repository(), l.section(), List.of(l.file()));
    }

    @Test
    public void xOnAStashDropsItAfterAskingAndSaysItsHash() throws Exception {
        repository();
        open();
        final StatusLine stash = stashed();
        final String id = stash.stash().hash().substring(0, 7);
        final java.util.function.BiPredicate<Editor, String> was = StashCommands.confirm;
        final List<String> asked = new ArrayList<>();
        try {
            StashCommands.confirm = (e, q) -> asked.add(q) && false;
            onEdt(() -> {
                h.editor().setDot(stash, 0);
                StatusActions.run(h.editor(), StatusActions.Op.DISCARD);
            });
            assertEquals(List.of("Drop stash@{0}: On main: saved?"), asked);
            // The Discard button and menu item agree.
            final List<Set<StatusActions.Op>> able = new ArrayList<>();
            onEdt(() -> able.add(StatusActions.able(h.editor())));
            assertEquals(Set.of(StatusActions.Op.DISCARD), able.get(0));
            assertTrue(git.run("rev-parse", "-q", "--verify", "stash@{0}").succeeded(), "kept when not confirmed");
            StashCommands.confirm = (e, q) -> true;
            onEdt(() -> StatusActions.run(h.editor(), StatusActions.Op.DISCARD));
            until(() -> !sectionTitles().contains("Stashes (1)"));
            assertFalse(git.run("rev-parse", "-q", "--verify", "stash@{0}").succeeded());
            until(() -> h.status().contains("git stash apply " + id));
        }
        finally {
            StashCommands.confirm = was;
        }
    }

    @Test
    public void enterOnAStashShowsItsDetailsBesideItsParentsLabeled() throws Exception {
        repository();
        open();
        final StatusLine stash = stashed();
        onEdt(() -> {
            h.editor().setDot(stash, 0);
            VcsStatusMode.vcsStatusVisit(h.editor());
        });
        caretOn(stash);
        until(() -> status.details() != null && lines(status.details().panes().get(0)).contains("  M  a.txt"));
        final List<String> files = lines(status.details().panes().get(0));
        final String on = git.git("rev-parse", "--short=7", "stash@{0}^1").strip();
        final String index = git.git("rev-parse", "--short=7", "stash@{0}^2").strip();
        assertTrue(files.contains("Stash:    " + on + " (on)  " + index + " (index)"), files.toString());
        // Its files' lines follow it too.
        onEdt(() -> status.toggle(stash));
        until(() -> text().contains("  M  a.txt"));
        final CommitDetails details = status.details();
        caretOn(line("  M  a.txt"));
        assertSame(details, status.details());
    }

    // A commit's details.

    // The first commit's line.
    private StatusLine commitLine() {
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.COMMIT)
                return s;
        throw new AssertionError("no commit in " + text());
    }

    @Test
    public void onACommitTheDiffBesideIsItsDetailsAsTheLogShowsThem() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(commitLine());
        until(() -> status.details() != null && lines(status.details().panes().get(0)).contains("  first"));
        final CommitDetails details = status.details();
        assertEquals(details.panes(), status.getChildren());
        final List<String> files = lines(details.panes().get(0));
        assertTrue(files.contains("Commit:   " + git.git("rev-parse", "HEAD").strip()), files.toString());
        assertTrue(files.contains("  A  a.txt"), files.toString());
        until(() -> lines(details.panes().get(1)).contains("  +three blind mice"));

        // A file at the caret: its diff again, the details gone.
        caretOn(line("  M  a.txt"));
        assertNull(status.details());
        assertEquals(List.of(status.getDiff()), status.getChildren());
        for (Buffer b : details.panes())
            assertFalse(Editor.getBufferList().contains(b));
        until(() -> paneText().contains("  +three blind cats"));
    }

    @Test
    public void aCommitsDetailsAreReadAgainWhenTheRepositoryChanges() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(commitLine());
        until(() -> status.details() != null && lines(status.details().panes().get(0)).contains("  first"));
        assertFalse(lines(status.details().panes().get(0)).get(0).contains("other"));
        git.git("branch", "other");
        repo().invalidate();
        repo().refresh();
        until(() -> lines(status.details().panes().get(0)).get(0).contains("other"));
    }

    @Test
    public void sideBySideOnACommitStaysSideBySideOnAFile() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(commitLine());
        until(() -> status.details() != null);
        onEdt(() -> status.diffView(null, status));
        assertTrue(status.details().isSideBySide());
        until(() -> lines(status.details().comparison()).contains("three blind mice"));
        caretOn(line("  M  a.txt"));
        assertNotNull(status.getCompare(), "= left it side by side");
        // And back: the details side by side too.
        caretOn(commitLine());
        assertTrue(status.details().isSideBySide());
    }

    @Test
    public void closingTheDiffBesideClosesACommitsDetails() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(commitLine());
        until(() -> status.details() != null);
        final List<Buffer> panes = status.details().panes();
        // q in the files, as the log's.
        onEdt(() -> {
            final EditorHarness w = EditorHarness.create();
            try {
                w.showBuffer(panes.get(0));
                LogCommands.vcsLogQuit(w.editor());
            }
            finally {
                w.showBuffer(w.buffer());
                w.close();
            }
        });
        assertNull(status.getDiff());
        assertNull(status.details());
        for (Buffer b : panes)
            assertFalse(Editor.getBufferList().contains(b));
    }

    // Side by side.

    private static List<String> lines(Buffer b) {
        final List<String> list = new ArrayList<>();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            list.add(l.getText());
        return list;
    }

    @Test
    public void sideBySideChangesAreGitsWhereAnotherDiffWouldSlideThem() throws Exception {
        changesAreGits(false, false);
    }

    @Test
    public void sideBySideChangesAreGitsWithWhitespaceIgnored() throws Exception {
        onEdt(() -> org.armedbear.j.mode.compare.DiffWhitespace.choose(org.armedbear.j.util.Whitespace.ALL));
        try {
            changesAreGits(false, false);
            onEdt(
                () -> assertEquals(org.armedbear.j.util.Whitespace.ALL, status.getCompare().getAlignment().ignored()));
        }
        finally {
            onEdt(org.armedbear.j.mode.compare.DiffWhitespace::forget);
        }
    }

    @Test
    public void sideBySideChangesAreGitsWithTheFilesBufferOpen() throws Exception {
        changesAreGits(true, false);
    }

    @Test
    public void sideBySideChangesAreGitsOnceTheStatusReadsTheDiff() throws Exception {
        changesAreGits(false, true);
    }

    @Test
    public void sideBySideChangesAreGitsOnceTheStatusReadsTheBuffersDiff() throws Exception {
        changesAreGits(true, true);
    }

    @Test
    public void sideBySideChangesAreGitsAgainOnceAnEditIsSaved() throws Exception {
        changesAreGits(true, false);
        // Edited, the comparison follows the buffer with its own diff; saved,
        // the status reads git's again, the file open there, and the
        // comparison has its changes.
        onEdt(() -> status.setExpanded(line("  M  a.txt"), true));
        until(() -> text().stream().anyMatch(t -> t.startsWith("  @@ -4,6 +4,11")));
        onEdt(() -> {
            h.showBuffer(file);
            h.editor().setDot(file.getFirstLine(), 0);
            h.editor().insertString("// x\n");
            file.save();
        });
        final CompareBuffer c = status.getCompare();
        // Followed while it is shown.
        onEdt(() -> h.showBuffer(c));
        until(() -> lines(c).contains("// x") && gitsAddedRows().contains(0));
        until(() -> addedRows(c).equals(gitsAddedRows()));
    }

    // The comparison's changed rows on the new side those git's diff adds,
    // shown with the file's buffer open if buffer, and the file closed in
    // the status, its diff unread, at first if closed.
    private void changesAreGits(boolean buffer, boolean closed) throws Exception {
        // The lines added repeat those around them: j's own diff has them
        // from "public K v()" to the "@Override" after, git's from the
        // "@Override" before to the blank line.
        final String old = "class A {\n    @Override\n    public K m() {\n        return a();\n    }\n\n"
                + "    @Override\n    public L s() {\n        return s;\n    }\n}\n";
        final String now = old.replace(
            "\n    @Override\n    public L",
            "\n    @Override\n    public K v() {\n        return b();\n    }\n\n    @Override\n    public L");
        git = GitFixture.init(tmp);
        git.write("a.txt", old);
        git.commitAll("first");
        git.write("a.txt", now);
        open();
        onEdt(() -> {
            if (buffer) {
                file = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
                if (!file.initialized())
                    file.initialize();
                file.load();
            }
            if (closed)
                status.setDepth(2);
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  a.txt"));
        until(() -> lines(c).contains("    public K v() {") && (!buffer || "a.txt (buffer)".equals(c.getTitle())));
        if (closed)
            onEdt(() -> status.setExpanded(line("  M  a.txt"), true));
        until(() -> status.getDiff().item() != null && status.getDiff().item().diff() != null);
        until(() -> addedRows(c).equals(gitsAddedRows()));
    }

    // The rows, from 0, git's diff of the file the diff beside shows adds.
    private java.util.Set<Integer> gitsAddedRows() {
        final java.util.Set<Integer> out = new java.util.TreeSet<>();
        for (org.armedbear.j.vcs.diff.Hunk hunk : status.getDiff().item().diff().hunks()) {
            int n = hunk.newStart() - 1;
            for (org.armedbear.j.vcs.diff.DiffLine d : hunk.lines()) {
                if (d.isAdded())
                    out.add(n);
                if (!d.isRemoved())
                    n++;
            }
        }
        return out;
    }

    // The new side's rows, from 0, c shows changed.
    private static java.util.Set<Integer> addedRows(CompareBuffer c) {
        final java.util.Set<Integer> out = new java.util.TreeSet<>();
        for (org.armedbear.j.mode.compare.DiffAlignment.Row row : c.getAlignment().rows())
            if (row.kind() != org.armedbear.j.mode.compare.DiffAlignment.Kind.SAME && row.newLine() >= 0)
                out.add(row.newLine());
        return out;
    }

    @Test
    public void aNewFilesBoxIsAtItsFirstRow() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.write("n.txt", "one\ntwo\nthree\n");
        git.git("add", "n.txt");
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  A  n.txt"));
        until(() -> lines(c).contains("three"));
        onEdt(() -> {
            assertNotNull(status.comparedPicks().boxAt(false, 0));
            assertFalse(status.comparedPicks().boxAt(false, 0).line());
            assertNull(status.comparedPicks().boxAt(false, 2));
        });
    }

    @Test
    public void aHunkIsPickedSideBySideAtItsRow() throws Exception {
        repository();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  a.txt"));
        until(() -> lines(c).contains("three blind cats"));
        onEdt(() -> {
            assertTrue(c.getGutterColumns().stream().anyMatch(col -> col instanceof ComparedPickColumn));
            // ; picks, but in vim it is its own, repeating an f or t; on a selection it picks.
            assertEquals("vcsStatusPick", c.getKeyMapForMode().lookup(';', 0, 0).getCommand());
            assertNull(c.getModalKeyMapForMode().lookup(';', 0, 0));
            assertEquals(
                "vcsStatusPick",
                c.getModalKeyMapForMode().lookup('\0', java.awt.event.KeyEvent.VK_INSERT, 0).getCommand());
            assertEquals("vcsStatusPick", c.getVisualKeyMapForMode().lookup(';', 0, 0).getCommand());
            // The unified diff the same.
            assertEquals(
                "vcsStatusPick",
                VcsStatusMode.getMode().getKeyMap(status.getDiff()).lookup(';', 0, 0).getCommand());
            assertNull(VcsStatusMode.getMode().getModalKeyMap(status.getDiff()).lookup(';', 0, 0));
            final int row = lines(c).indexOf("three blind cats");
            h.showBuffer(c);
            h.editor().setDot(c.getLine(row), 0);
            VcsStatusMode.vcsStatusPick(h.editor());
            // Its box at the change, not at its context above; on the new
            // side alone, the change adding a line.
            assertNotNull(status.comparedPicks().boxAt(false, row));
            assertNull(status.comparedPicks().boxAt(true, lines(c.getOld()).indexOf("three blind mice")));
            assertNull(status.comparedPicks().boxAt(false, 0));
            assertEquals(org.armedbear.j.util.PickState.ALL, status.comparedPicks().boxAt(false, row).state());
            assertFalse(status.picks().isEmpty());
            // Again, unpicked.
            VcsStatusMode.vcsStatusPick(h.editor());
            assertTrue(status.picks().isEmpty());
            h.showBuffer(status);
        });
    }

    @Test
    public void aSelectionSideBySidePicksTheLinesItCovers() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", TEN);
        git.commitAll("first");
        git.write("a.txt", TEN.replace("two\n", "two\nTWO\nTOO\n").replace("four\n", "four\nFOUR\n"));
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  a.txt"));
        until(() -> lines(c).contains("FOUR"));
        onEdt(() -> {
            h.showBuffer(c);
            final RowPicks picks = status.comparedPicks();
            final int two = lines(c).indexOf("TWO");
            final int too = lines(c).indexOf("TOO");
            final int four = lines(c).indexOf("FOUR");
            // One hunk of git's, two changes, each with its box.
            assertEquals(picks.boxAt(false, two).id(), picks.boxAt(false, four).id(), "one hunk");
            assertFalse(picks.boxAt(false, four).line());
            assertNull(picks.boxAt(false, too));
            // A selection over TWO alone picks that line, not its change.
            h.editor().setMark(new Position(c.getLine(two), 0));
            h.editor().setDot(c.getLine(two), 3);
            VcsStatusMode.vcsStatusPick(h.editor());
            assertNull(h.editor().getMark());
            assertTrue(picks.boxAt(false, two).line());
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.boxAt(false, two).state());
            assertEquals(
                org.armedbear.j.util.PickState.NONE,
                picks.boxAt(false, too).state(),
                "a box at each changed row while the change is picked in part");
            assertFalse(picks.boxAt(false, four).line(), "the other change's box its own");
            // A changed row's gutter shaded, a picked one's brighter.
            final java.awt.Color picked = c.getFormatter().getGutterBackground(c.getLine(two));
            final java.awt.Color not = c.getFormatter().getGutterBackground(c.getLine(four));
            assertNotNull(not);
            assertNotEquals(not, picked);
            assertNull(c.getFormatter().getGutterBackground(c.getLine(0)), "an unchanged row's is the display's");
            // A click on TOO: the change picked whole, its box one again,
            // with a line from it down through its changes.
            final org.armedbear.j.GutterColumn col = c.getGutterColumns()
                    .stream()
                    .filter(g -> g instanceof ComparedPickColumn)
                    .findFirst()
                    .orElseThrow();
            picks.toggle(picks.boxAt(false, too));
            assertFalse(picks.boxAt(false, two).line());
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.boxAt(false, two).state());
            assertNull(picks.boxAt(false, too));
            assertEquals(org.armedbear.j.util.PickColumn.Trail.FROM, picks.trailAt(false, false, two));
            assertEquals(org.armedbear.j.util.PickColumn.Trail.TO, picks.trailAt(false, false, too));
            assertNull(picks.trailAt(false, false, too + 1));
            assertNull(picks.trailAt(false, false, four), "the other change unpicked");
            // A Shift click on FOUR: every change picked, the hunk is.
            col.shiftClicked(h.editor(), c.getLine(four), 0, 0);
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.boxAt(false, four).state());
            h.showBuffer(status);
        });
        // The file closed in the status: its diff read for the pick.
        until(() -> !status.picks().unread(status.model()));
        onEdt(() -> assertEquals("a hunk of a.txt", StatusActions.names(StatusActions.targets(status, h.editor()))));
    }

    @Test
    public void aModifiedLineIsPickedWithItsRemovalAndADeletionOnTheOldSide() throws Exception {
        git = GitFixture.init(tmp);
        final StringBuilder thirty = new StringBuilder();
        for (int i = 1; i <= 30; i++)
            thirty.append('l').append(i).append('\n');
        git.write("a.txt", thirty.toString());
        git.commitAll("first");
        git.write("a.txt", thirty.toString().replace("l2\nl3\n", "TWO\n").replace("l20\n", ""));
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  a.txt"));
        until(() -> lines(c).contains("TWO"));
        onEdt(() -> {
            h.showBuffer(c);
            final RowPicks picks = status.comparedPicks();
            final int two = lines(c).indexOf("TWO");
            final int l2 = lines(c.getOld()).indexOf("l2");
            final int l3 = lines(c.getOld()).indexOf("l3");
            final int l20 = lines(c.getOld()).indexOf("l20");
            // A change adding lines has its box on the new side; one that
            // adds none, on the old.
            final String modified = picks.boxAt(false, two).id();
            assertFalse(picks.boxAt(false, two).line());
            assertNull(picks.boxAt(true, l2));
            final String deleted = picks.boxAt(true, l20).id();
            assertFalse(picks.boxAt(true, l20).line());
            assertNull(picks.boxAt(false, lines(c).indexOf("l21")));
            // The change picked, then TWO unpicked: l2, removed for it, with it.
            h.editor().setDot(c.getLine(two), 0);
            VcsStatusMode.vcsStatusPick(h.editor());
            assertTrue(picks.isPicked(true, l2));
            h.editor().setMark(new Position(c.getLine(two), 0));
            h.editor().setDot(c.getLine(two), 3);
            VcsStatusMode.vcsStatusPick(h.editor());
            assertFalse(picks.isPicked(false, two));
            assertFalse(picks.isPicked(true, l2), "a modified line's removal unpicked with it");
            assertTrue(picks.isPicked(true, l3));
            // l3, with no line added for it, has its box on the old side.
            assertNull(picks.boxAt(true, l2));
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.boxAt(true, l3).state());
            assertEquals(modified, picks.boxAt(true, l3).id());
            picks.toggle(picks.boxAt(true, l3));
            assertTrue(status.picks().isEmpty());
            // The deletion's box picks it.
            picks.toggle(picks.boxAt(true, l20));
            assertTrue(picks.isPicked(true, l20));
            assertEquals(org.armedbear.j.util.PickState.ALL, picks.boxAt(true, l20).state());
            assertEquals(deleted, picks.boxAt(true, l20).id());
            // The signs' rows: a modified line's removals on its addition's
            // row, past it the last's; a deletion's, the row above.
            final java.util.Map<String, org.armedbear.j.vcs.diff.Hunk> ids =
                    status.picks().ids(status.getDiff().item().diff());
            final org.armedbear.j.vcs.diff.Hunk m = ids.get(modified);
            final int[] rows = StatusPicks.signRows(m);
            for (int i = 0; i < m.lines().size(); i++)
                if (!m.lines().get(i).isContext())
                    assertEquals(two, rows[i], m.lines().get(i).text());
            final org.armedbear.j.vcs.diff.Hunk d = ids.get(deleted);
            for (int i = 0; i < d.lines().size(); i++)
                if (d.lines().get(i).isRemoved())
                    assertEquals(lines(c).indexOf("l19"), StatusPicks.signRows(d)[i]);
            h.showBuffer(status);
        });
    }

    @Test
    public void fromItsPartsTheStatussOwnCommandsRunInTheStatusAndTheRestWhereChosen() throws Exception {
        repository();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        assertNotNull(c);
        for (Buffer side : List.of(c, c.getOld())) {
            assertTrue(VcsStatusBuffer.runsInStatus("vcsStage", side));
            assertFalse(VcsStatusBuffer.runsInStatus("copyRegion", side), "Edit > Copy copies from the side");
            assertFalse(VcsStatusBuffer.runsInStatus("find", side));
            assertFalse(VcsStatusBuffer.runsInStatus("vcsStatusDiffView", side), "= is the side's own");
            assertFalse(VcsStatusBuffer.runsInStatus("vcsStatusDiff", side), "q too");
        }
        onEdt(() -> status.diffView(null, status));
        final StatusDiffBuffer d = status.getDiff();
        assertNotNull(d);
        assertFalse(VcsStatusBuffer.runsInStatus("vcsStage", d), "the hunk at the diff's caret");
        assertTrue(VcsStatusBuffer.runsInStatus("vcsStageAll", d));
        assertTrue(VcsStatusBuffer.runsInStatus("vcsStatusDepth", d));
        assertTrue(VcsStatusBuffer.runsInStatus("vcsCommitAmend", d));
        assertFalse(VcsStatusBuffer.runsInStatus("copyRegion", d));
    }

    @Test
    public void sideBySideTheComparisonIsAPartOfTheStatusAndFollowsItsCaret() throws Exception {
        repository();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        assertNotNull(c);
        assertEquals(List.of(c, c.getOld()), status.getChildren());
        assertSame(status, c.getFrameView());
        assertSame(status, c.getOld().getFrameView());
        assertFalse(c.isPrimary());
        // Not staged, and in no buffer: what is staged against the file.
        caretOn(line("  M  a.txt"));
        until(() -> lines(c).contains("three blind cats"));
        onEdt(() -> {
            assertTrue(lines(c.getOld()).contains("three blind mice"));
            assertEquals("a.txt (staged)", c.getOld().getTitle());
            assertEquals("a.txt", c.getTitle());
        });
        // Staged: HEAD against what is staged.
        caretOn(line("  M  b.txt"));
        until(() -> lines(c).contains("b changed"));
        onEdt(() -> {
            assertEquals(List.of("b"), lines(c.getOld()));
            assertEquals("b.txt (HEAD)", c.getOld().getTitle());
            assertEquals("b.txt (staged)", c.getTitle());
        });
        // Unified again: the comparison closes, the diff is the part.
        onEdt(() -> status.diffView(null, c));
        assertNull(status.getCompare());
        assertTrue(c.isClosed());
        assertFalse(Editor.getBufferList().contains(c.getOld()));
        assertEquals(List.of(status.getDiff()), status.getChildren());
    }

    @Test
    public void sideBySideAFilesOpenBufferIsFollowed() throws Exception {
        repository();
        open();
        onEdt(() -> {
            file = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  a.txt"));
        until(() -> "a.txt (buffer)".equals(c.getTitle()));
        onEdt(() -> {
            assertTrue(lines(c).contains("three blind cats"));
            // Shown, as the comparison follows its buffer only then.
            other = EditorHarness.create();
            other.showBuffer(c);
            file.withWriteLock(() -> {
                file.getFirstLine().setText("ONE");
                file.modified();
            });
        });
        until(() -> lines(c).get(0).equals("ONE"));
    }

    @Test
    public void sideBySideAFileDeletedOnDiskIsNotItsOpenBuffer() throws Exception {
        repository();
        open();
        onEdt(() -> {
            file = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
        });
        Files.delete(git.root.resolve("a.txt"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("  D  a.txt"));
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  D  a.txt"));
        until(() -> "a.txt".equals(c.getTitle()));
        onEdt(() -> {
            assertEquals(List.of(""), lines(c), "nothing on disk");
            assertFalse(c.getAlignment().changes().isEmpty(), "every line deleted");
        });
    }

    @Test
    public void sideBySideAFileDeletedWhileItsBufferIsFollowedIsComparedFromTheDisk() throws Exception {
        repository();
        open();
        onEdt(() -> {
            file = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  a.txt"));
        until(() -> "a.txt (buffer)".equals(c.getTitle()));
        Files.delete(git.root.resolve("a.txt"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().contains("  D  a.txt"));
        until(() -> "a.txt".equals(c.getTitle()));
        onEdt(() -> {
            assertFalse(c.isFollowing());
            assertEquals(List.of(""), lines(c));
        });
    }

    @Test
    public void aChangeOnlyToWhitespaceSaysSoWhileItIsIgnored() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", "x\ny\n");
        git.commitAll("first");
        git.write("a.txt", "x\ny \n");
        onEdt(() -> DiffWhitespace.choose(Whitespace.ALL));
        try {
            open();
            onEdt(() -> status.setDepth(4));
            until(() -> text().contains("  Only whitespace changed, which is ignored"));
            assertFalse(text().stream().anyMatch(t -> t.contains("No diff")));
        }
        finally {
            onEdt(DiffWhitespace::forget);
        }
    }

    @Test
    public void sideBySideAKilledSideClosesTheDiffAsDDoes() throws Exception {
        repository();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        final StatusDiffBuffer diff = status.getDiff();
        onEdt(() -> c.getOld().kill());
        assertNull(status.getDiff());
        assertNull(status.getCompare());
        assertTrue(c.isClosed());
        assertFalse(Editor.getBufferList().contains(c));
        assertFalse(Editor.getBufferList().contains(diff));
        assertTrue(Editor.getBufferList().contains(status));
        // D again: side by side still.
        onEdt(() -> status.openDiff(null));
        assertNotNull(status.getCompare());
    }

    @Test
    public void equalsAndQInTheComparisonAreTheStatuses() throws Exception {
        repository();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
            other = EditorHarness.create();
            other.showBuffer(status.getCompare().getOld());
        });
        final CompareBuffer c = status.getCompare();
        onEdt(() -> {
            assertEquals("vcsStatusDiffView", c.getOld().getKeyMapForMode().lookup('=', 0, 0).getCommand());
            assertEquals("vcsStatusDiffView", c.getModalKeyMapForMode().lookup('=', 0, 0).getCommand());
            assertEquals("vcsStatusDiff", c.getKeyMapForMode().lookup('q', 0, 0).getCommand());
            assertEquals("vcsStatusDiff", c.getModalKeyMapForMode().lookup('q', 0, 0).getCommand());
            assertEquals("vcsStatusDiff", status.getKeyMapForMode().lookup('D', 0, 0).getCommand());
            assertEquals(
                "vcsStatusVisit",
                c.getOld().getKeyMapForMode().lookup('\0', java.awt.event.KeyEvent.VK_ENTER, 0).getCommand());
            assertEquals("vcsStatusDiffView", status.getDiff().getModalKeyMapForMode().lookup('=', 0, 0).getCommand());
            assertEquals("vcsStatusDiffView", status.getModalKeyMapForMode().lookup('=', 0, 0).getCommand());
            assertEquals("vcsStatusDiffView", status.getKeyMapForMode().lookup('=', 0, 0).getCommand());
            assertSame(status, VcsStatusMode.owner(other.editor()));
        });
    }

    @Test
    public void equalsFromTheStatusOpensTheDiffAsItWasLastShown() throws Exception {
        repository();
        open();
        onEdt(() -> status.diffView(null, status));
        assertNotNull(status.getDiff());
        assertNull(status.getCompare(), "unified at first");
        onEdt(() -> {
            status.diffView(null, status);
            assertNotNull(status.getCompare());
            status.closeDiff(null);
            status.diffView(null, status);
            assertNotNull(status.getCompare(), "side by side, as last shown");
        });
    }

    // The diff line at the caret of the diff's window.
    private String diffCaret() {
        return other.editor().getDotLine().getText();
    }

    @Test
    public void sideBySideStartsAtTheLineTheUnifiedDiffIsOn() throws Exception {
        repository();
        git.write("a.txt", TEN.replace("one", "uno").replace("mice", "cats"));
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(line("  M  a.txt"));
        until(() -> paneText().contains("  +three blind cats"));
        final EditorHarness[] sides = new EditorHarness[2];
        onEdt(() -> {
            other = EditorHarness.create();
            other.showBuffer(status.getDiff());
            for (Line l = status.getDiff().getFirstLine(); l != null; l = l.next())
                if (l.getText().equals("  -three blind mice"))
                    other.editor().setDot(l, 0);
            status.diffView(null, status.getDiff());
            // Shown before the comparison is read, as the layout would.
            final CompareBuffer c = status.getCompare();
            sides[0] = EditorHarness.create();
            sides[0].showBuffer(c.getOld());
            sides[1] = EditorHarness.create();
            sides[1].showBuffer(c);
            windows.addAll(List.of(sides));
        });
        final CompareBuffer c = status.getCompare();
        until(() -> lines(c).contains("three blind cats"));
        onEdt(() -> {
            assertEquals(
                "three blind mice",
                sides[0].editor().getDotLine().getText(),
                "a removed line, on the old side");
            assertEquals("three blind cats", sides[1].editor().getDotLine().getText());
        });
    }

    @Test
    public void theUnifiedDiffGoesToTheLineASideIsOn() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(line("  M  a.txt"));
        until(() -> paneText().contains("  +three blind cats"));
        onEdt(() -> {
            other = EditorHarness.create();
            other.showBuffer(status.getDiff());
            // Line 3 of the old file, then of the new.
            status.getDiff().placeAt(true, 3);
            assertEquals("  -three blind mice", diffCaret());
            status.getDiff().placeAt(false, 3);
            assertEquals("  +three blind cats", diffCaret());
            // Out of every hunk: the nearest line there is.
            status.getDiff().placeAt(false, 10);
            assertEquals("   six", diffCaret());
        });
    }

    @Test
    public void reopenedSideBySideShowsTheFileAgain() throws Exception {
        repository();
        open();
        onEdt(() -> {
            file = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            status.openDiff(null);
            status.diffView(null, status);
        });
        caretOn(line("  M  a.txt"));
        until(() -> "a.txt (buffer)".equals(status.getCompare().getTitle()));
        onEdt(() -> {
            status.closeDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        assertNotNull(c);
        caretOn(line("  M  a.txt"));
        until(() -> lines(c).contains("three blind cats"));
        onEdt(() -> assertTrue(status.comparesDiffFile()));
    }

    @Test
    public void compareQuitInTheStatusesComparisonClosesTheDiff() throws Exception {
        repository();
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        onEdt(() -> {
            other = EditorHarness.create();
            other.showBuffer(c);
            org.armedbear.j.mode.compare.CompareCommands.compareQuit(other.editor());
        });
        assertTrue(c.isClosed());
        assertFalse(Editor.getBufferList().contains(c));
        assertFalse(Editor.getBufferList().contains(c.getOld()));
        assertNull(status.getDiff(), "closed as q closes it");
    }

    @Test
    public void aBinaryFileIsNotComparedAsText() throws Exception {
        git = GitFixture.init(tmp);
        java.nio.file.Files.write(git.root.resolve("x.bin"), new byte[] { 'a', 0, 'b', '\n' });
        git.commitAll("first");
        java.nio.file.Files.write(git.root.resolve("x.bin"), new byte[] { 'a', 0, 'c', '\n' });
        open();
        onEdt(() -> {
            status.openDiff(null);
            status.diffView(null, status);
        });
        final CompareBuffer c = status.getCompare();
        caretOn(line("  M  x.bin"));
        until(() -> c.getTitle().equals("x.bin (binary)"));
        onEdt(() -> {
            assertEquals(List.of(""), lines(c));
            assertEquals("x.bin (binary)", c.getOld().getTitle());
        });
    }

    @Test
    public void placingTheUnifiedDiffDropsItsSelection() throws Exception {
        repository();
        open();
        onEdt(() -> status.openDiff(null));
        caretOn(line("  M  a.txt"));
        until(() -> paneText().contains("  +three blind cats"));
        onEdt(() -> {
            other = EditorHarness.create();
            other.showBuffer(status.getDiff());
            other.editor().setMark(new org.armedbear.j.Position(status.getDiff().getFirstLine(), 0));
            status.getDiff().placeAt(false, 3);
            assertNull(other.editor().getMark());
        });
    }

    private static VcsRepository fake(String root) {
        try {
            final var c = VcsRepository.class.getDeclaredConstructor(Path.class, org.armedbear.j.vcs.VcsBackend.class);
            c.setAccessible(true);
            return c.newInstance(Path.of(root), new org.armedbear.j.vcs.git.GitBackend());
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
