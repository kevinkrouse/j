/*
 * GotoFile.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.mode.java.JavaSource;
import java.lang.StringBuilder;
import org.armedbear.j.util.Utilities;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.awt.AWTEvent;
import java.awt.event.MouseEvent;

public final class GotoFile implements Constants
{
    public static void gotoFile()
    {
        final Editor editor = Editor.currentEditor();

        // If this method is invoked via a mouse event mapping, move dot to
        // location of mouse click first.
        AWTEvent event = editor.getDispatcher().getLastEvent();
        if (event instanceof MouseEvent)
            editor.mouseMoveDotToPoint((MouseEvent)event);

        String filename = gotoFileGetFileName(editor);
        if (filename == null)
            return;
        int lineNumber = -1;

        // See if there's a line number at the end of the filename.
        int index = filename.lastIndexOf(" line ");
        if (index >= 0) {
            // "test.pl line 3"
            try {
                lineNumber = Integer.parseInt(filename.substring(index+6)) - 1;
                // Shorten filename to exclude line number.
                filename = filename.substring(0, index);
            }
            catch (NumberFormatException e) {
                // Not a valid number.
            }
        } else {
            index = filename.lastIndexOf(':');
            if (index >= 0) {
                // "Position.java:140"
                try {
                    lineNumber =
                        Integer.parseInt(filename.substring(index+1)) - 1;
                    // Shorten filename to exclude line number.
                    filename = filename.substring(0, index);
                }
                catch (NumberFormatException e) {
                    // Not a valid number.
                }
            }
        }

        boolean tryCurrentDirectory = true;

        if (filename.length() >= 2 &&
            filename.charAt(0) == '<' &&
            filename.charAt(filename.length()-1) == '>') {
            // We'll only get the angle brackets if we're in C or C++ mode.
            // Strip the angle brackets and don't look for the file in the
            // current directory.
            filename = filename.substring(1, filename.length()-1);
            tryCurrentDirectory = false;
        }

        File file = null;

        if (Utilities.isFilenameAbsolute(filename)) {
            file = File.getInstance(editor.getCurrentDirectory(), filename);
        } else {
            // The filename is not absolute.
            if (tryCurrentDirectory)
                // Try current directory first.
                file = File.getInstance(editor.getCurrentDirectory(),
                    filename);

            // Try source and include paths if applicable.
            if (file == null || (file.isLocal() && !file.exists()))
                file = Utilities.findFile(editor, filename);
        }

        if (file != null) {
            Buffer buf = editor.getBuffer(file);
            if (buf != null) {
                final Frame frame = editor.getFrame();
                editor.makeNext(buf);
                editor.switchToBuffer(buf);
                // Switching buffers might have closed the original editor.
                Editor ed =
                    frame.contains(editor) ? editor : frame.getCurrentEditor();
                if (ed.getBuffer() == buf) {
                    if (lineNumber >= 0) {
                        if (ed.getDot() != null) {
                            if (ed.getDotLineNumber() != lineNumber) {
                                ed.addUndo(SimpleEdit.MOVE);
                                ed.unmark();
                                ed.gotoline(lineNumber);
                                ed.moveCaretToDotCol();
                            }
                        }
                    }
                    ed.updateDisplay();
                }
            }
        }
    }

    private static String gotoFileGetFileName(Editor editor)
    {
        if (editor.getDot() == null)
            return null;
        final Line dotLine = editor.getDotLine();
        final int dotOffset = editor.getDotOffset();
        if (editor.getMark() != null && editor.getMarkLine() == dotLine) {
            // Use selection.
            return new Region(editor).toString();
        }
        final int modeId = editor.getModeId();
        if (modeId == HTML_MODE) {
            String href = BrowseFile.getHref(dotLine.getText(), dotOffset);
            if (href != null)
                return href;
        } else if (modeId == JAVA_MODE) {
            String fileName =
                getFileNameFromImport(editor.getBuffer(), dotLine.getText());
            if (fileName != null)
                return fileName;
        } else if ((modeId == C_MODE || modeId == CPP_MODE)) {
            String fileName = getFileNameFromInclude(dotLine.getText());
            if (fileName != null)
                return fileName;
        } else if (editor.getBuffer().getType() == Buffer.TYPE_SHELL) {
            String s = dotLine.getText().trim();
            Matcher matcher = DirectoryBuffer.getNativeMoveToFilenameRegExp().matcher(s);
            if (matcher.find())
                return s.substring(matcher.end());
        }
        return editor.getFilenameAtDot();
    }

    private static String getFileNameFromImport(Buffer buffer, String s)
    {
        if (s.indexOf('*') >= 0)
            return null;
        s = s.trim();
        if (!s.startsWith("import"))
            return null;
        s = s.substring(6);
        if (s.length() == 0)
            return null;
        if (s.charAt(0) != ' ' && s.charAt(0) != '\t')
            return null;
        s = s.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == ';')
                break;
            sb.append(c);
        }
        File file = JavaSource.findSource(buffer, sb.toString(), true);
        return file != null ? file.canonicalPath() : null;
    }

    private static final Pattern includeRE =
        Pattern.compile("[ \t]*#[ \t]*include[ \t]");

    private static final String getFileNameFromInclude(String s)
    {
        Matcher matcher = includeRE.matcher(s);
        if (!matcher.find())
            return null;
        s = s.substring(matcher.end()).trim();
        // Need at least one char plus quotes or angle brackets.
        if (s.length() < 3)
            return null;
        char c = s.charAt(0);
        if (c == '"') {
            int index = s.indexOf('"', 1);
            if (index >= 0)
                return s.substring(1, index);
            return null;
        }
        if (c == '<') {
            int index = s.indexOf('>', 1);
            if (index >= 0)
                return s.substring(0, index+1); // Include angle brackets.
        }
        return null;
    }
}
