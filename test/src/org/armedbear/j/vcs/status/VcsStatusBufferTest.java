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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class VcsStatusBufferTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private VcsStatusBuffer status;

    @AfterEach
    public void tearDown() throws Exception {
        if (status != null) {
            status.dispose();
            Editor.getBufferList().remove(status);
        }
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
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
        assertTrue(text().contains("  U  a.txt"), text().toString());
        assertFalse(line("  U  a.txt").isExpandable());
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
            key -> true);
        final List<String> text = lines.stream().map(Line::getText).toList();
        assertEquals("one  /r/one", text.get(0));
        assertEquals(Kind.REPOSITORY, lines.get(0).kind());
        assertTrue(text.contains("two  /r/two"));
        assertEquals(1, lines.get(text.indexOf("two  /r/two")).repositoryColor());
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
