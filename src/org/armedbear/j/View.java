/*
 * View.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

import org.armedbear.j.mode.dir.DirectoryTree;

public final class View implements Cloneable {
    Position dot;
    Position mark;
    Selection selection;
    private boolean isColumnSelection;
    Line topLine;
    int pixelsAboveTopLine;
    int shift;
    int caretCol;
    int lineNumber;
    int offs;
    int topLineNumber;
    // The mark's, kept by invalidate; -1 for none.
    int markLineNumber = -1;
    int markOffs;
    long timestamp;

    // An extension's place in a buffer that isn't lines of a file: a mailbox's entries.
    private Object topEntry;
    private Object dotEntry;
    private NavigationComponent sidebarComponent;

    public View() {}

    public View(SessionBufferEntry entry) {
        lineNumber = entry.getDotLineNumber();
        offs = entry.getDotOffset();
    }

    public Position getDot() {
        return dot;
    }

    public int getDotOffset() {
        return offs;
    }

    public void setDot(Position pos) {
        dot = pos != null ? new Position(pos) : null;
    }

    public Position getMark() {
        return mark;
    }

    public void setMark(Position pos) {
        mark = pos != null ? new Position(pos) : null;
    }

    public boolean isColumnSelection() {
        return isColumnSelection;
    }

    public void setColumnSelection(boolean b) {
        isColumnSelection = b;
    }

    public int getDotLineNumber() {
        return lineNumber;
    }

    public Line getTopLine() {
        return topLine;
    }

    public int getTopLineNumber() {
        return topLineNumber;
    }

    public int getPixelsAboveTopLine() {
        return pixelsAboveTopLine;
    }

    public Object getTopEntry() {
        return topEntry;
    }

    public void setTopEntry(Object entry) {
        topEntry = entry;
    }

    public Object getDotEntry() {
        return dotEntry;
    }

    public void setDotEntry(Object entry) {
        dotEntry = entry;
    }

    public Selection getSelection() {
        return selection;
    }

    public int getShift() {
        return shift;
    }

    public int getCaretCol() {
        return caretCol;
    }

    public void setCaretCol(int col) {
        caretCol = col;
    }

    public NavigationComponent getSidebarComponent() {
        return sidebarComponent;
    }

    public void setSidebarComponent(NavigationComponent c) {
        sidebarComponent = c;
    }

    /** Its lines dropped for their numbers, as when the buffer's lines are replaced; resolve has them back. */
    public void invalidate() {
        if (dot != null) {
            lineNumber = dot.lineNumber();
            offs = dot.getOffset();
        }
        if (mark != null) {
            markLineNumber = mark.lineNumber();
            markOffs = mark.getOffset();
        }
        dot = null;
        mark = null;
        selection = null;
        isColumnSelection = false;
        if (topLine != null)
            topLineNumber = topLine.lineNumber();
        topLine = null;
        if (!(sidebarComponent instanceof DirectoryTree))
            sidebarComponent = null;
    }

    /**
     * The caret, mark and top line invalidated had again from their numbers
     * in buffer: past its end at its last line, an offset past a line's end
     * at that end, and a hidden top line at the next one shown, without the
     * pixels it was scrolled by.
     */
    public void resolve(Buffer buffer) {
        if (dot == null)
            dot = at(buffer, lineNumber, offs);
        if (mark == null && markLineNumber >= 0)
            mark = at(buffer, markLineNumber, markOffs);
        if (topLine == null) {
            final Line line = line(buffer, topLineNumber);
            topLine = line == null ? null : line.shown();
            if (topLine == null || topLine.lineNumber() != topLineNumber)
                pixelsAboveTopLine = 0;
        }
    }

    private static Line line(Buffer buffer, int n) {
        final Line line = buffer.getLine(n);
        return line != null ? line : buffer.getLastLine();
    }

    private static Position at(Buffer buffer, int n, int offset) {
        final Line line = line(buffer, n);
        return line == null ? null : new Position(line, Math.max(0, Math.min(offset, line.length())));
    }

    @Override
    protected Object clone() {
        View view = new View();
        if (dot != null)
            view.dot = new Position(dot);
        if (mark != null)
            view.mark = new Position(mark);
        view.selection = selection;
        view.isColumnSelection = isColumnSelection;
        view.topLine = topLine;
        view.shift = shift;
        view.caretCol = caretCol;
        view.lineNumber = lineNumber;
        view.offs = offs;
        view.topLineNumber = topLineNumber;
        view.markLineNumber = markLineNumber;
        view.markOffs = markOffs;
        view.pixelsAboveTopLine = pixelsAboveTopLine;
        return view;
    }
}
