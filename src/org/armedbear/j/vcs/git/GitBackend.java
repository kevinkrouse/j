/*
 * GitBackend.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.git;

import static org.armedbear.j.Constants.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Ignores;
import org.armedbear.j.vcs.RepositoryWatcher;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.VersionControlEntry;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogRefTip;
import org.armedbear.j.vcs.status.StatusData;

public final class GitBackend implements VcsBackend {
    @Override
    public int id() {
        return VC_GIT;
    }

    @Override
    public String name() {
        return "git";
    }

    private static final Set<Capability> CAPABILITIES = EnumSet.of(
        Capability.STAGING,
        Capability.STASH,
        Capability.BRANCHES,
        Capability.MERGE_GRAPH,
        Capability.HUNK_CHANGELISTS,
        Capability.PARTIAL_COMMIT);

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    // --ignored=matching came in 2.16 (porcelain v2 in 2.11, --show-stash
    // in 2.14); porcelain v2's stash header in 2.35.
    private static final int[] STATUS_FLAGS = { 2, 16 };
    private static final int[] STASH_HEADER = { 2, 35 };

    // Ignored files are listed as the directories and patterns that ignore
    // them (--ignored=matching), not one by one, which in a build tree is
    // thousands.
    @Override
    public Snapshot readSnapshot(VcsRepository repository) {
        if (!Git.haveGit())
            return Snapshot.failed("git is not on the PATH");
        if (!GitCli.versionAtLeast(STATUS_FLAGS))
            return Snapshot.failed("git " + GitCli.versionString() + " is too old; j needs 2.16 or later");
        GitDirs dirs = GitDirs.of(repository.root());
        VcsResult r = GitCli.run(
            GitCli.read(
                repository.root(),
                "status",
                "--porcelain=v2",
                "-z",
                "--branch",
                "--show-stash",
                "--untracked-files=all",
                "--ignored=matching"));
        if (!r.succeeded())
            return Snapshot.failed(r.stderr().strip());
        return GitStatusParser.parse(
            r.stdout(),
            dirs == null ? GitSnapshot.Operation.NONE : dirs.operation(),
            GitCli.versionAtLeast(STASH_HEADER));
    }

    @Override
    public StatusData readStatus(VcsRepository repository, BiPredicate<String, String> wantsDiff, Whitespace ignored) {
        if (!Git.haveGit())
            return StatusData.failed("git is not on the PATH");
        return GitStatusReader.read(repository, wantsDiff, ignored);
    }

    @Override
    public List<FileDiff> readDiff(VcsRepository repository, boolean staged, List<String> paths, Whitespace ignored) {
        return GitStatusReader.diff(repository, staged, paths, ignored);
    }

    @Override
    public VcsResult stage(VcsRepository repository, List<String> paths) {
        return GitWrites.stage(repository, paths);
    }

    @Override
    public VcsResult stageAll(VcsRepository repository) {
        return GitWrites.stageAll(repository);
    }

    @Override
    public VcsResult unstage(VcsRepository repository, List<FileChange> changes) {
        return GitWrites.unstage(repository, changes);
    }

    @Override
    public VcsResult unstageAll(VcsRepository repository) {
        return GitWrites.unstageAll(repository);
    }

    @Override
    public VcsResult discard(VcsRepository repository, List<String> paths) {
        return GitWrites.discard(repository, paths);
    }

    @Override
    public VcsResult apply(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        return GitWrites.apply(repository, patch, index, workingTree, reverse);
    }

    @Override
    public boolean applies(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        final VcsResult r = GitWrites.apply(repository, patch, index, workingTree, reverse, true);
        return r != null && r.succeeded();
    }

    @Override
    public VcsResult commit(VcsRepository repository, String message, Set<CommitOption> options) {
        return GitWrites.commit(repository, message, options);
    }

    @Override
    public boolean verboseCommits(VcsRepository repository) {
        return GitWrites.verboseCommits(repository);
    }

    @Override
    public byte[] readCommitDiff(VcsRepository repository, Set<CommitOption> options) {
        return GitWrites.commitDiff(repository, options);
    }

    @Override
    public Bases readBases(VcsRepository repository, String path) {
        return GitBases.read(repository, path);
    }

    @Override
    public String lastMessage(VcsRepository repository) {
        return GitLog.message(repository, "HEAD");
    }

    @Override
    public byte[] readCommit(VcsRepository repository, String id, Whitespace ignored) {
        final List<String> args = GitCli.ignoring(ignored, new ArrayList<>(List.of("show")));
        args.addAll(List.of("--no-ext-diff", "--no-color", "--format=fuller", id));
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        return r.succeeded() ? r.stdout() : null;
    }

    @Override
    public List<LogCommit> readLog(VcsRepository repository, LogQuery query, int skip, int max) {
        return GitLog.read(repository, query, skip, max);
    }

    @Override
    public String readMessage(VcsRepository repository, String id) {
        return GitLog.message(repository, id);
    }

    @Override
    public List<CommitChange> readCommitChanges(VcsRepository repository, String id, String parent) {
        return GitLog.changes(repository, id, parent);
    }

    @Override
    public FileDiff readChangeDiff(
            VcsRepository repository,
            String id,
            String parent,
            CommitChange change,
            Whitespace ignored) {
        return GitLog.diff(repository, id, parent, change, ignored);
    }

    @Override
    public FileDiff readDiffToWorkingTree(VcsRepository repository, String id, String path) {
        return GitLog.toWorkingTree(repository, id, path);
    }

    @Override
    public byte[] readFileAt(VcsRepository repository, String id, String path) {
        return GitLog.fileAt(repository, id, path);
    }

    @Override
    public List<LogRefTip> readRefTips(VcsRepository repository) {
        return GitLog.refTips(repository);
    }

    @Override
    public List<String> readStarts(VcsRepository repository, LogQuery query) {
        return GitLog.starts(repository, query);
    }

    @Override
    public List<String> readAuthors(VcsRepository repository, int max) {
        return GitLog.authors(repository, max);
    }

    @Override
    public boolean logHas(VcsRepository repository, LogQuery query, String id) {
        return GitLog.has(repository, query, id);
    }

    @Override
    public List<String> readPaths(VcsRepository repository) {
        return GitLog.paths(repository);
    }

    // HEAD and the index in the working tree's git directory; refs and
    // packed-refs in the common one.
    @Override
    public List<RepositoryWatcher.Watch> watches(VcsRepository repository) {
        GitDirs dirs = GitDirs.of(repository.root());
        List<RepositoryWatcher.Watch> watches = new ArrayList<>();
        if (dirs == null)
            return watches;
        watches.add(new RepositoryWatcher.Watch(dirs.gitDir(), false));
        if (!dirs.commonDir().equals(dirs.gitDir()))
            watches.add(new RepositoryWatcher.Watch(dirs.commonDir(), false));
        watches.add(new RepositoryWatcher.Watch(dirs.commonDir().resolve("refs"), true));
        // Refs in a reftable repository, git 3's default.
        watches.add(new RepositoryWatcher.Watch(dirs.commonDir().resolve("reftable"), false));
        return watches;
    }

    // A worktree or submodule has a .git file, not a directory.
    @Override
    public Ignores ignores(Path root) {
        return new GitIgnores(root, GitIgnores.globalIgnoreFile());
    }

    @Override
    public boolean isRoot(File dir) {
        File git = File.getInstance(dir, ".git");
        return git != null && git.exists();
    }

    @Override
    public VersionControlEntry getEntry(Buffer buffer) {
        return GitEntry.getEntry(buffer);
    }

    @Override
    public boolean gotoDiffSource(Editor editor, DiffOutputBuffer buffer) {
        DiffMode.gotoUnifiedDiffSource(editor, buffer, GitBackend::filename, Git::findRoot);
        return true;
    }

    private static String filename(String line) {
        if (line.startsWith("+++ b/"))
            return line.substring("+++ b/".length());
        if (line.startsWith("renamed to "))
            return line.substring("renamed to ".length());
        return null;
    }
}
