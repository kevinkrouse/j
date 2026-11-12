/*
 * BackendBoundaryTest.java
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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.BackendFixture;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Anchors;
import org.armedbear.j.vcs.changelists.ChangelistStore;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.ChangelistCommands.Destination;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BackendBoundaryTest {
    private static final String SECTION = "working|vendor";
    private static final String TEXT = "literal|text.txt";
    private static final Hunk FIRST = hunk(1, "first");
    private static final Hunk SECOND = hunk(50, "second");

    @TempDir
    Path tmp;
    private EditorHarness h;
    private VcsStatusBuffer status;
    private VcsRepository repository;

    private static final class Fixture implements VcsBackend {
        private final String section;
        private final Set<Capability> capabilities;
        private final List<FileItem> files;
        final Set<String> diffsRequested = ConcurrentHashMap.newKeySet();

        Fixture(String section, Set<Capability> capabilities, List<FileItem> files) {
            this.section = section;
            this.capabilities = capabilities;
            this.files = files;
        }

        @Override
        public int id() {
            return -1;
        }

        @Override
        public String name() {
            return "fixture";
        }

        @Override
        public Set<Capability> capabilities() {
            return capabilities;
        }

        @Override
        public Snapshot readSnapshot(VcsRepository r) {
            return new Snapshot(files.stream().map(FileItem::change).toList());
        }

        @Override
        public StatusData readStatus(VcsRepository r, BiPredicate<String, String> wantsDiff, Whitespace ignored) {
            final List<StatusData.Item> shown = files.stream().map(file -> {
                if (wantsDiff.test(section, file.path())) {
                    diffsRequested.add(file.path());
                    return file;
                }
                return new FileItem(file.change(), file.letter(), file.staged(), null);
            }).map(StatusData.Item.class::cast).toList();
            return new StatusData(List.of(),
                    null,
                    List.of(new StatusData.Section(section, "Changes", StatusData.Role.WORKING, shown)),
                    null);
        }

        @Override
        public Path stateDirectory(VcsRepository r) {
            return r.root().resolve("editor-state");
        }

    }

    private static Hunk hunk(int row, String added) {
        return new Hunk(row, 1, row, 1, "", List.of(new DiffLine('-', "old", false), new DiffLine('+', added, false)));
    }

    private static FileItem file(String path, String old, FileChange.Kind kind, FileDiff diff) {
        return new FileItem(new FileChange(path, old, null, kind, " " + kind.letter()), kind.letter(), false, diff);
    }

    private void open() throws Exception {
        open(List.of(repository));
    }

    private void open(List<VcsRepository> repositories) throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            status = VcsStatusBuffer.of(repositories);
            h.showBuffer(status);
        });
        until(() -> repositories.stream().allMatch(r -> status.model().getData(r) != null));
    }

    @AfterEach
    void close() throws Exception {
        onEdt(() -> {
            if (status != null) {
                status.dispose();
                Editor.getBufferList().remove(status);
            }
            RepositoryStatus.closeAll();
        });
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
    }

    private static void onEdt(Runnable work) throws Exception {
        SwingUtilities.invokeAndWait(work);
    }

    private static void until(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ready = { false };
            onEdt(() -> ready[0] = condition.getAsBoolean());
            if (ready[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for the status");
    }

    @Test
    void diffReadKeysKeepLiteralSectionsAndPathsSeparate() throws Exception {
        final FileItem first = file(
            "c.txt",
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), "c.txt", "c.txt", false, false, false, false, List.of(FIRST), false));
        final FileItem second = file(
            "b|c.txt",
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), "b|c.txt", "b|c.txt", false, false, false, false, List.of(SECOND), false));
        final Set<FileKey> requests = ConcurrentHashMap.newKeySet();
        final VcsBackend backend = new VcsBackend() {
            @Override
            public int id() {
                return -1;
            }

            @Override
            public String name() {
                return "literal sections";
            }

            @Override
            public Set<Capability> capabilities() {
                return Set.of();
            }

            @Override
            public Snapshot readSnapshot(VcsRepository r) {
                return new Snapshot(List.of());
            }

            @Override
            public StatusData readStatus(VcsRepository r, BiPredicate<String, String> wants, Whitespace ignored) {
                final List<StatusData.Section> sections = new ArrayList<>();
                for (var entry : Map.of("a|b", first, "a", second).entrySet()) {
                    final FileItem file = entry.getValue();
                    final boolean read = wants.test(entry.getKey(), file.path());
                    if (read)
                        requests.add(new FileKey(r, entry.getKey(), file.path()));
                    sections.add(
                        new StatusData.Section(entry.getKey(),
                                entry.getKey(),
                                StatusData.Role.WORKING,
                                List.of(read ? file : new FileItem(file.change(), file.letter(), false, null))));
                }
                return new StatusData(List.of(), null, sections, null);
            }
        };
        repository = BackendFixture.repository(tmp, backend);
        final AtomicReference<RepositoryStatus> model = new AtomicReference<>();
        onEdt(() -> {
            model.set(RepositoryStatus.of(List.of(repository)));
            model.get().hold(this, null);
        });
        until(() -> model.get().getData(repository) != null);
        final FileKey one = new FileKey(repository, "a|b", "c.txt");
        final FileKey two = new FileKey(repository, "a", "b|c.txt");
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        onEdt(() -> model.get().readDiffsThen(Set.of(one), () -> done.set(true)));
        until(done::get);
        onEdt(() -> {
            assertTrue(model.get().diffRead(one));
            assertFalse(model.get().diffRead(two));
            assertNotNull(model.get().files(repository, "a|b").getFirst().diff());
            assertNull(model.get().files(repository, "a").getFirst().diff());
        });
        assertEquals(Set.of(one), requests);
        done.set(false);
        onEdt(() -> model.get().readDiffsThen(Set.of(two), () -> done.set(true)));
        until(done::get);
        assertEquals(Set.of(one, two), requests);
    }

    @Test
    void opaqueSectionsResolveAndMovesNeedTheEditorsChangelists() throws Exception {
        final FileItem text = file(
            TEXT,
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), TEXT, TEXT, false, false, false, false, List.of(FIRST), false));
        final Fixture fixture = new Fixture(SECTION, Set.of(Capability.NATIVE_CHANGELISTS), List.of(text));
        repository = BackendFixture.repository(tmp, fixture);
        open();
        final AtomicReference<String> said = new AtomicReference<>();
        onEdt(() -> {
            final var builder = new Selections.Builder();
            status.model().files(repository, SECTION).forEach(f -> builder.file(repository, SECTION, f));
            final List<Target> whole = builder.build();
            assertTrue(status.model().resolveTargets(whole).ready());
            assertTrue(StatusActions.refuse(StatusActions.Op.STAGE, whole.getFirst()).contains("no index"));
            // Native changelists go through the backend, which this one hasn't.
            ChangelistCommands.moveTargets(status.model(), whole, new Destination(repository, "Feature"), said::set);
        });
        until(() -> said.get() != null);
        assertTrue(said.get().contains("can't"), said.get());
        assertNull(RepositoryLists.of(repository));
        assertFalse(Files.exists(fixture.stateDirectory(repository)));

        final List<Target> lines = new Selections.Builder().line(repository, SECTION, text, FIRST, 1).build();
        final AtomicReference<Selections.Resolution> resolved = new AtomicReference<>();
        onEdt(() -> status.model().resolveTargets(lines, resolved::set));
        until(() -> resolved.get() != null);
        assertTrue(resolved.get().ready(), resolved.get().failure());
        assertEquals(SECTION, resolved.get().targets().getFirst().section());
        assertEquals(2, ((Target.Part) resolved.get().targets().getFirst()).selection().cardinality());
        onEdt(() -> {
            final var refused = assertThrows(
                PartialCommit.Refused.class,
                () -> PartialCommit.ofTargets(status.model(), lines).plan());
            assertTrue(refused.getMessage().contains("does not support committing selected changes"));
        });
    }

    @Test
    void editorChangelistDropsUseTheLocalStore() throws Exception {
        final FileItem text = file(
            TEXT,
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), TEXT, TEXT, false, false, false, false, List.of(FIRST, SECOND), false));
        final Fixture fixture =
                new Fixture(SECTION, Set.of(Capability.HUNK_CHANGELISTS, Capability.NATIVE_CHANGELISTS), List.of(text));
        repository = BackendFixture.repository(tmp, fixture);
        final ChangelistStore store = ChangelistStore.of(repository);
        assertTrue(store.write(Changelists.initial().withList("Feature")));
        open();
        final AtomicReference<String> said = new AtomicReference<>();
        final RepositoryStatus model = status.model();
        onEdt(() -> {
            final FileItem current = model.unstaged(repository, TEXT);
            final ChangelistDrag drag = ChangelistDrag
                    .capture(model, repository, SECTION, Changelists.DEFAULT, new Target.Member(current, null));
            assertNotNull(drag);
            assertTrue(drag.drop(model, new Destination(repository, "Feature"), drag, said::set));
        });
        until(() -> said.get() != null && !said.get().startsWith("Reading"));
        assertTrue(said.get().startsWith("Moved"), said.get());
        assertEquals(
            List.of("Feature", "Feature"),
            store.read().assignment(TEXT).changes().stream().map(Changelists.Change::list).toList());

        onEdt(() -> {
            final FileItem current = model.unstaged(repository, TEXT);
            final Target.Part first =
                    new Target.Part(repository, SECTION, current, current.diff().hunks().getFirst(), null);
            final ChangelistDrag drag = ChangelistDrag.capture(model, "Feature", List.of(first));
            assertNotNull(drag);
            assertTrue(drag.drop(model, new Destination(repository, Changelists.DEFAULT), drag, said::set));
        });
        assertTrue(said.get().startsWith("Moved"), said.get());
        assertEquals(
            List.of(Changelists.DEFAULT, "Feature"),
            store.read().assignment(TEXT).changes().stream().map(Changelists.Change::list).toList());
    }

    @Test
    void changelistsSortAWorkingSectionOfAnyName() throws Exception {
        final FileItem text = file(
            TEXT,
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), TEXT, TEXT, false, false, false, false, List.of(FIRST), false));
        final Fixture fixture = new Fixture(SECTION, Set.of(Capability.HUNK_CHANGELISTS), List.of(text));
        repository = BackendFixture.repository(tmp, fixture);
        assertTrue(ChangelistStore.of(repository).write(Changelists.initial().withList("Feature")));
        open();
        until(() -> status.model().listed(repository) != null);
        onEdt(() -> {
            final List<String> lists = new ArrayList<>();
            for (Line line = status.getFirstLine(); line != null; line = line.next())
                if (line instanceof StatusLine row && row.kind() == StatusLine.Kind.LIST) {
                    assertEquals(SECTION, row.section());
                    lists.add(row.list());
                }
            assertEquals(List.of(Changelists.DEFAULT, "Feature"), lists);

            final var root = ChangesTree.build(
                List.of(
                    new ChangesTree.Part(repository,
                            repository.name(),
                            status.model().getData(repository),
                            status.model().listed(repository))));
            final List<String> groups = new ArrayList<>();
            for (var e = root.preorderEnumeration(); e.hasMoreElements();)
                if (((javax.swing.tree.DefaultMutableTreeNode) e.nextElement())
                        .getUserObject() instanceof ChangesTree.GroupNode g) {
                    assertEquals(SECTION, g.section());
                    groups.add(g.list());
                }
            assertEquals(List.of(Changelists.DEFAULT, "Feature"), groups);
        });
    }

    @Test
    void aStatusWithARepositoryWithoutAnIndexStillCommits() throws Exception {
        final GitFixture git = GitFixture.init(Files.createDirectories(tmp.resolve("git")));
        try {
            git.write("a.txt", "one\n");
            git.commitAll("one");
            git.write("a.txt", "two\n");
            final FileItem working = file(TEXT, null, FileChange.Kind.MODIFIED, FileDiff.EMPTY);
            repository = BackendFixture.repository(
                Files.createDirectories(tmp.resolve("fixture")),
                new Fixture(SECTION, Set.of(Capability.COMMIT), List.of(working)));
            open(List.of(Repositories.forDirectory(git.root), repository));
            onEdt(() -> {
                assertTrue(VcsStatusMode.commits(status));
                assertTrue(VcsStatusMode.stages(status), "the Git repository stages");
                assertNotNull(VcsStatusMode.getMode().getTransient(h.editor()).item("c"), "offers commit");
                final Target.Files target = new Target.Files(repository, SECTION, List.of(working));
                assertTrue(StatusActions.refuse(StatusActions.Op.STAGE, target).contains("no index"));
                // Stage all stages the Git repository's changes alone.
                StatusActions.all(h.editor(), true);
            });
            until(() -> "two\n".equals(git.index("a.txt")) && !status.isWriting());
            onEdt(() -> assertFalse(h.status().contains("can't"), h.status()));
        }
        finally {
            GitFixture.reset();
        }
    }

    @Test
    void aStatusWithoutAnIndexCommitsButDoesNotStage() throws Exception {
        final FileItem working = file(TEXT, null, FileChange.Kind.MODIFIED, FileDiff.EMPTY);
        repository = BackendFixture.repository(tmp, new Fixture(SECTION, Set.of(Capability.COMMIT), List.of(working)));
        open();
        onEdt(() -> {
            assertTrue(VcsStatusMode.commits(status));
            assertFalse(VcsStatusMode.stages(status));
            final var t = VcsStatusMode.getMode().getTransient(h.editor());
            assertNotNull(t.item("c"), "offers commit");
            assertNull(t.item("s"), "offers no staging");
            assertNull(CommitCommands.popup(h.editor()).item("a"), "nor amending");
        });
    }

    @Test
    void aStatusOfABackendThatCannotCommitOffersNoCommit() throws Exception {
        final FileItem working = file(TEXT, null, FileChange.Kind.MODIFIED, FileDiff.EMPTY);
        repository = BackendFixture.repository(tmp, new Fixture(SECTION, Set.of(), List.of(working)));
        open();
        onEdt(() -> {
            assertFalse(VcsStatusMode.commits(status));
            assertNull(VcsStatusMode.getMode().getTransient(h.editor()).item("c"));
        });
    }

    @Test
    void stagingCapabilityAloneDoesNotEnablePartialCommits() throws Exception {
        final FileItem working = file(TEXT, null, FileChange.Kind.MODIFIED, FileDiff.EMPTY);
        final Fixture fixture = new Fixture("staged", Set.of(Capability.STAGING), List.of(working));
        repository = BackendFixture.repository(tmp, fixture);
        open();
        onEdt(() -> {
            // Here the backend happens to use a Git section name for working changes.
            final Target.Files target = new Target.Files(repository, "staged", List.of(working));
            assertNull(StatusActions.refuse(StatusActions.Op.STAGE, target));
            assertEquals("Not staged", StatusActions.refuse(StatusActions.Op.UNSTAGE, target));
            final var refused = assertThrows(
                PartialCommit.Refused.class,
                () -> PartialCommit.ofTargets(status.model(), List.of(target)).plan());
            assertTrue(refused.getMessage().contains("does not support committing selected changes"));
        });
    }

    @Test
    void renameRequestsTheNewPathDiffBeforeAskingForReselection() throws Exception {
        final FileItem renamed = file(
            "new.txt",
            "old.txt",
            FileChange.Kind.RENAMED,
            new FileDiff(List.of(), "old.txt", "new.txt", false, false, false, false, List.of(FIRST, SECOND), false));
        final Fixture fixture = new Fixture(SECTION, Set.of(Capability.HUNK_CHANGELISTS), List.of(renamed));
        repository = BackendFixture.repository(tmp, fixture);
        final var assignment = Anchors.assignment(
            List.of(
                new Anchors.Placed(FIRST, FIRST.runs().getFirst(), Changelists.DEFAULT, List.of()),
                new Anchors.Placed(SECOND, SECOND.runs().getFirst(), "Feature", List.of())));
        assertTrue(
            ChangelistStore.of(repository)
                    .write(Changelists.initial().withList("Feature").withFile("old.txt", assignment)));
        open();
        onEdt(() -> {
            assertNull(status.model().unstaged(repository, "new.txt").diff());
            for (Line line = status.getFirstLine(); line != null; line = line.next())
                if (line instanceof StatusLine row && row.kind() == StatusLine.Kind.FILE && row.file() != null) {
                    h.editor().setDot(line, 0);
                    ChangelistCommands.vcsChangelistMove(h.editor());
                    assertTrue(h.status().startsWith("Reading the changelist diff"), h.status());
                    return;
                }
            fail("No row for the renamed file");
        });
        until(() -> h.status().contains("Select the changes again"));
        assertEquals(Set.of("new.txt"), fixture.diffsRequested);
        onEdt(() -> {
            final var members = status.model().listed(repository).members();
            assertNotNull(members.get(Changelists.DEFAULT).getFirst().only());
            assertNotNull(members.get("Feature").getFirst().only());
        });
        assertEquals(assignment, ChangelistStore.of(repository).read().assignment("old.txt"));
    }
}
