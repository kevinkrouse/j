/*
 * LogLine.java
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

import org.armedbear.j.TextLine;

/** A commit's row in the log, its text laid out for the window's width. */
public final class LogLine extends TextLine {
    private final LogCommit commit;
    private final LogGraph.Row graph;
    private LogRow row;

    LogLine(LogCommit commit, LogGraph.Row graph, LogRow row) {
        super(row.text());
        this.commit = commit;
        this.graph = graph;
        this.row = row;
    }

    /** What its row of the graph draws. */
    LogGraph.Row graph() {
        return graph;
    }

    public LogCommit commit() {
        return commit;
    }

    public LogRow row() {
        return row;
    }

    /** Laid out again, as for another width. */
    void layout(LogRow row) {
        this.row = row;
        setText(row.text());
        // Not an edit: no change mark.
        unmodified();
    }
}
