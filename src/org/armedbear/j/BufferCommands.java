/*
 * BufferCommands.java
 *
 * Copyright (C) 1998-2003 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

import org.armedbear.j.mode.compilation.CompilationBuffer;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.util.Utilities;

/** Switching, creating and killing buffers, and the directory buffer's commands. */
public final class BufferCommands {
    private BufferCommands() {}

    public static void nextBuffer(Editor editor) {
        Buffer buf = Editor.getBufferList().getNextPrimaryBuffer(editor.getBuffer());
        if (buf == null)
            return;
        if (buf.isPaired()) {
            Buffer secondary = buf.getSecondary();
            if (secondary != null) {
                if (secondary.getLastActivated() > buf.getLastActivated())
                    buf = secondary;
            }
        }
        if (buf != editor.getBuffer())
            editor.switchToBuffer(buf);
    }

    /**
     * {@code prevBuffer alternate} goes to the buffer used most recently
     * before this one -- vim's alternate file, CTRL-^ -- rather than to the
     * one before this in the buffer list.
     */
    public static void prevBuffer(Editor editor, String parameters) {
        if (
            parameters == null
                || !parameters.trim().equalsIgnoreCase("alternate")
        ) {
            prevBuffer(editor);
            return;
        }
        final Buffer alternate = alternateBuffer(editor);
        if (alternate == null) {
            editor.status("E23: No alternate file");
            return;
        }
        editor.switchToBuffer(alternate);
    }

    /** The buffer activated most recently, other than this one. */
    static Buffer alternateBuffer(Editor editor) {
        Buffer best = null;
        for (Buffer b : Editor.getBufferList()) {
            if (b == editor.getBuffer() || !b.isPrimary())
                continue;
            if (best == null || b.getLastActivated() > best.getLastActivated())
                best = b;
        }
        return best;
    }

    public static void prevBuffer(Editor editor) {
        Buffer buf = Editor.getBufferList().getPreviousPrimaryBuffer(editor.getBuffer());
        if (buf == null)
            return;
        if (buf.isPaired()) {
            Buffer secondary = buf.getSecondary();
            if (secondary != null) {
                if (secondary.getLastActivated() > buf.getLastActivated())
                    buf = secondary;
            }
        }
        if (buf != editor.getBuffer())
            editor.switchToBuffer(buf);
    }

    public static void newBuffer(Editor editor) {
        Buffer buf = new Buffer(0);
        editor.makeNext(buf);
        editor.switchToBuffer(buf);
    }

    public static void tempBufferQuit(Editor editor) {
        if (editor.getBuffer() instanceof CompilationBuffer || editor.getBuffer().isTransient()) {
            if (editor.getBuffer().unsplitOnClose()) {
                editor.getBuffer().windowClosing();
                WindowCommands.otherWindow(editor);
                WindowCommands.unsplitWindow(editor);
            }
            maybeKillBuffer(editor, editor.getBuffer());
            Editor.restoreFocus();
            Sidebar.refreshSidebarInAllFrames();
            return;
        }
    }

    public static void killBuffer(Editor editor) {
        try {
            if (editor.getBuffer().isSecondary()) {
                editor.getBuffer().windowClosing();
                WindowCommands.otherWindow(editor);
                WindowCommands.unsplitWindow(editor);
                maybeKillBuffer(Editor.currentEditor(), editor.getBuffer());
                Editor.restoreFocus();
                return;
            }
            Buffer buf = editor.getBuffer().getSecondary();
            if (buf != null) {
                WindowCommands.unsplitWindow(editor);
                maybeKillBuffer(editor, buf);
                return;
            }
            // Normal buffer.
            maybeKillBuffer(editor, editor.getBuffer());
            // If we're left with two editors next to each other showing exactly the same thing,
            // unsplit the window.
            Frame frame = Editor.currentEditor().getFrame();
            frame.coalesceEditors(frame.getCurrentEditor());
        }
        finally {
            Sidebar.refreshSidebarInAllFrames();
        }
    }

    public static void maybeKillBuffer(Editor editor, Buffer toBeKilled) {
        if (!Editor.getBufferList().contains(toBeKilled)) {
            Debug.bug("maybeKillBuffer buffer not in list " + toBeKilled);
            return;
        }

        // Don't kill the last buffer if it's a directory.
        if (Editor.getBufferList().size() == 1 && toBeKilled instanceof DirectoryBuffer)
            return;

        // Cancel background process if any.
        BackgroundProcess backgroundProcess = toBeKilled.getBackgroundProcess();
        if (backgroundProcess != null) {
            Log.debug("maybeKillBuffer calling backgroundProcess.cancel...");
            backgroundProcess.cancel();
            // backgroundProcess.cancel() may have killed the buffer, so
            // verify that it's still in the list.
            if (!Editor.getBufferList().contains(toBeKilled)) {
                Log.debug("maybeKillBuffer buffer is no longer in list");
                return;
            }
        }

        Mode mode = toBeKilled.getMode();
        if (mode == null || mode.confirmClose(editor, toBeKilled))
            toBeKilled.kill();
    }

    public static void dirHome(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer)
            ((DirectoryBuffer) editor.getBuffer()).home();
    }

    public static void dirTagFile(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer)
            ((DirectoryBuffer) editor.getBuffer()).tagFileAtDot();
    }

    public static void dirBrowseFile(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer && !editor.getBuffer().getFile().isRemote()) {
            DirectoryBuffer d = (DirectoryBuffer) editor.getBuffer();
            d.browseFileAtDot();
        }
    }

    public static void dirDeleteFiles(Editor editor) {
        if (editor.getMark() != null && editor.getMarkLine() != editor.getDotLine()) {
            MessageDialog.showMessageDialog(
                editor,
                "This operation is not supported with multi-line text selections.",
                "Delete Files"
            );
            return;
        }
        if (editor.getBuffer() instanceof DirectoryBuffer) {
            if (editor.getBuffer().getFile() instanceof SshFile) {
                MessageDialog
                    .showMessageDialog(editor, "Deletions are not yet supported in ssh directory buffers.", "Error");
                return;
            }
            ((DirectoryBuffer) editor.getBuffer()).deleteFiles();
            ProjectFiles.invalidate();
        }
    }

    public static void dirCopyFile(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer && editor.getBuffer().getFile().isLocal()) {
            ((DirectoryBuffer) editor.getBuffer()).copyFileAtDot();
            ProjectFiles.invalidate();
        }
    }

    public static void dirGetFile(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer && editor.getBuffer().getFile() instanceof FtpFile)
            ((DirectoryBuffer) editor.getBuffer()).getFileAtDot();
    }

    public static void dirMoveFile(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer && editor.getBuffer().getFile().isLocal()) {
            ((DirectoryBuffer) editor.getBuffer()).moveFileAtDot();
            ProjectFiles.invalidate();
        }
    }

    public static void dirRescan(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer) {
            editor.setWaitCursor();
            ((DirectoryBuffer) editor.getBuffer()).rescan();
            editor.setDefaultCursor();
        }
    }

    public static void dirHomeDir(Editor editor) {
        File homeDir = File.getInstance(Utilities.getUserHome());
        if (editor.getBuffer() instanceof DirectoryBuffer) {
            if (!editor.getBuffer().getFile().equals(homeDir))
                ((DirectoryBuffer) editor.getBuffer()).changeDirectory(homeDir);
        } else {
            Buffer buf = Editor.getBuffer(homeDir);
            if (buf != null) {
                editor.makeNext(buf);
                editor.activate(buf);
            }
        }
    }

    public static void dirUpDir(Editor editor) {
        if (editor.getBuffer() instanceof DirectoryBuffer)
            ((DirectoryBuffer) editor.getBuffer()).upDir();
    }
}
