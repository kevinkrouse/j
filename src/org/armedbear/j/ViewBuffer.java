/*
 * ViewBuffer.java
 *
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

import static org.armedbear.j.Constants.LOAD_COMPLETED;
import static org.armedbear.j.Constants.REFRAME;
import static org.armedbear.j.Constants.REPAINT;

import java.util.ArrayList;
import java.util.List;

/**
 * A read-only view built from what was read, as the status buffer, the
 * log's and a comparison's sides: no file and no undo. Its mode's properties
 * keep line numbers, change marks and bracket highlights off. It may be a
 * part of another buffer, its owner, as the log's files and diff are the
 * log's: listed under it, shown in its frame view, skipped going through
 * the buffers, and closed by it, with more, when killed on its own.
 */
public abstract class ViewBuffer extends Buffer {
    // The buffer it is a part of, or null.
    private Buffer owner;

    /** A view in mode, called title, showing placeholder until its lines are read. */
    protected ViewBuffer(Mode mode, String title, String placeholder) {
        supportsUndo = false;
        type = TYPE_OUTPUT;
        this.mode = mode;
        formatter = mode.getFormatter(this);
        readOnly = true;
        this.title = title;
        withWriteLock(() -> {
            appendLine(placeholder);
            renumber();
        });
        setInitialized(true);
        setLoaded(true);
    }

    /** The buffer it is a part of, or null for none. */
    public Buffer getOwner() {
        return owner;
    }

    /** Makes it a part of owner from now on, or of none for null. */
    protected void setOwner(Buffer owner) {
        this.owner = owner;
    }

    @Override
    public boolean isPrimary() {
        return owner == null;
    }

    @Override
    public Buffer getPrimary() {
        return owner;
    }

    @Override
    public FrameView getFrameView() {
        return owner != null ? owner.getFrameView() : super.getFrameView();
    }

    /** Whether its owner closes it, killed on its own, as a part of its view. */
    protected final boolean closedByOwner() {
        return owner instanceof FrameView v && v.partKilled(this);
    }

    @Override
    public synchronized void kill() {
        if (!closedByOwner())
            super.kill();
    }

    @Override
    public int load() {
        return LOAD_COMPLETED;
    }

    @Override
    public String getFileNameForDisplay() {
        return title;
    }

    /** Puts lines in place of what it had, as a view of another's text shows it. */
    public void setLines(List<? extends Line> lines) {
        fill(lines);
    }

    /** Puts lines in place of what it had, leaving the windows to be placed. */
    protected void fill(List<? extends Line> lines) {
        empty();
        withWriteLock(() -> {
            for (Line l : lines)
                appendLine(l);
            if (getFirstLine() == null)
                appendLine("");
            renumber();
        });
        invalidate();
    }

    /** The windows showing it. */
    public List<Editor> windows() {
        final List<Editor> out = new ArrayList<>();
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this)
                out.add(ed);
        return out;
    }

    /** Puts ed's caret on dot and top at its top. */
    protected static void place(Editor ed, Line dot, Line top) {
        ed.setDot(dot, 0);
        ed.setMark(null);
        ed.moveCaretToDotCol();
        ed.setTopLine(top);
        ed.setUpdateFlag(REFRAME | REPAINT);
        ed.updateDisplay();
    }
}
