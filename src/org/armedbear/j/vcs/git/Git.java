/*
 * Git.java
 *
 * Copyright (C) 2012 Kevin Krouse
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

package org.armedbear.j.vcs.git;

import java.util.List;
import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.util.ProcessRunner;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.VersionControl;

public class Git extends VersionControl implements Constants {
    public static void git() {
        git("");
    }

    public static void git(String s) {
        if (!checkGitInstalled())
            return;
        List<String> args = Utilities.tokenize(s);
        if (args.size() == 0)
            return;
        String command = args.get(0);
        final Editor editor = Editor.currentEditor();
        editor.setWaitCursor();
        // Append current file name for diff
        final String cmd = parseArgs("git", s, true, command.startsWith("diff"));
        final Buffer parentBuffer = editor.getBuffer();
        commandAsync(cmd, editor.getCurrentDirectory(), output -> {
            gitCompleted(editor, parentBuffer, cmd, output);
        });
    }

    private static void gitCompleted(
        Editor editor,
        Buffer buffer,
        String cmd,
        String output
    ) {
        vcsCompleted(editor, buffer, cmd.startsWith("git diff"), cmd, output, VC_GIT, true);
    }

    public static File findRoot(File dir) {
        while (dir != null) {
            File file = File.getInstance(dir, ".git");
            if (file != null && file.exists())
                return dir;
            dir = dir.getParentFile();
        }
        // Not found.
        return null;
    }

    protected static boolean checkGitInstalled() {
        if (haveGit())
            return true;
        MessageDialog.showMessageDialog(
            "The Git command-line client does not appear to be in your PATH.",
            "Error"
        );
        return false;
    }

    private static int haveGit = -1;

    protected static boolean haveGit() {
        if (haveGit > 0)
            return true;
        if (ProcessRunner.exists("git")) {
            haveGit = 1; // Cache positive result.
            return true;
        }
        return false;
    }
}
