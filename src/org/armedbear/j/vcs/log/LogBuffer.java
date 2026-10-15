/*
 * LogBuffer.java
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
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Property;
import org.armedbear.j.TextLine;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.ViewBuffer;

/**
 * A repository's log: a row per commit, newest first, read a page at a time
 * as the caret nears the end. In the full log, the commit at the caret has
 * its files and the diff of one shown beside it, a moment after the caret
 * stops on it.
 */
public final class LogBuffer extends ViewBuffer {
    private static final int PAGE = 2000;
    // How near the end the caret comes before the next page is read.
    private static final int AHEAD = 200;
    // How long the caret rests on a commit before its details are read.
    static final int FOLLOW_DELAY = 100;

    private final VcsRepository repository;
    private final LogQuery query;
    private final int pageSize;
    private final List<LogCommit> commits = new ArrayList<>();
    private boolean more = true;
    private boolean loading;
    private volatile boolean disposed;
    private int width = 80;
    // The commit to put the caret on when its page is read, as after a refresh.
    private String select;

    // The windows before the log, put back when it closes; null until taken.
    WindowLayout.Node before;
    // Where the details are: at the right, or at the bottom; null until first shown.
    Boolean right;
    LogFilesBuffer files;
    LogDiffBuffer diff;
    private LogCommit shown;
    private final Timer follow;

    private LogBuffer(VcsRepository repository, LogQuery query, int pageSize) {
        super(VcsLogMode.getMode(),
                repository.backend().name() + " log: " + repository.root().getFileName()
                        + (query.path() != null ? " " + query.path() : ""),
                "Reading…");
        this.repository = repository;
        this.query = query;
        this.pageSize = pageSize;
        follow = new Timer(FOLLOW_DELAY, e -> showDetails());
        follow.setRepeats(false);
    }

    /** A new log of repository, reading its first page. */
    public static LogBuffer open(VcsRepository repository, LogQuery query) {
        return open(repository, query, PAGE);
    }

    static LogBuffer open(VcsRepository repository, LogQuery query, int pageSize) {
        final LogBuffer log = new LogBuffer(repository, query, pageSize);
        log.loadMore();
        return log;
    }

    public VcsRepository getRepository() {
        return repository;
    }

    public LogQuery getQuery() {
        return query;
    }

    /** Whether the log has the whole frame, with its details. */
    boolean isFull() {
        return files != null;
    }

    /**
     * Closes the log, once: the windows from before the full log come back,
     * or the quick log's window goes; its panes go with it.
     */
    void close(Frame frame) {
        if (disposed)
            return;
        disposed = true;
        follow.stop();
        if (frame != null) {
            if (isFull() && before != null)
                frame.restoreLayout(before);
            else {
                final Editor ed = LogBuffers.windowOf(this);
                if (ed != null && frame.hasSplit())
                    frame.closeEditor(ed);
            }
        }
        for (Buffer b : new Buffer[] { diff, files, this })
            if (b != null && Editor.getBufferList().contains(b))
                b.kill();
    }

    // Killed from the buffer list or :bd, it closes as q does.
    @Override
    public synchronized void kill() {
        if (!disposed) {
            final Editor current = Editor.currentEditor();
            close(current != null ? current.getFrame() : null);
        } else
            super.kill();
    }

    @Override
    public void dispose() {
        disposed = true;
        follow.stop();
        super.dispose();
    }

    boolean isDisposed() {
        return disposed;
    }

    /** The commits read so far. For tests. */
    List<LogCommit> commits() {
        return commits;
    }

    /** Whether a page is being read. */
    boolean isLoading() {
        return loading;
    }

    // Reading.

    /** Reads the next page in the background, unless one is being read or there is no more. */
    void loadMore() {
        if (loading || !more || disposed)
            return;
        loading = true;
        final int skip = commits.size();
        Background.start("vcs log", () -> {
            List<LogCommit> page;
            try {
                page = repository.backend().readLog(repository, query, skip, pageSize);
            }
            catch (RuntimeException e) {
                Log.error(e);
                page = List.of();
            }
            final List<LogCommit> read = page != null ? page : List.of();
            SwingUtilities.invokeLater(() -> append(read));
        });
    }

    /** Reads it again from the first page, the caret staying on its commit. */
    void refresh() {
        if (loading || disposed)
            return;
        final LogCommit at = commitAt(LogBuffers.windowOf(this));
        select = at != null ? at.id() : null;
        commits.clear();
        more = true;
        loadMore();
    }

    private void append(List<LogCommit> page) {
        loading = false;
        if (disposed)
            return;
        more = page.size() == pageSize;
        final boolean first = commits.isEmpty();
        commits.addAll(page);
        final long now = System.currentTimeMillis() / 1000;
        final List<Line> lines = new ArrayList<>();
        for (LogCommit c : page)
            lines.add(new LogLine(c, LogRow.layout(c, width, now)));
        if (first)
            empty();
        withWriteLock(() -> {
            if (first && lines.isEmpty())
                appendLine(new TextLine(repository.backend().name() + ": no commits"));
            for (Line l : lines)
                appendLine(l);
            renumber();
        });
        invalidate();
        Line to = null;
        if (select != null)
            for (Line l = getFirstLine(); l != null && to == null; l = l.next())
                if (l instanceof LogLine ll && ll.commit().id().equals(select))
                    to = l;
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() != this)
                continue;
            if (first || to != null) {
                final Line dot = to != null ? to : getFirstLine();
                ed.setDot(dot, 0);
                ed.setMark(null);
                ed.moveCaretToDotCol();
                if (first)
                    ed.setTopLine(getFirstLine());
                ed.setUpdateFlag(REFRAME | REPAINT);
            } else
                ed.setUpdateFlag(REPAINT);
            ed.updateDisplay();
        }
        // Looked for in the first page only: found later, it would take the caret from where it went.
        select = null;
        if (first && isFull())
            showDetails();
    }

    // Laying out.

    @Override
    public void windowResized(Editor editor) {
        final int columns = editor.getDisplay().getColumns();
        if (columns <= 0 || columns == width)
            return;
        width = columns;
        final long now = System.currentTimeMillis() / 1000;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof LogLine ll)
                ll.layout(LogRow.layout(ll.commit(), width, now));
        invalidate();
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this) {
                ed.setUpdateFlag(REPAINT);
                ed.updateDisplay();
            }
    }

    /** The width rows are laid out for. For tests. */
    void setWidth(int columns) {
        width = columns;
    }

    // Following the caret.

    @Override
    public void afterCommand(Editor editor) {
        if (editor.getDot() == null)
            return;
        if (more && !loading && editor.getDotLineNumber() >= getLineCount() - AHEAD)
            loadMore();
        if (isFull() && commitAt(editor) != shown)
            follow.restart();
    }

    /** The commit at editor's caret, or null. */
    static LogCommit commitAt(Editor editor) {
        return editor != null && editor.getDot() != null && editor.getDotLine() instanceof LogLine l
                ? l.commit()
                : null;
    }

    /** The commit whose details are shown, or null. */
    LogCommit shown() {
        return shown;
    }

    /** Shows the details of the commit at the caret of the window showing the log now. */
    void showDetails() {
        if (!isFull() || disposed)
            return;
        final LogCommit c = commitAt(LogBuffers.windowOf(this));
        if (c == null || c == shown)
            return;
        shown = c;
        files.show(c);
    }

    /** Forgets the details shown, as when they are rebuilt. */
    void forgetShown() {
        shown = null;
    }
}
