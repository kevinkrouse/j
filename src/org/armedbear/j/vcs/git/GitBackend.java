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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.armedbear.j.vcs.FetchOption;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Ignores;
import org.armedbear.j.vcs.MergeOption;
import org.armedbear.j.vcs.RepositoryWatcher;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.Snapshot.Relation;
import org.armedbear.j.vcs.StashOption;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.VersionControlEntry;
import org.armedbear.j.vcs.blame.BlameCommit;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogRef;
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
        Capability.PARTIAL_COMMIT,
        Capability.BLAME);

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
        final GitSnapshot s = GitStatusParser.parse(
            r.stdout(),
            dirs == null ? GitSnapshot.Operation.NONE : dirs.operation(),
            GitCli.versionAtLeast(STASH_HEADER));
        return withRelations(repository, dirs, s);
    }

    // A branch's relations as last read, good while the refs are as they
    // were then and HEAD, the branch and the upstream status gave the same.
    private record Kept(long refs, String head, String branch, Relation status, GitSnapshot read) {}

    private final Map<VcsRepository, Kept> kept = new ConcurrentHashMap<>();

    // The user's identity as last read, good while the repository's config
    // (its refs generation), the global config and the mailmap are as they
    // were then.
    private record KeptIdentity(long refs, long stamp, Identity identity) {}

    private final Map<VcsRepository, KeptIdentity> identities = new ConcurrentHashMap<>();

    @Override
    public Identity identity(VcsRepository repository) {
        final long refs = repository.refsGeneration();
        final long stamp = identityStamp(repository);
        final KeptIdentity k = identities.get(repository);
        if (k != null && k.refs() == refs && k.stamp() == stamp)
            return k.identity();
        final Identity identity = readIdentity(repository);
        identities.put(repository, new KeptIdentity(refs, stamp, identity));
        return identity;
    }

    // As a commit takes it: the environment over the config, then mapped
    // as blame maps its authors.
    private static Identity readIdentity(VcsRepository repository) {
        String name = System.getenv("GIT_AUTHOR_NAME");
        String email = System.getenv("GIT_AUTHOR_EMAIL");
        if (name == null)
            name = configValue(repository, "user.name");
        if (email == null)
            email = configValue(repository, "user.email");
        if (name == null && email == null)
            return null;
        if (email != null) {
            final String ident = (name != null ? name + " " : "") + "<" + email + ">";
            final VcsResult r = GitCli.run(GitCli.read(repository.root(), "check-mailmap", ident));
            final String mapped = r.succeeded() ? r.out().strip() : "";
            final int lt = mapped.lastIndexOf('<');
            if (lt >= 0 && mapped.endsWith(">")) {
                final String n = mapped.substring(0, lt).strip();
                name = n.isEmpty() ? name : n;
                email = mapped.substring(lt + 1, mapped.length() - 1);
            }
        }
        return new Identity(name, email);
    }

    private static String configValue(VcsRepository repository, String key) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "config", "--get", key));
        final String value = r.succeeded() ? r.out().strip() : "";
        return value.isEmpty() ? null : value;
    }

    // What else an identity is read from: the global config files and the
    // mailmap, by when each was last changed.
    private static long identityStamp(VcsRepository repository) {
        final String home = System.getProperty("user.home");
        final String xdg = System.getenv("XDG_CONFIG_HOME");
        final String global = System.getenv("GIT_CONFIG_GLOBAL");
        long stamp = 0;
        for (Path p : List.of(
            global != null ? Path.of(global) : Path.of(home, ".gitconfig"),
            xdg != null ? Path.of(xdg, "git", "config") : Path.of(home, ".config", "git", "config"),
            repository.root().resolve(".mailmap")))
            stamp = stamp * 31 + p.toFile().lastModified();
        return stamp;
    }

    // So a save's change marks run no more git than the status.
    private GitSnapshot withRelations(VcsRepository repository, GitDirs dirs, GitSnapshot s) {
        final long refs = repository.refsGeneration();
        final Kept k = kept.get(repository);
        if (k != null
                && k.refs() == refs
                && Objects.equals(k.head(), s.head())
                && Objects.equals(k.branch(), s.branch())
                && Objects.equals(k.status(), s.upstream()))
            return s.with(k.read().upstream(), k.read().push(), k.read().parent());
        final GitSnapshot read = GitRelations.read(repository.root(), dirs, s);
        kept.put(repository, new Kept(refs, s.head(), s.branch(), s.upstream(), read));
        return read;
    }

    @Override
    public VcsResult blame(
            VcsRepository repository,
            String path,
            String revision,
            byte[] contents,
            Whitespace ignored,
            BlameCommit.Sink sink) {
        return GitBlame.blame(repository, path, revision, contents, ignored, sink);
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
    public List<String> readRemotes(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "remote"));
        final List<String> remotes = new ArrayList<>();
        if (r.succeeded())
            for (String line : r.out().split("\n"))
                if (!line.isBlank())
                    remotes.add(line.strip());
        return remotes;
    }

    @Override
    public VcsResult fetch(VcsRepository repository, String remote, String refspec, Set<FetchOption> options) {
        return GitWrites.fetch(repository, remote, refspec, options);
    }

    @Override
    public VcsResult setParent(VcsRepository repository, String branch, String parent) {
        return GitWrites.setParent(repository, branch, parent);
    }

    @Override
    public VcsResult stash(VcsRepository repository, String message, Set<StashOption> options) {
        return GitWrites.stash(repository, message, options);
    }

    @Override
    public VcsResult stashApply(VcsRepository repository, String name, String id, boolean pop) {
        return GitWrites.stashApply(repository, name, id, pop);
    }

    @Override
    public VcsResult stashDrop(VcsRepository repository, String name, String id) {
        return GitWrites.stashDrop(repository, name, id);
    }

    @Override
    public VcsResult switchTo(VcsRepository repository, LogRef ref) {
        return GitWrites.switchTo(repository, ref);
    }

    @Override
    public VcsResult createBranch(VcsRepository repository, String name, String from, boolean checkout) {
        return GitWrites.createBranch(repository, name, from, checkout);
    }

    @Override
    public VcsResult renameBranch(VcsRepository repository, String branch, String name) {
        return GitWrites.renameBranch(repository, branch, name);
    }

    @Override
    public VcsResult deleteBranch(VcsRepository repository, String branch, boolean force) {
        return GitWrites.deleteBranch(repository, branch, force);
    }

    @Override
    public List<String> readOnlyOn(VcsRepository repository, String branch) {
        return GitLog.onlyOn(repository, branch);
    }

    @Override
    public VcsResult merge(VcsRepository repository, String ref, Set<MergeOption> options) {
        return GitWrites.merge(repository, ref, options);
    }

    @Override
    public VcsResult mergeAbort(VcsRepository repository) {
        return GitWrites.mergeAbort(repository);
    }

    @Override
    public boolean isMerging(VcsRepository repository) {
        return repository.snapshotNow() instanceof GitSnapshot g && g.operation() == GitSnapshot.Operation.MERGE;
    }

    @Override
    public List<String> readRecentBranches(VcsRepository repository) {
        return GitLog.recentBranches(repository);
    }

    @Override
    public int fetchedRefs(VcsResult result) {
        return GitWrites.fetchedRefs(result.stderr());
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
    public List<LogCommit> readStashes(VcsRepository repository) {
        return GitLog.stashes(repository);
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
