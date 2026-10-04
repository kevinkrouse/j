/*
 * DiffMode.java
 *
 * Copyright (C) 1998-2006 Peter Graves
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

package org.armedbear.j.mode.diff;

import static org.armedbear.j.Constants.*;

import java.awt.AWTEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.ConfirmDialog;
import org.armedbear.j.Debug;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;
import org.armedbear.j.ShellCommand;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsBackends;

public final class DiffMode extends AbstractMode implements Mode {
    private static final DiffMode mode = new DiffMode();

    private DiffMode() {
        super(DIFF_MODE, DIFF_MODE_NAME);
    }

    public static final DiffMode getMode() {
        return mode;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new DiffFormatter(buffer);
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "diffGotoFile");
        km.mapKey(KeyEvent.VK_G, CTRL_MASK | SHIFT_MASK, "diffGotoFile");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "diffGotoFile");
        km.mapKey(VK_MOUSE_2, 0, "diffGotoFile");
        km.mapKey('q', "tempBufferQuit");
    }

    public static void diff() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        File file = buffer.getFile();
        if (file.isLocal() && file.isFile()) {
            File patchFile = buffer.getPatchFile();
            if (patchFile != null && patchFile.isFile()) {
                boolean save = false;
                if (buffer.isModified()) {
                    int response =
                        ConfirmDialog.showConfirmDialogWithCancelButton(
                            editor,
                            CHECK_SAVE_PROMPT,
                            "diff"
                        );
                    switch (response) {
                        case RESPONSE_YES:
                            save = true;
                            break;
                        case RESPONSE_NO:
                            break;
                        case RESPONSE_CANCEL:
                            return;
                    }
                    editor.repaintNow();
                }
                editor.setWaitCursor();
                if (!save || buffer.save()) {
                    StringBuilder sb = new StringBuilder("-u \"");
                    sb.append(patchFile.canonicalPath());
                    sb.append("\" \"");
                    sb.append(file.canonicalPath());
                    sb.append('"');
                    diff(sb.toString());
                }
                return;
            }
        }
        diff("--help");
    }

    public static void diff(String args) {
        final Editor editor = Editor.currentEditor();
        final Buffer parentBuffer = editor.getBuffer();
        String defaultOptions = "-u ";
        List<String> argList = Utilities.tokenize(args);
        for (int i = 0; i < argList.size(); i++) {
            String arg = argList.get(i);
            if (arg.equals("%")) {
                File file = parentBuffer.getFile();
                if (file == null) {
                    MessageDialog.showMessageDialog(
                        "There is no file associated with the current buffer.",
                        "Error"
                    );
                    return;
                }
                if (file.isRemote()) {
                    MessageDialog.showMessageDialog(
                        file.netPath() + " is a remote file.",
                        "Error"
                    );
                    return;
                }
                if (file.isDirectory()) {
                    MessageDialog.showMessageDialog(
                        file.canonicalPath() + " is a directory.",
                        "Error"
                    );
                    return;
                }
                // OK.
                argList.set(i, file.canonicalPath());
            } else if (arg.startsWith("-")) {
                defaultOptions = null;
            } else {
                File file = File.getInstance(editor.getCurrentDirectory(), arg);
                if (file.exists())
                    argList.set(i, file.canonicalPath());
            }
        }
        editor.setWaitCursor();
        StringBuilder sb = new StringBuilder("diff ");
        if (defaultOptions != null)
            sb.append(defaultOptions);
        for (int i = 0; i < argList.size(); i++) {
            // A file found above is a path; anything else is the user's word.
            String arg = argList.get(i);
            File file = File.getInstance(arg);
            sb.append(
                file != null && file.isLocal() && file.exists()
                    ? Utilities.maybeQuote(arg)
                    : Utilities.quoteUserWord(arg)
            );
            sb.append(' ');
        }
        String cmdline = sb.toString().trim();
        ShellCommand shellCommand = new ShellCommand(cmdline);
        shellCommand.run();
        String output = shellCommand.getOutput();
        if (output.length() == 0)
            MessageDialog.showMessageDialog(editor, "No changes", "diff");
        else {
            DiffOutputBuffer buf = new DiffOutputBuffer(parentBuffer, output, 0);
            buf.setTitle(cmdline);
            editor.makeNext(buf);
            editor.activateInOtherWindow(buf);
            editor.setDefaultCursor();
        }
    }

    public static void gotoFile() {
        final Editor editor = Editor.currentEditor();
        if (editor.getDot() == null)
            return;
        final Buffer buffer = editor.getBuffer();
        if (!(buffer instanceof DiffOutputBuffer diffOutputBuffer))
            return;

        // If this method is invoked via a mouse event mapping, move dot to
        // location of mouse click first.
        AWTEvent e = editor.getDispatcher().getLastEvent();
        if (e instanceof MouseEvent mouseEvent)
            editor.mouseMoveDotToPoint(mouseEvent);

        VcsBackend backend = VcsBackends.get(diffOutputBuffer.getVCType());
        if (backend == null || !backend.gotoDiffSource(editor, diffOutputBuffer))
            localGotoFile(editor, diffOutputBuffer);
    }

    /**
     * Goes to the source of the unified diff line at the caret.
     *
     * @param filename the file a line names, or null if it names none
     * @param root maps the buffer's directory to the one file names are relative to
     */
    public static void gotoUnifiedDiffSource(
        Editor editor,
        DiffOutputBuffer diffOutputBuffer,
        Function<String, String> filename,
        UnaryOperator<File> root
    ) {
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();

        // Walk up the diff looking for either a filename or a diff hunk line number
        int lineNumber;
        int count = -1;
        Line line = dotLine;
        while (true) {
            if (line == null)
                return;
            String lineText = line.getText();
            String name = filename.apply(lineText);
            if (name != null) {
                // The caret was in the hunk header: go to the file without
                // moving its dot.
                gotoLocation(editor, root.apply(diffOutputBuffer.getDirectory()), name, -1, -1);
                return;
            } else if (lineText.startsWith("@@")) {
                // Found the start of a diff hunk.  Grab the line number.
                lineNumber = parseLineNumber(line);
                break;
            }

            if (!lineText.startsWith("-"))
                ++count;
            line = line.previous();
        }

        // Our line numbers are zero-based.
        if (--lineNumber < 0)
            return;
        if (count > 0)
            lineNumber += count;
        Buffer parentBuffer = diffOutputBuffer.getParentBuffer();
        File dir;
        if (parentBuffer != null)
            dir = parentBuffer.getCurrentDirectory();
        else
            dir = diffOutputBuffer.getDirectory();

        // Continue walking up the diff looking for the filename.
        for (line = line.previous(); line != null; line = line.previous()) {
            String name = filename.apply(line.getText());
            if (name != null) {
                gotoLocation(editor, root.apply(dir), name, lineNumber, dotOffset);
                return;
            }
        }
    }

    private static void localGotoFile(
        Editor editor,
        DiffOutputBuffer diffOutputBuffer
    ) {
        final Line dotLine = editor.getDotLine();
        String filename1 = null;
        String filename2 = null;
        for (Line line = dotLine; line != null; line = line.previous()) {
            String text = line.getText();
            if (text.startsWith("+++ ")) {
                filename2 = extractFilename(text);
            } else if (text.startsWith("--- ")) {
                filename1 = extractFilename(text);
                if (filename2 == null) {
                    line = line.next();
                    if (line != null)
                        filename2 = extractFilename(line.getText());
                }
                break;
            }
        }
        final String text = dotLine.getText();
        if (text.startsWith("---")) {
            Buffer buf = Editor.getBuffer(File.getInstance(filename1));
            if (buf != null) {
                editor.makeNext(buf);
                editor.activateInOtherWindow(buf);
            }
            return;
        }
        if (text.startsWith("+++")) {
            Buffer buf = Editor.getBuffer(File.getInstance(filename2));
            if (buf != null) {
                editor.makeNext(buf);
                editor.activateInOtherWindow(buf);
            }
            return;
        }
        int oldLineNumber = -1;
        int newLineNumber = -1;
        int oldLines = 0;
        int newLines = 0;
        int unchangedLines = 0;
        Line line = dotLine;
        if (line.getText().startsWith("@@")) {
            oldLineNumber = parseLineNumber(line, '-');
            newLineNumber = parseLineNumber(line, '+');
        } else {
            line = line.previous();
            while (line != null && !line.getText().startsWith("@@")) {
                if (line.getText().startsWith("-"))
                    ++oldLines;
                else if (line.getText().startsWith("+"))
                    ++newLines;
                else
                    ++unchangedLines;
                line = line.previous();
            }
            if (line == null)
                return;
            Debug.assertTrue(line.getText().startsWith("@@"));
            oldLineNumber = parseLineNumber(line, '-');
            newLineNumber = parseLineNumber(line, '+');
        }
        // Our line numbers are zero-based.
        --oldLineNumber;
        --newLineNumber;
        String filename = filename2;
        if (text.startsWith("-")) {
            oldLineNumber += unchangedLines + oldLines;
            newLineNumber += unchangedLines;
            filename = filename1;
        } else if (text.startsWith("+")) {
            oldLineNumber += unchangedLines;
            newLineNumber += unchangedLines + newLines;
            filename = filename2;
        } else {
            // Context line.
            oldLineNumber = oldLineNumber + unchangedLines + oldLines;
            newLineNumber = newLineNumber + unchangedLines + newLines;
            File parentFile = diffOutputBuffer.getParentBuffer().getFile();
            if (parentFile != null) {
                String cp = parentFile.canonicalPath();
                if (cp != null) {
                    if (cp.equals(filename1))
                        filename = filename1;
                }
            }
        }
        File dir = null;
        Buffer parentBuffer = diffOutputBuffer.getParentBuffer();
        if (parentBuffer != null)
            dir = parentBuffer.getCurrentDirectory();
        final File file;
        if (dir != null)
            file = File.getInstance(dir, filename);
        else
            file = File.getInstance(filename);
        if (file != null && file.isFile()) {
            Buffer buf = Editor.getBuffer(file);
            if (buf != null) {
                int lineNumber =
                    (filename == filename1) ? oldLineNumber : newLineNumber;
                final int offset = editor.getDotOffset();
                gotoLocation(
                    editor,
                    buf,
                    lineNumber,
                    offset > 0 ? offset - 1 : 0
                );
            }
        }
    }

    private static String extractFilename(String s) {
        if (s.startsWith("+++ ") || s.startsWith("--- "))
            s = s.substring(4);
        int index = s.indexOf('\t');
        return index >= 0 ? s.substring(0, index) : s;
    }

    private static void gotoLocation(
        Editor editor,
        File dir,
        String filename,
        int lineNumber,
        int dotOffset
    ) {
        File file = File.getInstance(dir, filename);
        if (file != null && file.isFile()) {
            Buffer buf = Editor.getBuffer(file);
            if (buf != null) {
                gotoLocation(
                    editor,
                    buf,
                    lineNumber,
                    dotOffset > 0 ? dotOffset - 1 : 0
                );
            }
        }
    }

    /** Shows buf in the other window at lineNumber (zero-based; -1 leaves its dot). */
    public static void gotoLocation(
        Editor editor,
        Buffer buf,
        int lineNumber,
        int offset
    ) {
        if (buf != null) {
            editor.makeNext(buf);
            Editor ed = editor.activateInOtherWindow(buf);
            if (lineNumber > -1) {
                Position pos = buf.findOriginal(lineNumber, offset);
                ed.moveDotTo(pos);
            }
            ed.setUpdateFlag(REFRAME);
            ed.updateDisplay();
        }
    }

    /** The number after the '+' in a hunk header. */
    public static int parseLineNumber(Line line) {
        return parseLineNumber(line, '+');
    }

    private static int parseLineNumber(Line line, char c) {
        String s = line.getText();
        int index = s.indexOf(c);
        if (index < 0)
            return 0;
        try {
            return Utilities.parseInt(s.substring(index + 1));
        }
        catch (NumberFormatException e) {
            Log.error(e);
            return 0;
        }
    }
}
