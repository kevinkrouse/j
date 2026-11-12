/*
 * VersionControlHooks.java
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
 * What the old per-system commands (checkin, diff, auto-edit) and a
 * buffer's status ask of a backend, which implements it beside VcsBackend:
 * kept apart, so the shared layer needs no editor type.
 */
public interface VersionControlHooks {
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

    /** Goes to the source of the diff line at the caret. False if it can't. */
    default boolean gotoDiffSource(Editor editor, DiffOutputBuffer buffer) {
        return false;
    }
}
