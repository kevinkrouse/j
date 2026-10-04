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

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VersionControlEntry;

public final class SvnBackend implements VcsBackend {
    @Override
    public int id() {
        return VC_SVN;
    }

    @Override
    public String name() {
        return "svn";
    }

    @Override
    public boolean isRoot(File dir) {
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
            dir -> dir
        );
        return true;
    }
}
