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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Property;
import org.armedbear.j.Sidebar;
import org.armedbear.j.TextLine;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.ViewBuffer;

/**
 * A repository's log, or several's together: a row per commit, newest
 * first, read a page at a time as the caret nears the end. In the full
 * log, the commit at the caret has its files and the diff of one shown
 * beside it, a moment after the caret stops on it.
 */
public final class LogBuffer extends ViewBuffer implements FrameView {
    private static final int PAGE = 2000;
    // How near the end the caret comes before the next page is read.
    private static final int AHEAD = 200;
    // How long the caret rests on a commit before its details are read.
    static final int FOLLOW_DELAY = 100;

    private final LogScope scope;
    private LogQuery query;
    private final int pageSize;
    // Each repository's pages, listed as one, new for each read from the
    // first page, so a page read for one before it is dropped; the commits
    // listed.
    private LogMerge merge;
    private final List<LogCommit> commits = new ArrayList<>();
    // Until the first rows of a read from the first page, the old rows stay.
    private boolean fresh = true;
    private volatile boolean disposed;
    private int width = 80;
    // How the rows' dates are written: their age, or as ls's --time-style.
    private String timeStyle =
            TimeStyle.valid(Editor.preferences().getStringProperty(Property.VCS_LOG_TIME_STYLE), TimeStyle.AGE);
    // The lanes of the commits read, and the most any row spans.
    private LogGraph graph = new LogGraph();
    private int graphWidth;
    private final LogGraphColumn graphColumn = new LogGraphColumn(this);
    // The commit to put the caret on when its page is read, as after a refresh.
    private LogCommit select;

    // The windows before the log took the frame, put back when it is left
    // or closes; null when it isn't shown.
    private WindowLayout.Node before;
    // Where the details are: at the right, or at the bottom; null until first shown.
    Boolean right;
    LogFilesBuffer files;
    LogDiffBuffer diff;
    private LogCommit shown;
    private final Timer follow;

    private LogBuffer(LogScope scope, LogQuery query, int pageSize) {
        super(VcsLogMode.getMode(), scope.title(query), "Reading…");
        this.scope = scope;
        this.query = query;
        this.pageSize = pageSize;
        merge = newMerge();
        follow = new Timer(FOLLOW_DELAY, e -> showDetails());
        follow.setRepeats(false);
    }

    /** A new log of scope's repositories, reading their first pages. */
    public static LogBuffer open(LogScope scope, LogQuery query) {
        return open(scope, query, PAGE);
    }

    static LogBuffer open(LogScope scope, LogQuery query, int pageSize) {
        final LogBuffer log = new LogBuffer(scope, query, pageSize);
        log.loadMore();
        LogNames.read(scope, false);
        return log;
    }

    static LogBuffer open(VcsRepository repository, LogQuery query, int pageSize) {
        return open(LogScope.of(repository), query, pageSize);
    }

    public LogScope getScope() {
        return scope;
    }

    /** The color of the lane of the first commit read that branch, not a tag, is at; -1 for none. */
    int laneColorOf(String branch) {
        return branchLanes.getOrDefault(branch, -1);
    }

    // Each branch's lane's color, as the rows are read.
    private final Map<String, Integer> branchLanes = new HashMap<>();

    /** The color of the lane c is in, its row read; -1 for none, as with no graph. */
    int laneColorOf(LogCommit c) {
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof LogLine ll && ll.commit().isSame(c))
                return ll.graph() == null ? -1 : ll.graph().color();
        return -1;
    }

    /** The repository c is in. */
    VcsRepository repositoryOf(LogCommit c) {
        return c.repository() != null ? c.repository() : scope.first();
    }

    // The repositories query reads, each a source.
    private LogMerge newMerge() {
        final List<VcsRepository> read = new ArrayList<>();
        for (VcsRepository r : scope.repositories())
            if (scope.queryFor(r, query) != null)
                read.add(r);
        return new LogMerge(read, pageSize);
    }

    public LogQuery getQuery() {
        return query;
    }

    /** Shows the commits query takes, read again from the first page. */
    void setQuery(LogQuery query) {
        if (query.equals(this.query))
            return;
        // With a branch, tags change nothing read: kept for when it is unset.
        final boolean same = !query.revisions().isEmpty() && query.withTags(this.query.tags()).equals(this.query);
        this.query = query;
        if (same)
            return;
        title = scope.title(query);
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this && ed.getFrame() != null) {
                ed.updateLocation();
                ed.getFrame().updateTitle();
            }
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
        readAgain();
    }

    /** Whether the log has the whole frame, with its details. */
    boolean isFull() {
        return files != null;
    }

    // The full log is a frame view: switched to and away from as one, its
    // files and diff with it.

    @Override
    public FrameView getFrameView() {
        return isFull() && !disposed ? this : null;
    }

    @Override
    public List<Buffer> getChildren() {
        return files == null ? List.of() : List.of(files, diff);
    }

    @Override
    public WindowLayout.Node layout(Frame frame, Buffer focus) {
        return LogCommands.layout(frame, this, focus);
    }

    @Override
    public WindowLayout.Node before() {
        return before;
    }

    @Override
    public void setBefore(WindowLayout.Node before) {
        this.before = before;
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
            if (isFull())
                frame.leaveView(this, getChildren().isEmpty() ? List.of(this) : List.of(this, files, diff));
            else {
                final Editor ed = Editor.windowShowing(this);
                if (ed != null && frame.hasSplit())
                    frame.closeEditor(ed);
            }
        }
        for (Buffer b : new Buffer[] { diff, files, this })
            if (b != null && Editor.getBufferList().contains(b))
                b.kill();
    }

    // A pane killed on its own closes the log, as q does.
    @Override
    public boolean partKilled(Buffer part) {
        if (disposed)
            return false;
        kill();
        return true;
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

    /** The commits read so far. */
    List<LogCommit> commits() {
        return commits;
    }

    /** Whether a page is being read. */
    boolean isLoading() {
        return merge.isLoading();
    }

    // Reading.

    /**
     * Reads the next page of each repository that has more and none read
     * left to list, in the background: in parallel, a process each.
     */
    void loadMore() {
        if (disposed)
            return;
        final LogMerge m = merge;
        // None to read, as when the filters leave every repository out: no rows.
        if (fresh && !m.hasMore() && !m.isLoading()) {
            append(List.of());
            return;
        }
        for (LogMerge.Source source : m.wanted()) {
            m.loading(source);
            final VcsRepository r = (VcsRepository) source.key;
            final int skip = source.skip();
            final LogQuery q = scope.queryFor(r, query);
            Background.start("vcs log", () -> {
                List<LogCommit> page;
                try {
                    page = r.backend().readLog(r, q, skip, pageSize);
                }
                catch (RuntimeException e) {
                    Log.error(e);
                    page = List.of();
                }
                final List<LogCommit> read = page != null ? page : List.of();
                SwingUtilities.invokeLater(() -> {
                    if (m == merge) {
                        m.add(source, read);
                        append(m.take());
                    }
                });
            });
        }
    }

    /** Reads it again from the first page, the caret staying on its commit, and the refs and paths. */
    void refresh() {
        readAgain();
        LogNames.read(scope, true);
    }

    // From the first page again, any page being read dropped.
    private void readAgain() {
        if (disposed)
            return;
        final LogCommit at = commitAt(Editor.windowShowing(this));
        select = at;
        merge = newMerge();
        fresh = true;
        jumping = null;
        commits.clear();
        graph = new LogGraph();
        loadMore();
    }

    // The commits the pages read let be listed next.
    private void append(List<LogCommit> page) {
        if (disposed)
            return;
        // The old rows stay until there are new ones, or none are coming.
        final boolean first = fresh;
        if (first && page.isEmpty() && (merge.hasMore() || merge.isLoading())) {
            loadMore();
            return;
        }
        fresh = false;
        commits.addAll(page);
        LogNames.addAuthorsOf(page);
        final long now = System.currentTimeMillis() / 1000;
        final List<Line> lines = new ArrayList<>();
        // No graph of commits whose parents may be left out.
        final List<LogGraph.Row> rows = query.skipsCommits() ? null : graph.add(page);
        // The old rows keep their graph's width until these replace them.
        if (first)
            graphWidth = 0;
        for (int i = 0; i < page.size(); i++) {
            final LogGraph.Row row = rows == null ? null : rows.get(i);
            if (row != null)
                graphWidth = Math.max(graphWidth, row.width());
        }
        // The first page laid out for the room its graph leaves; the rows
        // after, a wider graph's display lays out again when it paints.
        final Editor shown = first ? Editor.windowShowing(this) : null;
        if (shown != null && shown.getDisplay().getColumns() > 0)
            width = shown.getDisplay().getColumns();
        if (first)
            branchLanes.clear();
        for (int i = 0; i < page.size(); i++) {
            final LogGraph.Row row = rows == null ? null : rows.get(i);
            lines.add(new LogLine(page.get(i), row, LogRow.layout(page.get(i), width, now, timeStyle)));
            if (row != null)
                for (LogRef r : page.get(i).refs())
                    if (r.kind() != LogRef.Kind.TAG)
                        branchLanes.putIfAbsent(r.name(), row.color());
        }
        if (first)
            empty();
        withWriteLock(() -> {
            if (first && lines.isEmpty())
                appendLine(new TextLine(emptyText()));
            for (Line l : lines)
                appendLine(l);
            renumber();
        });
        invalidate();
        Line to = null;
        if (select != null)
            for (Line l = getFirstLine(); l != null && to == null; l = l.next())
                if (l instanceof LogLine ll && ll.commit().isSame(select))
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
        // A page read and nothing listed: another repository's is wanted first.
        if (page.isEmpty())
            loadMore();
        if (jumping != null && !page.isEmpty())
            jump(jumping.editor(), jumping.key());
    }

    // A file's log with no commits has none; filters, as an author, match none.
    private String emptyText() {
        if (query.withPath(null, false).isFiltered())
            return "No commits match the filters";
        final String none = scope.first().backend().name() + ": no commits";
        return query.path() != null ? none + " of " + query.path() : none;
    }

    @Override
    public GutterColumn getGutterColumn() {
        return graphColumn;
    }

    /** How many lanes the widest row of the graph spans. */
    int graphWidth() {
        return graphWidth;
    }

    // Laying out.

    @Override
    public void windowResized(Editor editor) {
        final int columns = editor.getDisplay().getColumns();
        if (columns <= 0 || columns == width)
            return;
        width = columns;
        relayout();
    }

    public String getTimeStyle() {
        return timeStyle;
    }

    /** The rows' and the details' dates in a time style: age, locale, iso or long-iso. */
    void setTimeStyle(String style) {
        if (!TimeStyle.ALL.contains(style) || style.equals(timeStyle))
            return;
        timeStyle = style;
        relayout();
        if (files != null)
            files.restyle();
    }

    // Each row laid out again, for the width and the time style.
    private void relayout() {
        final long now = System.currentTimeMillis() / 1000;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof LogLine ll)
                ll.layout(LogRow.layout(ll.commit(), width, now, timeStyle));
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
        if (editor.getDotLineNumber() >= getLineCount() - AHEAD)
            loadMore();
        if (isFull() && commitAt(editor) != shown)
            follow.restart();
    }

    /**
     * Puts editor's caret on the commit key names (LogGraph.key), the other
     * end of a lane's arrow; one not read yet is read on toward.
     */
    void jump(Editor editor, String key) {
        jumping = null;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof LogLine ll && LogGraph.key(ll.commit(), ll.commit().id()).equals(key)) {
                editor.moveDotTo(l, 0);
                editor.updateDisplay();
                // As after a command: its details follow, and pages near the end are read.
                afterCommand(editor);
                return;
            }
        if (!merge.hasMore()) {
            editor.status("Not in the log");
            return;
        }
        jumping = new Jump(editor, key);
        editor.status("Reading on to it…");
        loadMore();
    }

    // A jump to a commit not read yet, made when its page is.
    private record Jump(Editor editor, String key) {}

    private Jump jumping;

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
        final LogCommit c = commitAt(Editor.windowShowing(this));
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
