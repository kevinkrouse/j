/*
 * ShellCommand.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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

import org.armedbear.j.util.ProcessRunner;

/** Runs a command line in the shell and keeps its output. */
public final class ShellCommand implements Runnable {
    private final String cmdline;
    private final File workingDirectory;
    private final String input;
    private volatile ProcessRunner.Result result = new ProcessRunner.Result(-1, "");

    public ShellCommand(String cmdline) {
        this(cmdline, null, null);
    }

    public ShellCommand(String cmdline, File workingDirectory) {
        this(cmdline, workingDirectory, null);
    }

    public ShellCommand(String cmdline, File workingDirectory, String input) {
        this.cmdline = cmdline;
        this.workingDirectory = workingDirectory;
        this.input = input;
    }

    public final String getCmdLine() {
        return cmdline;
    }

    public final String getOutput() {
        return result.output();
    }

    public final int exitValue() {
        return result.exitValue();
    }

    public void run() {
        if (cmdline != null)
            result = ProcessRunner.shell(cmdline).directory(workingDirectory).input(input).run();
    }

    public static void shellCommand() {
        if (!Platform.isPlatformUnix())
            return;
        final Editor editor = Editor.currentEditor();
        InputDialog d =
            new InputDialog(editor, "Command:", "Shell Command", null);
        d.setHistory(new History("shellCommand.command"));
        editor.centerDialog(d);
        d.setVisible(true);
        String command = d.getInput();
        if (command == null)
            return;
        command = command.trim();
        if (command.length() == 0)
            return;
        AsynchronousShellCommand.startShellCommand(editor, command);
    }

    public static void shellCommand(String command) {
        if (!Platform.isPlatformUnix())
            return;
        AsynchronousShellCommand.startShellCommand(
            Editor.currentEditor(),
            command
        );
    }
}
