/*
 * VcsBackend.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.blame.BlameCommit;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;
import org.armedbear.j.vcs.status.StatusData;

/**
 * A version control system. Core provides git and svn; an extension declares
 * others in META-INF/services/org.armedbear.j.vcs.VcsBackend.
 */
public interface VcsBackend {
    /** A Constants VC_ id, unique among backends. */
    int id();

    String name();

    /** Whether dir holds this system's metadata (".git", ".svn"). */
    default boolean isRoot(File dir) {
        return false;
    }

    /** What the system can do. */
    default Set<Capability> capabilities() {
        return Set.of();
    }

    /**
     * Reads the repository's state, off the event thread; null if this
     * backend has no repository model.
     */
    default Snapshot readSnapshot(VcsRepository repository) {
        return null;
    }

    /**
     * Reads what the status buffer shows, off the event thread; null if this
     * backend has none. wantsDiff(section id, path) says which files' diffs
     * to read: the ones expanded, ignoring ignored's whitespace.
     */
    default StatusData readStatus(VcsRepository repository, BiPredicate<String, String> wantsDiff, Whitespace ignored) {
        return null;
    }

    /**
     * The diffs of paths, relative to the root: the working tree's, or with
     * staged the next commit's, ignoring ignored's whitespace: none for a
     * patch to apply; null if they can't be read. Off the event thread.
     */
    default List<FileDiff> readDiff(VcsRepository repository, boolean staged, List<String> paths, Whitespace ignored) {
        return null;
    }

    // Writes, run off the event thread at the repository's root. Each
    // returns what ran, or null if the backend can't.

    /** Stages files whole, new, changed or deleted, by path. */
    default VcsResult stage(VcsRepository repository, List<String> paths) {
        return null;
    }

    /**
     * Of paths, those whose conflict git can put back as it was before
     * they were staged resolved; empty if none, or the backend can't.
     * Off the event thread.
     */
    default Set<String> resolvedConflicts(VcsRepository repository, List<String> paths) {
        return Set.of();
    }

    /** Puts paths' conflicts back in the index as resolvedConflicts has them, the working tree as it is. */
    default VcsResult unresolve(VcsRepository repository, List<String> paths) {
        return null;
    }

    /** Stages every change to a tracked file. */
    default VcsResult stageAll(VcsRepository repository) {
        return null;
    }

    /** Takes files' staged changes back out of the next commit, both paths of a rename. */
    default VcsResult unstage(VcsRepository repository, List<FileChange> changes) {
        return null;
    }

    /** Takes everything staged back out. */
    default VcsResult unstageAll(VcsRepository repository) {
        return null;
    }

    /** Puts files in the working tree back as they are staged, losing their unstaged changes. */
    default VcsResult discard(VcsRepository repository, List<String> paths) {
        return null;
    }

    /** Puts files in a conflict as one side has them, ours (HEAD's) or theirs; they stay unmerged. */
    default VcsResult checkoutSide(VcsRepository repository, List<String> paths, boolean ours) {
        return null;
    }

    /** Puts a conflict's markers back in files, with the base between them. */
    default VcsResult restoreConflict(VcsRepository repository, List<String> paths) {
        return null;
    }

    /**
     * Applies a patch (see Patch) to the index, the working tree, or both,
     * or with reverse takes it back out.
     */
    default VcsResult apply(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        return null;
    }

    /** Whether apply would apply the patch so, changing nothing. */
    default boolean applies(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        return false;
    }

    /** Commits what is staged with message (null to keep the last's, with AMEND and KEEP_MESSAGE). */
    default VcsResult commit(VcsRepository repository, String message, Set<CommitOption> options) {
        return null;
    }

    /** Whether commits show their diff under the message unless told not to: git's commit.verbose. */
    default boolean verboseCommits(VcsRepository repository) {
        return false;
    }

    /** Who commits: a name and a mail address, either null if not set. */
    record Identity(String name, String email) {}

    /**
     * Who the user commits as in repository, as its blame names them (git's
     * user.name and user.email through the mailmap), or null if neither is
     * set. Off the event thread.
     */
    default Identity identity(VcsRepository repository) {
        return null;
    }

    /**
     * A number that changes whenever identity's answer may, as its config
     * changes; cheap, so the event thread can ask. 0 if it never does.
     */
    default long identityStamp(VcsRepository repository) {
        return 0;
    }

    /**
     * The diff a commit made with options would make, as patch text, for
     * the message's verbose view; null if it can't be read. Off the event
     * thread.
     */
    default byte[] readCommitDiff(VcsRepository repository, Set<CommitOption> options) {
        return null;
    }

    /**
     * The texts a file's change marks are against, path relative to the
     * root; null if this backend gives none, or the file is in a conflict.
     * Off the event thread.
     */
    default Bases readBases(VcsRepository repository, String path) {
        return null;
    }

    /** The last commit's message, or null. Off the event thread. */
    default String lastMessage(VcsRepository repository) {
        return null;
    }

    /**
     * A commit's message and diff, ignoring ignored's whitespace, as patch
     * text; null if it can't be read. Off the event thread.
     */
    default byte[] readCommit(VcsRepository repository, String id, Whitespace ignored) {
        return null;
    }

    // The log's reads, off the event thread.

    /**
     * Commits query takes, newest first with each before its parents, from
     * the skip-th on, at most max; null if this backend has no log.
     */
    default List<LogCommit> readLog(VcsRepository repository, LogQuery query, int skip, int max) {
        return null;
    }

    /**
     * Blames path in the background: sink told of each run of lines as it
     * is read. With revision null, its text in the working tree or as
     * edited, given as contents on stdin; with one, the file as that
     * revision has it, contents ignored. A line not as committed has a
     * commit whose isUncommitted() is true; a path with no history is
     * told by sink.noHistory(), with no lines. A change only to the whitespace ignored, as
     * near as the backend can, leaves the line its older commit's. Null if
     * the backend can't.
     */
    default VcsResult blame(
            VcsRepository repository,
            String path,
            String revision,
            byte[] contents,
            Whitespace ignored,
            BlameCommit.Sink sink) {
        return null;
    }

    /**
     * The stashes as the log shows them, newest first, each badged with its
     * name and drawn off the commit it was made on alone: its graph's parent
     * is its first; none if it has none, null if they can't be read.
     */
    default List<LogCommit> readStashes(VcsRepository repository) {
        return List.of();
    }

    /** A commit's whole message, or null. */
    default String readMessage(VcsRepository repository, String id) {
        return null;
    }

    /** The files a commit changed against parent (null for a root), or null if they can't be read. */
    default List<CommitChange> readCommitChanges(VcsRepository repository, String id, String parent) {
        return null;
    }

    /**
     * One file's diff in a commit against parent (null for a root),
     * ignoring ignored's whitespace: {@link FileDiff#EMPTY} for none, null
     * if it can't be read.
     */
    default FileDiff readChangeDiff(
            VcsRepository repository,
            String id,
            String parent,
            CommitChange change,
            Whitespace ignored) {
        return null;
    }

    /** The working tree's file against a commit's, path at the commit, or null if they don't differ. */
    default FileDiff readDiffToWorkingTree(VcsRepository repository, String id, String path) {
        return null;
    }

    /** A conflicted file's two sides as the index holds them; a side null where it has none, as one that deleted it. */
    record ConflictSides(byte[] ours, byte[] theirs) {}

    /** path's conflict's sides, read at once; null if they can't be read. Off the event thread. */
    default ConflictSides readConflictSides(VcsRepository repository, String path) {
        return null;
    }

    /** A file as a commit has it, or null. */
    default byte[] readFileAt(VcsRepository repository, String id, String path) {
        return null;
    }

    /**
     * HEAD, the branches, remotes, tags and stashes, each with its commit
     * and date, and a local branch's ahead and behind its upstream; empty if
     * none can be read. A detached HEAD is first, as HEAD.
     */
    default List<LogRefTip> readRefTips(VcsRepository repository) {
        return List.of();
    }

    /** The remotes' names; none if it has none, or no remotes at all. */
    default List<String> readRemotes(VcsRepository repository) {
        return List.of();
    }

    /**
     * Fetches from remote, or from every remote for null: refspec, as a
     * remote's branch, or for null what the remote fetches. It never asks
     * for a password.
     */
    default VcsResult fetch(VcsRepository repository, String remote, String refspec, Set<FetchOption> options) {
        return null;
    }

    /**
     * Records parent as the branch branch is to be merged into, by name;
     * null removes the record.
     */
    default VcsResult setParent(VcsRepository repository, String branch, String parent) {
        return null;
    }

    /** Stashes the changes, with message if not null. */
    default VcsResult stash(VcsRepository repository, String message, Set<StashOption> options) {
        return null;
    }

    /** Stashes the changes to paths, whole, as stash does all of them; null where it can't. */
    default VcsResult stash(VcsRepository repository, String message, Set<StashOption> options, List<String> paths) {
        return paths.isEmpty() ? stash(repository, message, options) : null;
    }

    /**
     * Applies the stash id, named name; with pop drops it too if it applied
     * cleanly, failing without either if name is no longer id.
     */
    default VcsResult stashApply(VcsRepository repository, String name, String id, boolean pop) {
        return null;
    }

    /**
     * Drops the stash named name; fails without it if name is no longer
     * the stash id. With no name, the stash id wherever it is now.
     */
    default VcsResult stashDrop(VcsRepository repository, String name, String id) {
        return null;
    }

    /**
     * Checks ref out: a branch; a remote's branch as a new local one
     * tracking it; anything else detached.
     */
    default VcsResult switchTo(VcsRepository repository, LogRef ref) {
        return null;
    }

    /** Makes branch name starting at from, the ref named; with checkout, checks it out. */
    default VcsResult createBranch(VcsRepository repository, String name, String from, boolean checkout) {
        return null;
    }

    default VcsResult renameBranch(VcsRepository repository, String branch, String name) {
        return null;
    }

    /** Deletes branch; without force, failing if its commits aren't merged. */
    default VcsResult deleteBranch(VcsRepository repository, String branch, boolean force) {
        return null;
    }

    /**
     * The commits on branch and no other branch, tag or remote's branch,
     * newest first, each as "abc1234 subject"; null if they can't be read.
     */
    default List<String> readOnlyOn(VcsRepository repository, String branch) {
        return null;
    }

    /** Merges ref, the ref named, into what is checked out. */
    default VcsResult merge(VcsRepository repository, String ref, Set<MergeOption> options) {
        return null;
    }

    /**
     * Continues, skips or aborts what the system has in progress, as
     * Snapshot.inProgress names it: a merge's continue its commit, with
     * git's message; null if it can't, or nothing is in progress.
     */
    default VcsResult finish(VcsRepository repository, Finish how) {
        return null;
    }

    /** Whether the stash id is still a stash. Off the event thread. */
    default boolean hasStash(VcsRepository repository, String id) {
        return true;
    }

    /** j's note of a stash's conflicted apply or pop, as writeStashNote kept it; null for none. */
    default VcsRepository.StashConflict readStashNote(VcsRepository repository) {
        return null;
    }

    /** Keeps note where it outlives j, or with null drops it. */
    default void writeStashNote(VcsRepository repository, VcsRepository.StashConflict note) {}

    /** The local branches checked out, the latest first, each once; some may be gone. */
    default List<String> readRecentBranches(VcsRepository repository) {
        return List.of();
    }

    /** How many refs a fetch's result says it moved; -1 if it doesn't say. */
    default int fetchedRefs(VcsResult result) {
        return -1;
    }

    /**
     * The authors of the newest max commits, newest first, each once; by
     * default from the log, which a backend can read more cheaply.
     */
    default List<String> readAuthors(VcsRepository repository, int max) {
        final List<LogCommit> recent = readLog(repository, LogQuery.ALL, 0, max);
        final Set<String> out = new LinkedHashSet<>();
        if (recent != null)
            for (LogCommit c : recent)
                out.add(c.author());
        return List.copyOf(out);
    }

    /**
     * The commits the log query reads starts from, as ids: its revisions,
     * or every branch, remote and HEAD (and tag, with tags); null where
     * they can't be told. A log reads its later pages from these, so that a
     * commit or a fetch made meanwhile shifts none.
     */
    default List<String> readStarts(VcsRepository repository, LogQuery query) {
        return null;
    }

    /** Whether the log query reads lists the commit id; true where it can't be told. */
    default boolean logHas(VcsRepository repository, LogQuery query, String id) {
        return true;
    }

    /** The paths of the files tracked, from the root, separated by '/'. */
    default List<String> readPaths(VcsRepository repository) {
        return List.of();
    }

    /** What to watch for changes made outside j: the repository's metadata, not its working tree. */
    default List<RepositoryWatcher.Watch> watches(VcsRepository repository) {
        return List.of();
    }

    /**
     * What the repository rooted at root ignores; null when the backend has
     * no rules, so that an enclosing repository's apply, as git's around an
     * old CVS tree.
     */
    default Ignores ignores(Path root) {
        return null;
    }

    /** Whether a file in no tree any backend recognizes belongs to this one. */
    default boolean claimsUntracked() {
        return false;
    }

    /** The file's status, or null. */
    default VersionControlEntry getEntry(Buffer buffer) {
        return null;
    }

    default void replaceComment(Editor editor, String comment) {
        CheckinBuffer.replaceText(editor, comment);
    }

    default String extractComment(CheckinBuffer buffer) {
        return buffer.getText();
    }

    /** Commits what the checkin buffer describes. */
    default void finish(Editor editor, CheckinBuffer buffer) {}

    /** Opens the editor's read-only file for editing, as "p4 edit" does. True if it did. */
    default boolean autoEdit(Editor editor) {
        return false;
    }

    /** Like {@link #autoEdit(Editor)}, for a file not in a buffer. */
    default boolean autoEdit(File file) {
        return false;
    }

    /** A line for the properties dialog, or null. */
    default String getStatusString(File file) {
        return null;
    }

    /** Goes to the source of the diff line at the caret. False if it can't. */
    default boolean gotoDiffSource(Editor editor, DiffOutputBuffer buffer) {
        return false;
    }
}
