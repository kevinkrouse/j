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

import javax.swing.SwingUtilities;
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

    /** Finds a file in the project, open buffers and recent files, from the location bar. */
    public static void findFileInProject(Editor editor) {
        final LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return;
        locationBar.setLabelText(LocationBar.PROMPT_FIND_FILE);
        HistoryTextField textField = locationBar.getTextField();
        FindFileTextFieldHandler handler = new FindFileTextFieldHandler(editor, textField);
        textField.setHandler(handler);
        textField.setHistory(new History("findFileInProject.input", 30));
        textField.setText("");
        editor.setFocusToTextField();
        // After the focus has moved, so the list shows.
        SwingUtilities.invokeLater(handler::start);
    }

    /** Finds a command by name or summary, from the location bar, and runs it. */
    public static void findAction(Editor editor) {
        final LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return;
        locationBar.setLabelText(LocationBar.PROMPT_ACTION);
        editor.status("");
        HistoryTextField textField = locationBar.getTextField();
        ActionTextFieldHandler handler = new ActionTextFieldHandler(editor, textField);
        textField.setHandler(handler);
        textField.setHistory(new History("findAction.input", 30));
        textField.setText("");
        editor.setFocusToTextField();
        SwingUtilities.invokeLater(handler::start);
    }

    /** Opens file, in the other window if otherWindow, at line if it's positive. */
    static void open(Editor editor, File file, boolean otherWindow, int line) {
        Buffer buf = Editor.getBuffer(file);
        if (buf == null) {
            editor.status("Unable to open " + file.canonicalPath());
            return;
        }
        final Frame frame = editor.getFrame();
        Editor target = editor;
        if (otherWindow) {
            target = editor.activateInOtherWindow(buf);
        } else if (buf != editor.getBuffer()) {
            editor.makeNext(buf);
            editor.switchToBuffer(buf);
            // Switching to or from a paired buffer can close the editor.
            if (!frame.contains(editor))
                target = frame.getCurrentEditor();
        }
        if (target == null)
            return;
        Editor.setCurrentEditor(target);
        if (line > 0 && target.getBuffer() == buf && target.getDot() != null) {
            target.beginMotion();
            target.gotoline(line - 1);
            target.moveCaretToDotCol();
        }
        target.ensureActive();
        target.setFocusToDisplay();
        target.updateLocation();
        target.updateDisplay();
    }
}
