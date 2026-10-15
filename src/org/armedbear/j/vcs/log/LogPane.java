/*
 * LogPane.java
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

package org.armedbear.j.vcs.log;

import static org.armedbear.j.Constants.*;

import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.ViewBuffer;

/** A pane of the log's details: the commit's files, or a file's diff. */
abstract class LogPane extends ViewBuffer {
    final LogBuffer owner;
    // Each read's number, so that only the last one asked for is shown.
    int generation;

    LogPane(LogBuffer owner, String title) {
        super(VcsLogMode.getMode(), title, "");
        this.owner = owner;
    }

    // Killed on its own, the whole log closes.
    @Override
    public synchronized void kill() {
        if (!owner.isDisposed())
            owner.kill();
        else
            super.kill();
    }

    /**
     * Puts lines in place of what it had, each window showing it with its
     * caret on dot (null for the first line) and the first line at the top.
     */
    void replace(List<? extends Line> lines, Line dot) {
        fill(lines);
        for (Editor ed : windows())
            place(ed, dot != null ? dot : getFirstLine(), getFirstLine());
    }

    /** Puts lines in place of what it had, leaving the windows to be placed. */
    void fill(List<? extends Line> lines) {
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
    List<Editor> windows() {
        final List<Editor> out = new ArrayList<>();
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this)
                out.add(ed);
        return out;
    }

    /** Puts ed's caret on dot and top at its top. */
    static void place(Editor ed, Line dot, Line top) {
        ed.setDot(dot, 0);
        ed.setMark(null);
        ed.moveCaretToDotCol();
        ed.setTopLine(top);
        ed.setUpdateFlag(REFRAME | REPAINT);
        ed.updateDisplay();
    }
}
