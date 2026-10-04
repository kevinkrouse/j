/*
 * P4Backend.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.p4;

import static org.armedbear.j.Constants.*;

import org.armedbear.j.Buffer;
import org.armedbear.j.Debug;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.vcs.VcsBackend;

public final class P4Backend implements VcsBackend {
    @Override
    public int id() {
        return VC_P4;
    }

    @Override
    public String name() {
        return "p4";
    }

    // Perforce has no metadata directory; its environment says it's in use.
    @Override
    public boolean claimsUntracked() {
        return System.getenv("P4CONFIG") != null || System.getenv("P4PORT") != null;
    }

    @Override
    public void replaceComment(Editor editor, String comment) {
        P4.replaceComment(editor, comment);
    }

    @Override
    public String extractComment(CheckinBuffer buffer) {
        return P4.extractComment(buffer);
    }

    @Override
    public void finish(Editor editor, CheckinBuffer buffer) {
        P4.finish(editor, buffer);
    }

    @Override
    public boolean autoEdit(Editor editor) {
        Buffer buffer = editor.getBuffer();
        if (!buffer.getBooleanProperty(Property.P4_AUTO_EDIT) || buffer.getType() != Buffer.TYPE_NORMAL)
            return false;
        File file = buffer.getFile();
        return file != null && file.isLocal() && file.isFile() && P4.autoEdit(editor);
    }

    @Override
    public boolean autoEdit(File file) {
        return Editor.preferences().getBooleanProperty(Property.P4_AUTO_EDIT) && P4.autoEdit(file);
    }

    @Override
    public String getStatusString(File file) {
        return P4.getStatusString(file);
    }

    @Override
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
        final String text = dotLine.getText();
        int lineNumber = 0;
        int count = 0;
        Line line = dotLine;
        if (line.getText().startsWith("@@")) {
            lineNumber = DiffMode.parseLineNumber(line);
        } else {
            line = line.previous();
            while (line != null && !line.getText().startsWith("@@")) {
                if (!line.getText().startsWith("-"))
                    ++count;
                line = line.previous();
            }
            if (line == null)
                return;
            Debug.assertTrue(line.getText().startsWith("@@"));
            lineNumber = DiffMode.parseLineNumber(line);
        }
        // Our line numbers are zero-based.
        if (--lineNumber < 0)
            return;
        lineNumber += count;
        Buffer parentBuffer = diffOutputBuffer.getParentBuffer();
        File dir;
        if (parentBuffer != null)
            dir = parentBuffer.getCurrentDirectory();
        else
            dir = diffOutputBuffer.getDirectory();
        line = line.previous();
        while (line != null && !line.getText().endsWith(" ===="))
            line = line.previous();
        if (line == null)
            return;
        int index = line.getText().lastIndexOf(" - ");
        if (index >= 0) {
            String filename = line.getText().substring(index + 3);
            if (filename.endsWith(" ===="))
                filename = filename.substring(0, filename.length() - 5);
            File file = File.getInstance(dir, filename);
            if (file != null && file.isFile()) {
                Buffer buf = editor.getBuffer(file);
                if (buf != null)
                    DiffMode.gotoLocation(
                        editor,
                        buf,
                        lineNumber,
                        dotOffset > 0 ? dotOffset - 1 : 0
                    );
            }
        }
    }

}
