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

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.diff.DiffOutputBuffer;

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
