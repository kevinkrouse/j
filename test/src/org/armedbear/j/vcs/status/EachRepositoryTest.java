/*
 * EachRepositoryTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Position;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Committing, fetching, stashing and merging in a status of three repositories. */
public class EachRepositoryTest {
    @TempDir
    Path tmp;

    private static final BranchCommands.Choose CHOOSE = BranchCommands.choose;

    private final List<GitFixture> fs = new ArrayList<>();
    private final List<VcsRepository> rs = new ArrayList<>();
    private EditorHarness h;
    private VcsStatusBuffer status;

    @AfterEach
    public void tearDown() throws Exception {
        CommitCommands.show = (editor, buffer) -> editor.activateInOtherWindow(buffer);
        BranchCommands.choose = CHOOSE;
        onEdt(() -> {
            final List<Buffer> buffers = new ArrayList<>();
            for (Buffer b : Editor.getBufferList())
                buffers.add(b);
            for (Buffer b : buffers)
                if (b instanceof VcsStatusBuffer || b instanceof CommitMessageBuffer) {
                    b.dispose();
                    Editor.getBufferList().remove(b);
                }
        });
        GitFixture.reset();
    }

    // x, y and z, each on main at a commit of f.txt.
    private void three() throws Exception {
        for (String name : List.of("x", "y", "z")) {
            final GitFixture f = GitFixture.init(Files.createDirectory(tmp.resolve(name)));
            f.write("f.txt", "one\n");
            f.commitAll("one");
            fs.add(f);
        }
    }

    // A status of the three, read, its caret at the top, its popups' -a on.
    private void open() throws Exception {
        for (GitFixture f : fs) {
            final VcsRepository r = Repositories.forDirectory(f.root);
            r.invalidate();
            rs.add(r);
        }
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        CommitCommands.show = (editor, buffer) -> {
            h.showBuffer(buffer);
            return h.editor();
        };
        onEdt(() -> {
            status = VcsStatusBuffer.of(rs);
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
            for (String id : List.of(FetchCommands.ID, StashCommands.ID, MergeCommands.ID))
                Transients.args(h.editor(), id).setOn(EachRepository.ALL, true);
        });
        until(() -> rs.stream().allMatch(r -> status.getData(r) != null));
    }

    private CommitMessageBuffer message() {
        return h.editor().getBuffer() instanceof CommitMessageBuffer b ? b : null;
    }

    private static String subject(GitFixture f) {
        return f.git("log", "-1", "--format=%s").strip();
    }

    @Test
    public void aCommitSaysItGoesToEachRepositoryWithSomethingStagedAndMakesOneEach() throws Exception {
        three();
        for (GitFixture f : fs.subList(0, 2)) {
            f.write("f.txt", "two\n");
            f.git("add", "f.txt");
        }
        // y's hook refuses the first time.
        final Path hook = fs.get(1).root.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);
        open();
        final Transient popup = CommitCommands.popup(h.editor());
        assertEquals("commit to 2 repositories: x, y", popup.item("c").description());
        assertEquals("amend x's last commit", popup.item("a").description());
        onEdt(() -> CommitCommands.vcsCommit(h.editor()));
        until(() -> message() != null);
        final CommitMessageBuffer m = message();
        assertEquals("git commit: 2 repositories: x, y", m.getTitle());
        assertTrue(m.getText().contains("\n# COMMITS TO 2 REPOSITORIES, ONE EACH: x, y\n"), m.getText());
        assertTrue(m.getText().contains("# x, staged:\n#   M  f.txt\n# y, staged:\n#   M  f.txt"), m.getText());
        assertEquals("commit to 2 repositories: x, y", CommitMessageMode.getMode().getHints(m)[0][1]);
        onEdt(() -> m.insertString(new Position(m.getFirstLine(), 0), "Two"));
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> subject(fs.get(0)).equals("Two") && !m.isCommitting());
        assertEquals("one", subject(fs.get(1)), "the hook refused");
        assertTrue(Editor.getBufferList().contains(m), "kept for y");
        assertEquals(List.of(rs.get(1)), m.getRepositories());
        assertEquals("git commit: y", m.getTitle());
        assertTrue(m.getText().startsWith("Two\n"), m.getText());
        assertTrue(m.getText().contains("# COMMITS TO y ALONE: x, z have nothing staged"), m.getText());
        Files.delete(hook);
        // The status read again since the first.
        until(() -> !status.isWriting());
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        assertEquals("Two", subject(fs.get(1)));
        assertEquals("one", subject(fs.get(2)));
    }

    @Test
    public void aDetachedCommitIsReportedWithItsHash() throws Exception {
        three();
        fs.get(0).git("switch", "-q", "--detach");
        for (GitFixture f : fs.subList(0, 2)) {
            f.write("f.txt", "two\n");
            f.git("add", "f.txt");
        }
        open();
        onEdt(() -> CommitCommands.vcsCommit(h.editor()));
        until(() -> message() != null);
        final CommitMessageBuffer m = message();
        onEdt(() -> m.insertString(new Position(m.getFirstLine(), 0), "Two"));
        onEdt(() -> CommitCommands.vcsCommitFinish(h.editor()));
        until(() -> !Editor.getBufferList().contains(m));
        final String x = fs.get(0).git("rev-parse", "--short", "HEAD").strip();
        final String y = fs.get(1).git("rev-parse", "--short", "HEAD").strip();
        assertEquals("Committed to x (" + x + "), y (" + y + ")", h.status());
    }

    @Test
    public void aCommitToOneOfSeveralSaysItIsThatOneAlone() throws Exception {
        three();
        fs.get(2).write("f.txt", "two\n");
        fs.get(2).git("add", "f.txt");
        open();
        onEdt(() -> h.editor().setDot(status.getFirstLine(), 0));
        assertEquals("commit to z", CommitCommands.popup(h.editor()).item("c").description());
        assertEquals(
            "# COMMITS TO z ALONE: x, y have nothing staged",
            CommitCommands.reach(status, List.of(rs.get(2)), EnumSet.noneOf(CommitOption.class)));
        assertEquals(
            "# CHANGES x'S LAST COMMIT ALONE, not the other repositories'",
            CommitCommands.reach(status, List.of(rs.get(0)), EnumSet.of(CommitOption.AMEND)));
    }

    @Test
    public void aFetchFromTheUpstreamIsMadeInEachThatHasOne() throws Exception {
        three();
        final List<GitFixture> origins = new ArrayList<>();
        for (GitFixture f : fs.subList(0, 2)) {
            final GitFixture o = GitFixture.init(Files.createDirectory(tmp.resolve("o" + origins.size())));
            o.write("f.txt", "origin\n");
            o.commitAll("origin's");
            f.git("remote", "add", "origin", o.root.toString());
            f.git("fetch", "-q", "origin");
            f.git("branch", "-q", "--set-upstream-to=origin/main");
            o.write("g.txt", "new\n");
            o.commitAll("newer");
            origins.add(o);
        }
        open();
        assertEquals(
            "its upstream's remote",
            FetchCommands.popup(new FetchCommands.Remotes(List.of(), null, null), "x", true).item("u").description());
        onEdt(() -> FetchCommands.vcsFetchUpstream(h.editor()));
        until(
            () -> fs.subList(0, 2)
                    .stream()
                    .allMatch(f -> f.git("log", "-1", "--format=%s", "origin/main").strip().equals("newer")));
        until(() -> h.status().startsWith("Fetched"));
        assertEquals("Fetched in x, y; not in z (no remote)", h.status());
        // All remotes: git's --all succeeds with none, but z is said to have none.
        until(() -> !status.isWriting());
        onEdt(() -> h.editor().status(""));
        onEdt(() -> FetchCommands.vcsFetchAll(h.editor()));
        until(() -> h.status().startsWith("Fetched"));
        assertEquals("Fetched in x, y; not in z (no remote)", h.status());
    }

    @Test
    public void aStashIsMadeInEachWithSomethingToStash() throws Exception {
        three();
        fs.get(0).write("f.txt", "x's\n");
        fs.get(1).write("f.txt", "y's\n");
        open();
        onEdt(() -> StashCommands.vcsStashSave(h.editor()));
        until(
            () -> fs.subList(0, 2)
                    .stream()
                    .allMatch(f -> f.run("rev-parse", "-q", "--verify", "stash@{0}").succeeded()));
        assertFalse(fs.get(2).run("rev-parse", "-q", "--verify", "stash@{0}").succeeded(), "z had nothing");
        assertEquals("one\n", fs.get(0).read("f.txt"));
    }

    @Test
    public void aStashsMessageIsKeptToTryAgainWhereItFailed() throws Exception {
        three();
        fs.get(0).write("f.txt", "x's\n");
        fs.get(1).write("f.txt", "y's\n");
        // y's index locked: its stash fails.
        final Path lock = fs.get(1).root.resolve(".git/index.lock");
        Files.writeString(lock, "");
        open();
        onEdt(() -> Transients.args(h.editor(), StashCommands.ID).set("--message=", "wip"));
        onEdt(() -> StashCommands.vcsStashSave(h.editor()));
        until(() -> fs.get(0).run("rev-parse", "-q", "--verify", "stash@{0}").succeeded() && !status.isWriting());
        assertFalse(fs.get(1).run("rev-parse", "-q", "--verify", "stash@{0}").succeeded(), "y's failed");
        assertEquals("wip", Transients.args(h.editor(), StashCommands.ID).get("--message="), "kept");
        Files.delete(lock);
        // x, stashed, has nothing left: y alone.
        until(() -> status.files(rs.get(0), "unstaged").isEmpty());
        onEdt(() -> StashCommands.vcsStashSave(h.editor()));
        until(() -> fs.get(1).run("rev-parse", "-q", "--verify", "stash@{0}").succeeded() && !status.isWriting());
        assertTrue(fs.get(1).git("stash", "list").contains("wip"), fs.get(1).git("stash", "list"));
        assertEquals("1", fs.get(0).git("rev-list", "--walk-reflogs", "--count", "refs/stash").strip(), "x's once");
        until(() -> Transients.args(h.editor(), StashCommands.ID).get("--message=") == null);
    }

    @Test
    public void aMergeByNameIsMadeInEachThatHasTheRef() throws Exception {
        three();
        for (GitFixture f : fs.subList(0, 2)) {
            f.git("switch", "-q", "-c", "feature");
            f.write("g.txt", "feature\n");
            f.commitAll("on feature");
            f.git("switch", "-q", "main");
        }
        open();
        final List<List<String>> offered = new ArrayList<>();
        BranchCommands.choose = (editor, q, history, names, chosen) -> {
            offered.add(names);
            chosen.accept("feature (2 of 3)");
        };
        final Command m = (Command) MergeCommands
                .popup(new BranchCommands.Context(rs.get(0), null, "main", List.of()), false, "x", true)
                .item("m");
        assertEquals("merge into each repository", m.description());
        onEdt(() -> MergeCommands.vcsMerge(h.editor(), null));
        until(() -> fs.subList(0, 2).stream().allMatch(f -> subject(f).equals("on feature")));
        assertEquals(List.of("feature (2 of 3)"), offered.get(0), "main checked out in each");
        assertEquals("one", subject(fs.get(2)));
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out");
    }
}
