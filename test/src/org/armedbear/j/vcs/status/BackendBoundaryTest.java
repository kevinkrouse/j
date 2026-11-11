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
import java.util.List;
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
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.changelists.Anchors;
import org.armedbear.j.vcs.changelists.ChangelistStore;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.ChangelistCommands.Destination;
import org.armedbear.j.vcs.status.ChangelistCommands.ListKind;
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

    private record Move(List<String> paths, String destination, boolean create) {}

    private static final class Fixture implements VcsBackend {
        private final String section;
        private final Set<Capability> capabilities;
        private final List<FileItem> files;
        final Set<String> diffsRequested = ConcurrentHashMap.newKeySet();
        final Set<String> names = ConcurrentHashMap.newKeySet();
        final AtomicReference<Move> moved = new AtomicReference<>();

        Fixture(String section, Set<Capability> capabilities, List<FileItem> files) {
            this.section = section;
            this.capabilities = capabilities;
            this.files = files;
            names.add("Feature");
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
            final Move move = moved.get();
            final List<StatusData.Field> header =
                    move == null ? List.of() : List.of(new StatusData.Field("Changelist", move.destination()));
            return new StatusData(header, null, List.of(new StatusData.Section(section, "Changes", shown)), null);
        }

        @Override
        public Path stateDirectory(VcsRepository r) {
            return r.root().resolve("editor-state");
        }

        @Override
        public List<String> readNativeChangelists(VcsRepository r) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return names.stream().sorted().toList();
        }

        @Override
        public VcsResult moveToNativeChangelist(
                VcsRepository r,
                List<String> paths,
                String destination,
                boolean create) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            if (!create && !names.contains(destination))
                return new VcsResult(null, 1, new byte[0], "No such list", 0);
            names.add(destination);
            moved.set(new Move(List.copyOf(paths), destination, create));
            return new VcsResult(null, 0, new byte[0], "", 0);
        }
    }

    private static Hunk hunk(int row, String added) {
        return new Hunk(row, 1, row, 1, "", List.of(new DiffLine('-', "old", false), new DiffLine('+', added, false)));
    }

    private static FileItem file(String path, String old, FileChange.Kind kind, FileDiff diff) {
        return new FileItem(new FileChange(path, old, null, kind, " " + kind.letter()), kind.letter(), false, diff);
    }

    private void open() throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            status = VcsStatusBuffer.of(List.of(repository));
            h.showBuffer(status);
        });
        until(() -> status.model().getData(repository) != null);
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
    void opaqueSectionsResolveAndNativeMovesTakeWholePathsWithoutDiffs() throws Exception {
        final FileItem text = file(
            TEXT,
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), TEXT, TEXT, false, false, false, false, List.of(FIRST), false));
        final FileItem binary = file(
            "binary.bin",
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), "binary.bin", "binary.bin", false, false, true, false, List.of(), false));
        final Fixture fixture = new Fixture(SECTION, Set.of(Capability.NATIVE_CHANGELISTS), List.of(text, binary));
        repository = BackendFixture.repository(tmp, fixture);
        open();
        final AtomicReference<String> said = new AtomicReference<>();
        final var to = new Destination(repository, "Feature", false, ListKind.NATIVE);
        onEdt(() -> {
            final var builder = new Selections.Builder();
            status.model().files(repository, SECTION).forEach(f -> builder.file(repository, SECTION, f));
            final List<Target> whole = builder.build();
            assertTrue(status.model().resolveTargets(whole).ready());
            assertTrue(StatusActions.refuse(StatusActions.Op.STAGE, whole.getFirst()).contains("no index"));
            ChangelistCommands.moveTargets(status.model(), whole, to, said::set);
        });
        until(() -> said.get() != null && !status.model().isWriting());
        assertTrue(said.get().startsWith("Moved"), said.get());
        assertEquals(new Move(List.of(TEXT, "binary.bin"), "Feature", false), fixture.moved.get());
        assertTrue(fixture.diffsRequested.isEmpty());
        assertNull(RepositoryLists.of(repository));
        assertFalse(Files.exists(fixture.stateDirectory(repository)));

        final BranchCommands.Choose choose = BranchCommands.choose;
        BranchCommands.choose = (e, q, history, names, unavailable, chosen) -> {
            assertEquals(List.of("Feature"), names);
            chosen.accept("New list");
        };
        try {
            onEdt(() -> {
                for (Line line = status.getFirstLine(); line != null; line = line.next())
                    if (line instanceof StatusLine row
                            && row.kind() == StatusLine.Kind.FILE
                            && row.file().path().equals("binary.bin")) {
                        h.editor().setDot(line, 0);
                        ChangelistCommands.vcsChangelistMove(h.editor());
                        return;
                    }
                fail("No row for the binary file");
            });
            until(() -> fixture.names.contains("New list") && !status.model().isWriting());
        }
        finally {
            BranchCommands.choose = choose;
        }
        assertTrue(fixture.diffsRequested.isEmpty());
        assertEquals(new Move(List.of("binary.bin"), "New list", true), fixture.moved.get());

        final List<Target> lines = new Selections.Builder().line(repository, SECTION, text, FIRST, 1).build();
        final AtomicReference<Selections.Resolution> resolved = new AtomicReference<>();
        onEdt(() -> status.model().resolveTargets(lines, resolved::set));
        until(() -> resolved.get() != null);
        assertTrue(resolved.get().ready(), resolved.get().failure());
        assertEquals(SECTION, resolved.get().targets().getFirst().section());
        assertEquals(2, ((Target.Part) resolved.get().targets().getFirst()).lines().cardinality());
        onEdt(() -> {
            ChangelistCommands.moveTargets(status.model(), resolved.get().targets(), to, said::set);
            assertTrue(said.get().contains("whole files"), said.get());
            final var refused = assertThrows(
                PartialCommit.Refused.class,
                () -> PartialCommit.ofTargets(status.model(), lines).plan());
            assertTrue(refused.getMessage().contains("does not support committing selected changes"));
        });
        assertEquals(new Move(List.of("binary.bin"), "New list", true), fixture.moved.get());
    }

    @Test
    void editorChangelistDropsUseTheLocalStoreWhenBothKindsAreSupported() throws Exception {
        final FileItem text = file(
            TEXT,
            null,
            FileChange.Kind.MODIFIED,
            new FileDiff(List.of(), TEXT, TEXT, false, false, false, false, List.of(FIRST, SECOND), false));
        final Fixture fixture = new Fixture(StatusRenderer.UNSTAGED,
                Set.of(Capability.HUNK_CHANGELISTS, Capability.NATIVE_CHANGELISTS),
                List.of(text));
        repository = BackendFixture.repository(tmp, fixture);
        final ChangelistStore store = ChangelistStore.of(repository);
        assertTrue(store.write(Changelists.initial().withList("Feature")));
        open();
        final AtomicReference<String> said = new AtomicReference<>();
        final RepositoryStatus model = status.model();
        onEdt(() -> {
            final FileItem current = model.unstaged(repository, TEXT);
            final ChangelistDrag drag = ChangelistDrag.capture(
                model,
                repository,
                StatusRenderer.UNSTAGED,
                Changelists.DEFAULT,
                new Target.Member(current, null));
            assertNotNull(drag);
            assertTrue(drag.drop(model, new Destination(repository, "Feature"), drag, said::set));
        });
        until(() -> said.get() != null && !said.get().startsWith("Reading"));
        assertTrue(said.get().startsWith("Moved"), said.get());
        assertNull(fixture.moved.get());
        assertEquals(
            List.of("Feature", "Feature"),
            store.read().assignment(TEXT).changes().stream().map(Changelists.Change::list).toList());

        onEdt(() -> {
            final FileItem current = model.unstaged(repository, TEXT);
            final Target.Part first = Target.Part
                    .of(repository, StatusRenderer.UNSTAGED, current, current.diff().hunks().getFirst(), null);
            final ChangelistDrag drag = ChangelistDrag.capture(model, "Feature", List.of(first));
            assertNotNull(drag);
            assertTrue(drag.drop(model, new Destination(repository, Changelists.DEFAULT), drag, said::set));
        });
        assertTrue(said.get().startsWith("Moved"), said.get());
        assertNull(fixture.moved.get());
        assertEquals(
            List.of(Changelists.DEFAULT, "Feature"),
            store.read().assignment(TEXT).changes().stream().map(Changelists.Change::list).toList());
    }

    @Test
    void nativeMoveRefreshesBothStandaloneAndMultiRepositoryStatus() throws Exception {
        final FileItem text = file(TEXT, null, FileChange.Kind.MODIFIED, FileDiff.EMPTY);
        final Fixture fixture = new Fixture(SECTION, Set.of(Capability.NATIVE_CHANGELISTS), List.of(text));
        repository = BackendFixture.repository(tmp, fixture);
        final VcsRepository other = BackendFixture
                .repository(Files.createDirectory(tmp.resolve("other")), new Fixture(SECTION, Set.of(), List.of()));
        open();
        final RepositoryStatus standalone = status.model();
        final AtomicReference<RepositoryStatus> combined = new AtomicReference<>();
        onEdt(() -> {
            final RepositoryStatus model = RepositoryStatus.of(List.of(repository, other));
            model.hold(this, null);
            combined.set(model);
        });
        until(() -> combined.get().getData(repository) != null);
        onEdt(() -> assertTrue(combined.get().getData(repository).header().isEmpty()));

        final AtomicReference<String> said = new AtomicReference<>();
        onEdt(
            () -> ChangelistCommands.moveTargets(
                standalone,
                List.of(new Target.Files(repository, SECTION, standalone.files(repository, SECTION))),
                new Destination(repository, "Feature", false, ListKind.NATIVE),
                said::set));
        final List<StatusData.Field> expected = List.of(new StatusData.Field("Changelist", "Feature"));
        until(
            () -> expected.equals(standalone.getData(repository).header())
                    && expected.equals(combined.get().getData(repository).header()));
        assertTrue(said.get().startsWith("Moved"), said.get());
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
        final Fixture fixture =
                new Fixture(StatusRenderer.UNSTAGED, Set.of(Capability.HUNK_CHANGELISTS), List.of(renamed));
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
