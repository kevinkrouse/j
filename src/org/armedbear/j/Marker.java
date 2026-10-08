/*
 * Marker.java
 *
 * Copyright (C) 1998-2007 Peter Graves <peter@armedbear.org>
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.mode.web.WebBuffer;

public final class Marker {
    private Buffer buffer;
    private Position pos;
    private final File file;
    private int lineNumber;
    private int offset;
    // A page shown in a WebBuffer, as help is: gone back to there, and not
    // opened as the file's HTML.
    private final boolean web;
    // Whether it was help or the like, for a buffer made again.
    private final boolean wasTransient;

    public Marker(Buffer buffer, Position pos) {
        this.buffer = buffer;
        this.pos = new Position(pos);
        file = buffer.getFile();
        lineNumber = pos.lineNumber();
        offset = pos.getOffset();
        web = buffer instanceof WebBuffer;
        wasTransient = buffer.isTransient();
    }

    public Buffer getBuffer() {
        return buffer;
    }

    /** The file it's in, if it's in one. */
    public File getFile() {
        return file;
    }

    /** Its line, zero-based, even after its buffer is closed. */
    public int getLineNumber() {
        return pos != null ? pos.lineNumber() : lineNumber;
    }

    // Returns an alias, not a copy.
    public Position getPosition() {
        return pos;
    }

    public void setPosition(Position pos) {
        this.pos = new Position(pos);
    }

    public Line getLine() {
        return pos != null ? pos.getLine() : null;
    }

    public void invalidate() {
        if (pos != null) {
            lineNumber = pos.lineNumber();
            offset = pos.getOffset();
        }
        pos = null;
        buffer = null;
    }

    /**
     * Goes there, in this window or, between a transient buffer and any
     * other, the panel's or the one behind it.
     *
     * @return the window it went to
     */
    public Editor gotoMarker(Editor editor) {
        if (web)
            return gotoWebMarker(editor);
        if (buffer == editor.getBuffer() || (file != null && file.equals(editor.getBuffer().getFile()))) {
            // Marker is in current buffer.
            editor.beginMotion();
            editor.updateDotLine();
            if (pos != null && editor.getBuffer().contains(pos.getLine())) {
                editor.getDot().moveTo(pos);
            } else {
                editor.gotoline(lineNumber);
                editor.getDot().setOffset(offset);
            }
            if (editor.getDotOffset() > editor.getDotLine().length())
                editor.getDot().setOffset(editor.getDotLine().length());
            editor.moveCaretToDotCol();
            editor.updateDotLine();
            editor.setUpdateFlag(REFRAME);
        } else {
            // Marker is not in current buffer.
            BufferList buffer_list = Editor.getBufferList();
            Buffer buf = null;
            if (file != null)
                buf = buffer_list.findBuffer(file);
            else if (buffer_list.contains(buffer))
                buf = buffer;
            if (buf != null) {
                editor = editor.show(buf);
                editor.addUndo(SimpleEdit.MOVE);
                editor.updateDotLine();
                if (pos != null && buf.contains(pos.getLine())) {
                    editor.getDot().moveTo(pos);
                } else {
                    editor.gotoline(lineNumber);
                    editor.getDot().setOffset(offset);
                }
                if (editor.getDotOffset() > editor.getDotLine().length())
                    editor.getDot().setOffset(editor.getDotLine().length());
                editor.moveCaretToDotCol();
                editor.updateDotLine();
            } else if (file != null) {
                buf = Buffer.createBuffer(file);
                editor = editor.show(buf);
                editor.gotoline(lineNumber);
                editor.getDot().setOffset(offset);
                if (editor.getDotOffset() > editor.getDotLine().length())
                    editor.getDot().setOffset(editor.getDotLine().length());
                editor.moveCaretToDotCol();
            } else
                return editor;
        }
        pos = new Position(editor.getDot());
        buffer = editor.getBuffer();
        return editor;
    }

    // The page again in its WebBuffer, or in a new one if that has gone.
    private Editor gotoWebMarker(Editor editor) {
        WebBuffer wb = buffer instanceof WebBuffer w && Editor.getBufferList().contains(w) ? w : null;
        if (wb == null && file != null) {
            for (Buffer b : Editor.getBufferList()) {
                if (b instanceof WebBuffer w && file.equals(w.getFile())) {
                    wb = w;
                    break;
                }
            }
        }
        if (wb == null) {
            if (file == null)
                return editor;
            wb = WebBuffer.createWebBuffer(file, null, null);
            wb.setTransient(wasTransient);
        }
        final Editor ed = editor.show(wb);
        final boolean samePage = file == null || file.equals(wb.getFile());
        if (!samePage) {
            if (ed.getDot() != null)
                wb.saveHistory(wb.getFile(), wb.getAbsoluteOffset(ed.getDot()), wb.getContentType());
            wb.go(file, 0, null);
        }
        if (ed.getDot() == null)
            return ed;
        ed.beginMotion();
        ed.updateDotLine();
        if (samePage && pos != null && wb.contains(pos.getLine()))
            ed.getDot().moveTo(pos);
        else {
            ed.gotoline(lineNumber);
            ed.getDot().setOffset(offset);
        }
        if (ed.getDotOffset() > ed.getDotLine().length())
            ed.getDot().setOffset(ed.getDotLine().length());
        ed.moveCaretToDotCol();
        ed.updateDotLine();
        ed.setUpdateFlag(REFRAME);
        ed.updateDisplay();
        pos = new Position(ed.getDot());
        buffer = wb;
        return ed;
    }

    public static void selectToMarker() {
        selectToMarker(InputDialog.showInputDialog(Editor.currentEditor(), "Marker:", "Select To Marker"));
    }

    public static void selectToTemporaryMarker() {
        selectToMarker("10");
    }

    public static void selectToMarker(String s) {
        if (s == null)
            return;
        s = s.trim();
        if (s.length() == 0)
            return;
        final Editor editor = Editor.currentEditor();
        Marker m = null;
        try {
            final int index = Integer.parseInt(s);
            final Marker[] bookmarks = Editor.getBookmarks();
            if (index >= 0 && index < bookmarks.length)
                m = bookmarks[index];
        }
        catch (NumberFormatException ignored) {}
        if (m == null) {
            MessageDialog.showMessageDialog(editor, "No such marker", "Select To Marker");
            return;
        }
        m.selectToMarker(editor);
    }

    private void selectToMarker(Editor editor) {
        if (buffer == editor.getBuffer() || (file != null && file.equals(editor.getBuffer().getFile()))) {
            // Marker is in current buffer.
            editor.beginMotion();
            editor.setMarkAtDot();
            editor.updateDotLine();
            if (pos != null && editor.getBuffer().contains(pos.getLine())) {
                editor.getDot().moveTo(pos);
            } else {
                editor.gotoline(lineNumber);
                editor.getDot().setOffset(offset);
            }
            if (editor.getDotOffset() > editor.getDotLine().length())
                editor.getDot().setOffset(editor.getDotLine().length());
            editor.moveCaretToDotCol();
            editor.updateDotLine();
            editor.setUpdateFlag(REFRAME | REPAINT);
        } else {
            // Marker is not in current buffer.
            MessageDialog.showMessageDialog(editor, "Marker is not in this buffer.", "Select To Marker");
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object)
            return true;
        if (object instanceof Marker m) {
            if (buffer != null && buffer == m.buffer)
                if (pos != null && pos.equals(m.pos))
                    return true;
            if (file != null && file.equals(m.file))
                if (lineNumber == m.lineNumber && offset == m.offset)
                    return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        // equals matches on either buffer and position or file and offset;
        // no field is common to both.
        return 0;
    }

    public static void invalidateAllMarkers() {
        List<Marker> markers = getAllMarkers();
        for (int i = markers.size(); i-- > 0;) {
            Marker m = markers.get(i);
            if (m != null)
                m.invalidate();
        }
    }

    public static void invalidateMarkers(Buffer buf) {
        List<Marker> markers = getAllMarkers();
        for (int i = markers.size(); i-- > 0;) {
            Marker m = markers.get(i);
            if (m != null && m.getBuffer() == buf)
                m.invalidate();
        }
    }

    public static List<Marker> getAllMarkers() {
        Marker[] bookmarks = Editor.getBookmarks();
        List<Marker> jumps = JumpList.getEntries();
        ArrayList<Marker> list = new ArrayList<>(bookmarks.length + jumps.size());
        for (int i = bookmarks.length; i-- > 0;) {
            Marker m = bookmarks[i];
            if (m != null)
                list.add(m);
        }
        list.addAll(jumps);
        list.addAll(ChangeList.getAllEntries());
        return list;
    }
}
