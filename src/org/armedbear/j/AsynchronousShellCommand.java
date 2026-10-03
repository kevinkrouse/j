/*
 * AsynchronousShellCommand.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import java.io.IOException;
import java.io.InputStream;
import javax.swing.SwingUtilities;
import org.armedbear.j.mode.text.PlainTextFormatter;
import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.util.ReaderThread;
import org.armedbear.j.util.Utilities;

public final class AsynchronousShellCommand implements Constants, Runnable {
    private final String command;
    private final File directory;
    private final String cmdline;
    private final ShellCommandOutputBuffer outputBuffer;
    private final Position posEndOfBuffer;

    private Process process;
    private Thread thread;

    public AsynchronousShellCommand(
        String command,
        File directory,
        ShellCommandOutputBuffer buf
    ) {
        this.command = command;
        this.directory = directory;
        cmdline = command;
        this.outputBuffer = buf;
        this.posEndOfBuffer = new Position(buf.getFirstLine(), 0);
        buf.setShellCommand(this);
    }

    private void start() {
        thread = new Thread(this);
        thread.start();
    }

    public void run() {
        if (!Platform.isPlatformUnix()) {
            Debug.bug();
            return;
        }
        try {
            if (cmdline != null) {
                process = new ProcessBuilder("/bin/sh", "-c", cmdline)
                    .directory(new java.io.File(directory.canonicalPath()))
                    .start();
            }
        }
        catch (IOException e) {
            Log.error(e);
            appendLater(e.getMessage() + "\n");
        }
        if (process != null) {
            ShellCommandReaderThread stdoutThread =
                new ShellCommandReaderThread(process.getInputStream());
            stdoutThread.start();
            ShellCommandReaderThread stderrThread =
                new ShellCommandReaderThread(process.getErrorStream());
            stderrThread.start();
            try {
                process.waitFor();
            }
            catch (InterruptedException e) {
                // User has closed the output buffer.
                killProcess();
            }
        }
    }

    private void interrupt() {
        if (thread != null)
            thread.interrupt();
    }

    // The shell and everything it started.
    private void killProcess() {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor();
        }
        catch (InterruptedException e) {
            Log.debug(e);
        }
    }

    private void appendLater(final String s) {
        Runnable runnable = () -> {
            outputBuffer.insertString(posEndOfBuffer, s);
            if (outputBuffer.needsRenumbering())
                outputBuffer.renumber();
            outputBuffer.enforceOutputLimit(Property.SHELL_OUTPUT_LIMIT);
            for (EditorIterator it = new EditorIterator(); it.hasNext();) {
                Editor ed = it.next();
                if (ed.getBuffer() == outputBuffer) {
                    ed.eob();
                    ed.getDisplay().setReframe(-2);
                    ed.setUpdateFlag(REPAINT);
                    ed.updateDisplay();
                }
            }
        };
        SwingUtilities.invokeLater(runnable);
    }

    private class ShellCommandReaderThread extends ReaderThread {
        public ShellCommandReaderThread(InputStream inputStream) {
            super(inputStream);
        }

        public void update(final String s) {
            appendLater(s);
        }
    }

    private static class ShellCommandOutputBuffer extends Buffer {
        private AsynchronousShellCommand shellCommand;

        public ShellCommandOutputBuffer() {
            supportsUndo = false;
            type = TYPE_OUTPUT;
            mode = PlainTextMode.getMode();
            formatter = new PlainTextFormatter(this);
            lineSeparator = System.getProperty("line.separator");
            readOnly = true;
            setTransient(true);
            setProperty(Property.VERTICAL_RULE, 0);
            setProperty(Property.SHOW_CHANGE_MARKS, false);
            setProperty(Property.SHOW_LINE_NUMBERS, false);
            setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
            setProperty(Property.HIGHLIGHT_BRACKETS, false);
            try {
                lockWrite();
            }
            catch (InterruptedException e) {
                Log.debug(e);
                return;
            }
            try {
                appendLine("");
                renumber();
            }
            finally {
                unlockWrite();
            }
            setInitialized(true);
        }

        public void setShellCommand(AsynchronousShellCommand shellCommand) {
            this.shellCommand = shellCommand;
        }

        public int load() {
            return LOAD_COMPLETED;
        }

        public String getFileNameForDisplay() {
            return title != null ? title : "";
        }

        public boolean isModified() {
            return false;
        }

        public void dispose() {
            if (shellCommand != null)
                shellCommand.interrupt();
        }
    }

    public static void startShellCommand(Editor editor, String command) {
        final File dir = editor.getCurrentDirectory();
        if (dir == null || !dir.isDirectory())
            return;
        ShellCommandOutputBuffer buf = new ShellCommandOutputBuffer();
        buf.setTitle(command);
        editor.makeNext(buf);
        editor.activateInOtherWindow(buf);
        AsynchronousShellCommand shellCommand =
            new AsynchronousShellCommand(command, dir, buf);
        shellCommand.start();
    }
}
