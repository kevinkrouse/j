/*
 * VersionControl.java
 *
 * Copyright (C) 2005 Peter Graves
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

package org.armedbear.j.vcs;

import java.lang.StringBuilder;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.OutputBuffer;
import org.armedbear.j.Property;
import org.armedbear.j.ShellCommand;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.git.GitStatusCache;

public abstract class VersionControl implements Constants {
    public static void invalidate() {
        GitStatusCache.invalidate();
    }

    public static VersionControlEntry getEntry(Buffer buffer) {
        final VersionControlEntry prevEntry = buffer.getVCSEntry();
        final int vc = prevEntry != null ? prevEntry.getVersionControl() : guessVCS(buffer);
        final VcsBackend backend = VcsBackends.get(vc);
        return backend == null ? null : backend.getEntry(buffer);
    }

    /**
     * The id of the backend whose tree holds the file: the nearest
     * directory, up to the home directory, that a backend recognizes.
     * Otherwise a backend that claims untracked files, or -1.
     */
    public static int guessVCS(Buffer buffer) {
        return guessVCS(buffer.getFile());
    }

    public static int guessVCS(File file) {
        if (file == null || file.isRemote())
            return -1;
        final File home = Directories.getUserHomeDirectory();
        for (File dir = file.getParentFile(); dir != null; dir = dir.getParentFile()) {
            for (VcsBackend backend : VcsBackends.all()) {
                if (backend.isRoot(dir))
                    return backend.id();
            }
            if (dir.equals(home))
                break;
        }
        for (VcsBackend backend : VcsBackends.all()) {
            if (backend.claimsUntracked())
                return backend.id();
        }
        return -1;
    }

    protected static void diffCompleted(
        Editor editor,
        Buffer parentBuffer,
        String title,
        String output,
        int vcType
    ) {
        if (output.length() == 0) {
            parentBuffer.setBusy(false);
            MessageDialog.showMessageDialog(
                editor,
                "No changes since latest version",
                parentBuffer.getFile().getName()
            );
        } else {
            DiffOutputBuffer buf =
                new DiffOutputBuffer(parentBuffer, output, vcType);
            buf.setTitle(title);
            editor.makeNext(buf);
            editor.activateInOtherWindow(buf);
            parentBuffer.setBusy(false);
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == parentBuffer)
                    ed.setDefaultCursor();
            }
        }
    }

    protected static void processCompleted(Buffer buffer, String output) {
        buffer.setText(output);
        buffer.setBusy(false);
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == buffer) {
                ed.setDot(buffer.getFirstLine(), 0);
                ed.setTopLine(buffer.getFirstLine());
                ed.setUpdateFlag(REPAINT);
                ed.updateDisplay();
            }
        }
    }

    protected static void vcsCompleted(
        Editor editor,
        Buffer buffer,
        boolean diff,
        String title,
        String output,
        int vcType,
        boolean checkVCS
    ) {
        if (output != null && output.length() > 0) {
            Buffer buf;
            if (diff)
                buf = new DiffOutputBuffer(buffer, output, vcType);
            else
                buf = OutputBuffer.getOutputBuffer(output);
            buf.setTitle(title);
            editor.makeNext(buf);
            editor.activateInOtherWindow(buf);
        }

        if (checkVCS) {
            buffer.checkVCS();
            buffer.setBusy(false);
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == buffer) {
                    ed.setDefaultCursor();
                    // Update version information in status bar.
                    ed.getFrame().repaintStatusBar();
                }
            }
        }
    }

    /**
     * Parse a command line and replace tokens if needed.
     *
     * @param cmd the command line to parse.
     * @param args arguments to the command will be concatinated to the cmd or null.
     * @param replaceFileTokens if true, replace '%' with the current buffer's filename.
     * @param appendFilename if true, append the current buffer's filename if no '%' token was already replaced in the args.
     * @return the parsed command line.
     */
    protected static String parseArgs(String cmd, String args, boolean replaceFileTokens, boolean appendFilename) {
        final Editor editor = Editor.currentEditor();
        final Buffer parentBuffer = editor.getBuffer();
        final File file = parentBuffer.getFile();

        boolean hasFilename = false;
        if (args != null)
            cmd = cmd + ' ' + args;
        List<String> tokens = Utilities.tokenize(cmd);
        StringBuilder sb = new StringBuilder();
        if (replaceFileTokens) {
            for (String arg : tokens) {
                if (arg.equals("%")) {
                    hasFilename = true;
                    if (file != null)
                        arg = Utilities.maybeQuote(file.canonicalPath());
                } else {
                    arg = Utilities.quoteUserWord(arg);
                }
                sb.append(arg);
                sb.append(' ');
            }
        } else {
            for (String arg : tokens) {
                if (arg.charAt(0) != '-') {
                    // assume filename.
                    hasFilename = true;
                    break;
                }
            }

            sb.append(args);
        }

        if (appendFilename && !hasFilename && file != null) {
            if (sb.charAt(sb.length() - 1) != ' ')
                sb.append(" ");
            sb.append(Utilities.maybeQuoteFile(file.getName()));
        }

        return sb.toString();
    }

    // Implementation.
    protected static String command(String cmd, File workingDirectory) {
        ShellCommand shellCommand = new ShellCommand(cmd, workingDirectory);
        shellCommand.run();
        return shellCommand.getOutput();
    }

    protected static void outputBufferCommand(final Editor editor, final String cmd, final File workingDirectory) {
        editor.setWaitCursor();
        Runnable commandRunnable = () -> {
            final String output = command(cmd, workingDirectory);
            Runnable completionRunnable = () -> {
                OutputBuffer buf = OutputBuffer.getOutputBuffer(output);
                buf.setTitle(cmd);
                editor.makeNext(buf);
                editor.activateInOtherWindow(buf);
                editor.setDefaultCursor();
            };
            SwingUtilities.invokeLater(completionRunnable);
        };
        Background.start("VersionControl command", commandRunnable);
    }

    protected static List<Buffer> getModifiedBuffers() {
        ArrayList<Buffer> list = null;
        for (Buffer buf : Editor.getBufferList()) {
            if (!buf.isModified())
                continue;
            if (buf.isUntitled())
                continue;
            if (buf.isManagedFile())
                continue;
            if (buf.getModeId() == CHECKIN_MODE)
                continue;
            if (buf.getFile() != null && buf.getFile().isLocal()) {
                if (list == null)
                    list = new ArrayList<Buffer>();
                list.add(buf);
            }
        }
        return list;
    }

    protected static boolean saveModifiedBuffers(Editor editor, List<Buffer> list) {
        editor.setWaitCursor();
        int numErrors = 0;
        for (Buffer buf : list) {
            if (buf.getFile() != null && buf.getFile().isLocal()) {
                editor.status("Saving modified buffers...");
                if (buf.getBooleanProperty(Property.REMOVE_TRAILING_WHITESPACE))
                    buf.removeTrailingWhitespace();
                if (!buf.save())
                    ++numErrors;
            }
        }
        editor.setDefaultCursor();
        if (numErrors == 0) {
            editor.status("Saving modified buffers...done");
            return true;
        }
        // User will already have seen detailed error information from Buffer.save().
        editor.status("");
        return false;
    }
}
