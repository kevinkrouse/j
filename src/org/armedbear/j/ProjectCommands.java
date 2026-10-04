/*
 * ProjectCommands.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import org.armedbear.j.mode.dir.DirectoryBuffer;

/** Commands on the current buffer's project; see ProjectRoot. */
public final class ProjectCommands {
    private ProjectCommands() {}

    /** Shows the project root in a directory buffer: this one, if it is one. */
    public static void dirProjectDir(Editor editor) {
        File root = ProjectRoot.find(editor.getBuffer());
        if (root == null) {
            editor.status("Not in a project");
            return;
        }
        if (editor.getBuffer() instanceof DirectoryBuffer dir) {
            if (dir.getFile().equals(root))
                editor.status("Already at the project root");
            else
                dir.changeDirectory(root);
            return;
        }
        Buffer buf = Editor.getBuffer(root);
        if (buf != null) {
            editor.makeNext(buf);
            editor.activate(buf);
        }
    }

    /** Rescans the current project's file list for the finder. */
    public static void rescanProject(Editor editor) {
        File root = ProjectRoot.find(editor.getBuffer());
        if (root == null) {
            editor.status("Not in a project");
            return;
        }
        ProjectFiles.forRoot(root).rescan();
        editor.status("Rescanning " + root.canonicalPath());
    }
}
