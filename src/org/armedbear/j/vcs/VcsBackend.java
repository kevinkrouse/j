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

import java.util.List;
import java.util.Set;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.vcs.diff.FileDiff;

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
     * to read: the ones expanded.
     */
    default org.armedbear.j.vcs.status.StatusData readStatus(
            VcsRepository repository,
            java.util.function.BiPredicate<String, String> wantsDiff) {
        return null;
    }

    /**
     * The diffs of paths, relative to the root: the working tree's, or with
     * staged the next commit's. Off the event thread.
     */
    default List<org.armedbear.j.vcs.diff.FileDiff> readDiff(
            VcsRepository repository,
            boolean staged,
            List<String> paths) {
        return List.of();
    }

    // Writes, run off the event thread at the repository's root. Each
    // returns what ran, or null if the backend can't.

    /** Stages files whole, new, changed or deleted, by path. */
    default VcsResult stage(VcsRepository repository, List<String> paths) {
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

    /** A commit's message and diff, as patch text; null if it can't be read. Off the event thread. */
    default byte[] readCommit(VcsRepository repository, String id) {
        return null;
    }

    // The log's reads, off the event thread.

    /**
     * Commits query takes, newest first with each before its parents, from
     * the skip-th on, at most max; null if this backend has no log.
     */
    default List<org.armedbear.j.vcs.log.LogCommit> readLog(
            VcsRepository repository,
            org.armedbear.j.vcs.log.LogQuery query,
            int skip,
            int max) {
        return null;
    }

    /** A commit's whole message, or null. */
    default String readMessage(VcsRepository repository, String id) {
        return null;
    }

    /** The files a commit changed against parent (null for a root), or null if they can't be read. */
    default List<org.armedbear.j.vcs.log.CommitChange> readCommitChanges(
            VcsRepository repository,
            String id,
            String parent) {
        return null;
    }

    /**
     * One file's diff in a commit against parent (null for a root):
     * {@link FileDiff#EMPTY} for none, null if it can't be read.
     */
    default FileDiff readChangeDiff(
            VcsRepository repository,
            String id,
            String parent,
            org.armedbear.j.vcs.log.CommitChange change) {
        return null;
    }

    /** The working tree's file against a commit's, path at the commit, or null if they don't differ. */
    default FileDiff readDiffToWorkingTree(VcsRepository repository, String id, String path) {
        return null;
    }

    /** A file as a commit has it, or null. */
    default byte[] readFileAt(VcsRepository repository, String id, String path) {
        return null;
    }

    /** What to watch for changes made outside j: the repository's metadata, not its working tree. */
    default List<RepositoryWatcher.Watch> watches(VcsRepository repository) {
        return List.of();
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
