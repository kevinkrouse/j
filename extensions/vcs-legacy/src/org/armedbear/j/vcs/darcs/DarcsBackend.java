/*
 * DarcsBackend.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.darcs;

import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Debug;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.VcsBackend;

public final class DarcsBackend implements VcsBackend, Constants {
    public int id() {
        return VC_DARCS;
    }

    public String name() {
        return "darcs";
    }

    public boolean isRoot(File dir) {
        File darcs = File.getInstance(dir, "_darcs");
        return darcs != null && darcs.isDirectory();
    }

    public boolean gotoDiffSource(Editor editor, DiffOutputBuffer buffer) {
        gotoFile(editor, buffer);
        return true;
    }

    private static void gotoFile(
        Editor editor,
        DiffOutputBuffer diffOutputBuffer
    ) {
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();
        int lineNumber = 0;
        int context = 0;
        int added = 0;
        Line line = dotLine;
        File dir;
        Buffer parentBuffer = diffOutputBuffer.getParentBuffer();
        if (parentBuffer != null)
            dir = parentBuffer.getCurrentDirectory();
        else
            dir = diffOutputBuffer.getDirectory();
        while (line != null && !line.getText().startsWith("hunk ")) {
            if (line != dotLine && line.getText().startsWith("+"))
                ++added;
            else if (!line.getText().startsWith("-"))
                ++context;
            line = line.previous();
        }
        if (line == null)
            return;
        Debug.assertTrue(line.getText().startsWith("hunk "));
        String text = line.getText();
        int index = text.lastIndexOf(' ');
        try {
            lineNumber = Utilities.parseInt(text.substring(index + 1));
        }
        catch (NumberFormatException e) {
            Log.error(e);
            return;
        }
        Log.debug("lineNumber = " + lineNumber);
        // Our line numbers are zero-based.
        if (--lineNumber < 0)
            return;
        String filename = text.substring(5, index);
        Log.debug("filename = " + filename);
        File darcs_root = Darcs.findRoot(dir);
        Log.debug("darcs_root = " + darcs_root);
        if (darcs_root != null)
            dir = darcs_root;
        File file = File.getInstance(dir, filename);
        if (file != null && file.isFile()) {
            Buffer buf = editor.getBuffer(file);
            if (buf != null)
                DiffMode.gotoLocation(editor, buf, lineNumber + added, 0);
        }
    }

}
