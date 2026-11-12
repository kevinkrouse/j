/*
 * SvnBackend.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import static org.armedbear.j.Constants.*;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.Ignores;
import org.armedbear.j.vcs.RepositoryWatcher;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.VersionControlEntry;
import org.armedbear.j.vcs.VersionControlHooks;
import org.armedbear.j.vcs.blame.BlameCommit;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.status.StatusData;

public final class SvnBackend implements VcsBackend, VersionControlHooks {
    @Override
    public int id() {
        return VC_SVN;
    }

    @Override
    public String name() {
        return "svn";
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    private static final Set<Capability> CAPABILITIES =
            EnumSet.of(Capability.COMMIT, Capability.RESOLVE, Capability.NATIVE_CHANGELISTS, Capability.BLAME);

    @Override
    public Snapshot readSnapshot(VcsRepository repository) {
        return SvnStatus.readSnapshot(repository);
    }

    @Override
    public StatusData readStatus(VcsRepository repository, BiPredicate<String, String> wantsDiff, Whitespace ignored) {
        return SvnStatus.read(repository, wantsDiff, ignored);
    }

    // No index: nothing is staged.
    @Override
    public List<FileDiff> readDiff(VcsRepository repository, boolean staged, List<String> paths, Whitespace ignored) {
        return staged ? List.of() : SvnStatus.diffs(repository, paths, ignored);
    }

    @Override
    public Bases readBases(VcsRepository repository, String path) {
        return SvnStatus.bases(repository, path);
    }

    // No index: staging a conflicted file marks it resolved, as the shared resolve does.
    @Override
    public VcsResult stage(VcsRepository repository, List<String> paths) {
        return SvnConflicts.markResolved(repository, paths);
    }

    @Override
    public VcsResult checkoutSide(VcsRepository repository, List<String> paths, boolean ours) {
        return SvnConflicts.takeSide(repository, paths, ours);
    }

    @Override
    public VcsResult restoreConflict(VcsRepository repository, List<String> paths) {
        return new VcsResult(null, 1, new byte[0], "svn can't put a conflict back", 0);
    }

    @Override
    public ConflictSides readConflictSides(VcsRepository repository, String path) {
        return SvnConflicts.sides(repository, path);
    }

    @Override
    public String nativeChangelist(String section) {
        return SvnStatus.changelist(section);
    }

    @Override
    public VcsResult setChangelist(VcsRepository repository, List<String> paths, String name) {
        return SvnWrites.changelist(repository, paths, name);
    }

    @Override
    public VcsResult discard(VcsRepository repository, List<String> paths) {
        return SvnWrites.discard(repository, paths);
    }

    @Override
    public VcsResult apply(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        return workingTree ? SvnWrites.apply(repository, patch, index, reverse) : null;
    }

    @Override
    public boolean applies(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        return workingTree && SvnWrites.applies(repository, patch, index, reverse);
    }

    @Override
    public CommitPreview previewPaths(VcsRepository repository, List<String> paths) {
        return SvnWrites.preview(repository, paths);
    }

    @Override
    public VcsResult commitPaths(
            VcsRepository repository,
            List<String> paths,
            Map<String, ChangeVersion> reviewed,
            String message) {
        return SvnWrites.commit(repository, paths, reviewed, message);
    }

    @Override
    public List<LogCommit> readLog(VcsRepository repository, LogQuery query, int skip, int max) {
        return SvnLog.read(repository, query, skip, max);
    }

    @Override
    public VcsResult blame(
            VcsRepository repository,
            String path,
            String revision,
            byte[] contents,
            Whitespace ignored,
            BlameCommit.Sink sink) {
        return SvnBlame.blame(repository, path, revision, contents, ignored, sink);
    }

    @Override
    public String readMessage(VcsRepository repository, String id) {
        return SvnLog.message(repository, id);
    }

    @Override
    public List<CommitChange> readCommitChanges(VcsRepository repository, String id, String parent) {
        return SvnLog.changes(repository, id);
    }

    // Against the revision before, whatever the log listed before it.
    @Override
    public FileDiff readChangeDiff(
            VcsRepository repository,
            String id,
            String parent,
            CommitChange change,
            Whitespace ignored) {
        return SvnLog.changeDiff(repository, id, change, ignored);
    }

    @Override
    public byte[] readFileAt(VcsRepository repository, String id, String path) {
        return SvnLog.fileAt(repository, id, path);
    }

    @Override
    public byte[] readCommit(VcsRepository repository, String id, Whitespace ignored) {
        return SvnLog.commit(repository, id, ignored);
    }

    // The working copy's database, which every svn command that changes it writes.
    @Override
    public List<RepositoryWatcher.Watch> watches(VcsRepository repository) {
        return List.of(new RepositoryWatcher.Watch(repository.root().resolve(".svn"), false));
    }

    // A working copy's rules, read again a while after they were, each read
    // costing two svn runs; never while one is running, as a listing waiting
    // for them would start another when they came.
    private static final long REREAD_MILLIS = 10_000;
    private final Map<Path, SvnIgnores> ignores = new ConcurrentHashMap<>();

    @Override
    public Ignores ignores(Path root) {
        final long now = System.currentTimeMillis();
        return ignores
                .compute(root, (r, read) -> read != null && read.fresh(now, REREAD_MILLIS) ? read : new SvnIgnores(r));
    }

    // The top of a working copy: before svn 1.7, each of its directories has
    // a .svn too, and only the one whose parent hasn't is its root.
    @Override
    public boolean isRoot(File dir) {
        if (!hasSvn(dir))
            return false;
        final File parent = dir.getParentFile();
        return parent == null || !hasSvn(parent);
    }

    private static boolean hasSvn(File dir) {
        File svn = File.getInstance(dir, ".svn");
        return svn != null && svn.isDirectory();
    }

    @Override
    public VersionControlEntry getEntry(Buffer buffer) {
        return SVNEntry.getEntry(buffer);
    }

    @Override
    public void finish(Editor editor, CheckinBuffer buffer) {
        SVN.finish(editor, buffer);
    }

    @Override
    public boolean gotoDiffSource(Editor editor, DiffOutputBuffer buffer) {
        DiffMode.gotoUnifiedDiffSource(
            editor,
            buffer,
            line -> line.startsWith("Index: ") ? line.substring("Index: ".length()) : null,
            dir -> dir);
        return true;
    }
}
