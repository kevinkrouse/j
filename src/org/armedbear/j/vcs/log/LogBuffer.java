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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Folds;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.PerFrame;
import org.armedbear.j.Property;
import org.armedbear.j.SessionProperties;
import org.armedbear.j.Sidebar;
import org.armedbear.j.TextLine;
import org.armedbear.j.ViewBuffer;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.CompareCommands;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.vcs.VcsRepository;

/**
 * A repository's log, or several's together: a row per commit, newest
 * first, read a page at a time as the caret nears the end. In the full
 * log, the commit at the caret has its files and the diff of one shown
 * beside it, a moment after the caret stops on it.
 */
public final class LogBuffer extends ViewBuffer implements FrameView, DiffWhitespace.Listener, CommitDetails.Host {
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
    // Every commit's line read, in order: the buffer's, hidden or not.
    private final List<LogLine> commitLines = new ArrayList<>();
    // Whether runs of plain commits start folded, and those, by their first
    // commit, folded or opened since, the other way.
    private boolean collapse = Editor.preferences().getBooleanProperty(Property.VCS_LOG_COLLAPSE_RUNS);
    private final Set<String> flipped = new HashSet<>();
    // The runs, by the line that heads each and by each line in one; and
    // those whose heads say they are folded.
    private final Map<LogLine, Run> runHeaded = new HashMap<>();
    private final Map<LogLine, Run> runHolding = new HashMap<>();
    private final Set<Run> labeled = new HashSet<>();
    // The lane dotted above each commit a folded run comes before.
    private final Map<LogLine, LogGraph.Lane> dottedAbove = new HashMap<>();
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
    // The commit at the caret's details, in the full log; else null.
    CommitDetails details;
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

    @Override
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
    @Override
    public int laneColorOf(LogCommit c) {
        for (LogLine ll : commitLines)
            if (ll.commit().isSame(c))
                return ll.graph() == null ? -1 : ll.graph().color();
        return -1;
    }

    @Override
    public boolean isStash(LogCommit c) {
        return c.isStash();
    }

    /** The repository c is in. */
    @Override
    public VcsRepository repositoryOf(LogCommit c) {
        return c.repository() != null ? c.repository() : scope.first();
    }

    // A repository's stashes, a source of their own, read whole.
    private record Stashes(VcsRepository repository) {}

    // The repositories query reads, each a source, its stashes before it
    // to go above a commit of the same time, as their bases are.
    private LogMerge newMerge() {
        final List<Object> read = new ArrayList<>();
        for (VcsRepository r : scope.repositories()) {
            final LogQuery q = scope.queryFor(r, query);
            if (q == null)
                continue;
            if (q.showsStashes())
                read.add(new Stashes(r));
            read.add(r);
        }
        return new LogMerge(read, pageSize);
    }

    public LogQuery getQuery() {
        return query;
    }

    /** Shows the commits query takes, read again from the first page. */
    void setQuery(LogQuery query) {
        if (query.equals(this.query))
            return;
        // With a branch, tags change nothing read, nor stashes with a
        // filter: kept for when it is unset.
        final LogQuery was = this.query;
        final boolean same = query.withTags(was.tags()).withStashes(was.stashes()).equals(was)
                && (query.tags() == was.tags() || !query.revisions().isEmpty())
                && (query.stashes() == was.stashes() || !query.canShowStashes());
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
        return details != null;
    }

    // The full log is a frame view: switched to and away from as one, its
    // files and diff with it.

    @Override
    public FrameView getFrameView() {
        return isFull() && !disposed ? this : null;
    }

    @Override
    public List<Buffer> getChildren() {
        return details == null ? List.of() : details.panes();
    }

    @Override
    public WindowLayout.Node layout(Frame frame, Buffer focus) {
        return details.layout(this, focus);
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
            if (isFull()) {
                final List<Buffer> parts = new ArrayList<>(List.of(this));
                parts.addAll(getChildren());
                if (!parts.contains(details.diff))
                    parts.add(details.diff);
                frame.leaveView(this, parts);
            } else {
                final Editor ed = Editor.windowShowing(this);
                if (ed != null && frame.hasSplit())
                    frame.closeEditor(ed);
            }
        }
        if (details != null)
            details.close();
        if (Editor.getBufferList().contains(this))
            kill();
    }

    /**
     * Where a command from the toolbar or a menu runs, from from: a diff's
     * in the diff; from a side of the comparison, a vcs command it has no
     * key for in the list; the rest, as the editor's own, in from.
     */
    @Override
    public Editor windowFor(Editor from, String command) {
        final Editor diffs =
                details == null ? null : CompareCommands.windowFor(from, command, details.diff, details.comparison());
        if (diffs != null)
            return diffs;
        final Editor list = from.getFrame() != null ? from.getFrame().findEditor(this) : null;
        final Buffer b = from.getBuffer();
        return list != null
                && command.startsWith("vcs")
                && CompareBuffer.of(b) != null
                && !CompareCommands.sideRuns(b, command) ? list : from;
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
        refTrees.forget();
        super.dispose();
    }

    @Override
    public boolean isDisposed() {
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
            if (source.key instanceof Stashes s) {
                loadStashes(m, source, s.repository());
                continue;
            }
            final VcsRepository r = (VcsRepository) source.key;
            final int skip = source.skip();
            final LogQuery q = scope.queryFor(r, query);
            Background.start("vcs log", () -> {
                List<LogCommit> page;
                boolean threw = false;
                List<String> starts = source.starts;
                try {
                    if (skip == 0 && starts == null)
                        starts = r.backend().readStarts(r, q);
                    // Nothing to start from, as in an empty repository: no page.
                    if (starts != null && starts.isEmpty())
                        page = List.of();
                    else
                        page = r.backend().readLog(r, starts != null ? q.withRevisions(starts) : q, skip, pageSize);
                }
                catch (RuntimeException e) {
                    Log.error(e);
                    page = null;
                    threw = true;
                }
                final boolean ok = !threw;
                final List<LogCommit> read = page != null ? page : List.of();
                final List<String> started = starts;
                SwingUtilities.invokeLater(() -> {
                    if (m != merge)
                        ++dropped;
                    else {
                        source.starts = started;
                        // A failed read ends its repository's pages, and says so.
                        if (!ok) {
                            failed = true;
                            final Editor ed = Editor.windowShowing(this);
                            if (ed != null)
                                ed.status("Reading the log failed: see vcsCommandLog");
                        }
                        m.add(source, read);
                        append(m.take());
                    }
                });
            });
        }
    }

    // r's stashes, all of them, in the background.
    private void loadStashes(LogMerge m, LogMerge.Source source, VcsRepository r) {
        Background.then("vcs log stashes", () -> r.backend().readStashes(r), read -> {
            if (m != merge) {
                ++dropped;
                return;
            }
            m.addLast(source, read != null ? read : List.of());
            append(m.take());
        });
    }

    /** Reads it again from the first page, the caret staying on its commit, and the refs and paths. */
    void refresh() {
        readAgain();
        LogNames.read(scope, true, this::refsRead);
    }

    // Its refs in each frame's sidebar it is in, one for its list, files and
    // diff there; the refs are its names', read once for every tree.
    private final PerFrame<LogRefTree> refTrees = new PerFrame<>(frame -> {
        final LogRefTree tree = new LogRefTree(this);
        tree.update();
        LogNames.read(getScope(), false, this::refsRead);
        return tree;
    });

    // The refs read again: each tree shows them.
    private void refsRead() {
        for (LogRefTree t : refTrees.all())
            t.update();
    }

    /** How the refs tree orders the branches: by name, or the newest commit first. */
    static final List<String> REFS_ORDERS = List.of("name", "date");

    private String refsOrder = REFS_ORDERS.contains(Editor.preferences().getStringProperty(Property.VCS_LOG_REFS_ORDER))
            ? Editor.preferences().getStringProperty(Property.VCS_LOG_REFS_ORDER)
            : "name";

    String getRefsOrder() {
        return refsOrder;
    }

    void setRefsOrder(String order) {
        if (!REFS_ORDERS.contains(order) || order.equals(refsOrder))
            return;
        refsOrder = order;
        for (LogRefTree t : refTrees.all())
            t.reorder();
    }

    /** Its refs tree in frame's sidebar, or with null its own. */
    LogRefTree refTree(Frame frame) {
        return refTrees.in(frame);
    }

    /**
     * The caret of the window on it to commit id of repository, reading on
     * to it, if the log lists it: asked of the backend first, so that a
     * commit its filters leave out reads nothing. A stash is listed when
     * the log shows the stashes.
     */
    void reveal(VcsRepository repository, String id, boolean stash) {
        final Editor ed = Editor.windowShowing(this);
        if (ed == null)
            return;
        final LogQuery q = scope.queryFor(repository, query);
        if (q == null) {
            ed.status("Not in the log: its repository is filtered out");
            return;
        }
        if (stash) {
            if (q.showsStashes())
                jump(ed, repository.root() + "\0" + id);
            else
                ed.status(q.stashes() ? "Not in the log with its filters" : "Not in the log (-z shows the stashes)");
            return;
        }
        Background.start("vcs log reveal", () -> {
            boolean has;
            try {
                has = repository.backend().logHas(repository, q, id);
            }
            catch (RuntimeException e) {
                Log.error(e);
                has = false;
            }
            final boolean listed = has;
            SwingUtilities.invokeLater(() -> {
                // Not if its window shows another buffer by now.
                if (disposed || ed.getBuffer() != this)
                    return;
                if (listed)
                    jump(ed, repository.root() + "\0" + id);
                else
                    ed.status(
                        "Not in the log with its filters"
                                + (query.tags() ? "" : " (-T shows the commits only tags reach)"));
            });
        });
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
        failed = false;
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
            commitLines.clear();
        for (Line l : lines)
            commitLines.add((LogLine) l);
        show(first, lines);
        // The runs the new rows make or join, folded as they are or should be.
        findRuns(first);
        // Looked for in the first page only: found later, it would take the caret from where it went.
        select = null;
        if (first && isFull())
            showDetails();
        // A page read and nothing listed: another repository's is wanted first.
        if (page.isEmpty())
            loadMore();
        if (jumping != null && !page.isEmpty()) {
            if (jumping.stands(this))
                jump(jumping.editor(), jumping.key());
            else
                jumping = null;
        }
    }

    // listed after the rows there, or in place of them for a first page,
    // the caret on the commit selected, or the first, then.
    private void show(boolean first, List<Line> listed) {
        if (first)
            empty();
        withWriteLock(() -> {
            if (first && listed.isEmpty())
                appendLine(new TextLine(emptyText()));
            for (Line l : listed)
                appendLine(l);
            renumber();
        });
        invalidate();
        Line to = null;
        if (select != null)
            for (LogLine l : commitLines)
                if (l.commit().isSame(select)) {
                    to = l;
                    break;
                }
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
    }

    // A file's log with no commits has none; filters, as an author, match none.
    // Whether a read of the pages listed failed.
    private boolean failed;

    // Pages read for a query since changed, and dropped: for tests.
    private int dropped;

    int dropped() {
        return dropped;
    }

    private String emptyText() {
        if (failed)
            return "Reading the log failed: see vcsCommandLog";
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

    @Override
    public String getTimeStyle() {
        return timeStyle;
    }

    /** The rows' and the details' dates in a time style: age, locale, iso or long-iso. */
    void setTimeStyle(String style) {
        if (!TimeStyle.ALL.contains(style) || style.equals(timeStyle))
            return;
        timeStyle = style;
        relayout();
        if (details != null)
            details.restyle();
    }

    // Each row laid out again, for the width and the time style.
    private void relayout() {
        final long now = System.currentTimeMillis() / 1000;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof LogLine ll)
                ll.layout(layout(ll, now));
        invalidate();
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this) {
                ed.setUpdateFlag(REPAINT);
                ed.updateDisplay();
            }
    }

    // A commit's row for the width and the time style; a folded run's
    // head's with its count after the subject and badges.
    private LogRow layout(LogLine l, long now) {
        final Run run = runHeaded.get(l);
        return LogRow
                .layout(l.commit(), width, now, timeStyle, run != null && labeled.contains(run) ? run.label() : "");
    }

    // Collapsing runs, as folds.

    /**
     * A run of two or more plain commits, one after another down one lane,
     * a fold headed by the commit above: folded, its lines are hidden, the
     * head shows how many after its subject, and the lane is dotted where
     * they were, to after.
     */
    private record Run(String key, LogLine head, List<LogLine> lines, LogLine after) {
        boolean folded() {
            return lines.get(0).isHidden();
        }

        LogGraph.Lane lane() {
            return lines.get(0).graph().lane();
        }

        String label() {
            return Folds.label(lines.size() + " commits");
        }
    }

    public boolean getCollapse() {
        return collapse;
    }

    /** Every run folded, or every commit shown, as zM and zR do. */
    void setCollapse(boolean on) {
        collapse = on;
        flipped.clear();
        for (Run run : new HashSet<>(runHeaded.values()))
            fold(run, on);
        runsChanged();
    }

    /** Shift Tab: every run folded, or every one opened when none is open. */
    void toggleAllRuns() {
        boolean anyOpen = false;
        for (Run run : runHeaded.values())
            anyOpen |= !run.folded();
        setCollapse(anyOpen);
    }

    /** Whether line heads a run: its fold marker's. */
    boolean isRunHead(Line line) {
        return line instanceof LogLine l && runHeaded.containsKey(l);
    }

    /**
     * Folds, opens, or with null toggles, the run line heads or is in, as
     * the fold commands do; false if there is none.
     */
    boolean setRun(Line line, Boolean open) {
        if (!(line instanceof LogLine l))
            return false;
        final Run run = runHeaded.containsKey(l) ? runHeaded.get(l) : runHolding.get(l);
        if (run == null)
            return false;
        final boolean folded = open != null ? !open : !run.folded();
        if (folded != run.folded()) {
            fold(run, folded);
            if (folded != collapse)
                flipped.add(run.key());
            else
                flipped.remove(run.key());
            runsChanged();
        }
        return true;
    }

    // Its lines hidden once, not again as each page finds it folded, or shown.
    private static void fold(Run run, boolean folded) {
        for (LogLine l : run.lines())
            if (!folded)
                l.show();
            else if (!l.isHidden())
                l.hide();
    }

    private static String key(LogLine l) {
        return LogGraph.key(l.commit(), l.commit().id());
    }

    /**
     * The runs among the commits read, after a page: a run that was there,
     * as it was, its new lines folded with it; a new one as -l and the runs
     * flipped say. A plain commit has one parent and one child read, is no
     * merge, has no ref, and a row nothing but its lane passes through
     * straight.
     */
    private void findRuns(boolean first) {
        final Map<LogLine, Run> before = first ? Map.of() : new HashMap<>(runHolding);
        final Map<String, Integer> children = new HashMap<>();
        for (LogLine l : commitLines)
            for (String p : l.commit().graphParents())
                children.merge(LogGraph.key(l.commit(), p), 1, Integer::sum);
        runHeaded.clear();
        runHolding.clear();
        int i = 0;
        while (i < commitLines.size()) {
            int j = i;
            if (i > 0 && plain(commitLines.get(i), children))
                while (j + 1 < commitLines.size()
                        && plain(commitLines.get(j + 1), children)
                        && follows(commitLines.get(j + 1), commitLines.get(j)))
                    j++;
            if (j > i) {
                final Run run = new Run(key(commitLines.get(i)),
                        commitLines.get(i - 1),
                        List.copyOf(commitLines.subList(i, j + 1)),
                        j + 1 < commitLines.size() ? commitLines.get(j + 1) : null);
                final Run was = before.get(run.lines().get(0));
                fold(run, was != null ? was.folded() : collapse != flipped.contains(run.key()));
                runHeaded.put(run.head(), run);
                for (LogLine l : run.lines())
                    runHolding.put(l, run);
            }
            i = j + 1;
        }
        // Flips of runs gone, once every commit is read.
        if (!merge.hasMore()) {
            final Set<String> keys = new HashSet<>();
            for (Run run : runHeaded.values())
                keys.add(run.key());
            flipped.retainAll(keys);
        }
        runsChanged();
    }

    /**
     * After a command: a run whose lines were shown by other means, as when
     * a search finds a commit in it, is open now; its head says so.
     */
    private void syncRuns() {
        boolean changed = false;
        for (Run run : runHeaded.values())
            if (run.folded() != labeled.contains(run)) {
                if (run.folded() != collapse)
                    flipped.add(run.key());
                else
                    flipped.remove(run.key());
                changed = true;
            }
        if (changed)
            runsChanged();
    }

    // The heads' counts and rows, the markers and the lines' numbers again,
    // as the runs folded have changed, the caret off any line hidden.
    private void runsChanged() {
        final Set<Run> was = new HashSet<>(labeled);
        labeled.clear();
        dottedAbove.clear();
        for (Run run : runHeaded.values())
            if (run.folded()) {
                labeled.add(run);
                if (run.after() != null)
                    dottedAbove.put(run.after(), run.lane());
            }
        final long now = System.currentTimeMillis() / 1000;
        for (Run run : runHeaded.values()) {
            run.head().setHeadsFolded(labeled.contains(run));
            if (labeled.contains(run) != was.contains(run))
                run.head().layout(layout(run.head(), now));
        }
        // A head that heads nothing now, as after a refresh: plain again.
        for (Run run : was)
            if (!labeled.contains(run) && !runHeaded.containsKey(run.head())) {
                run.head().setHeadsFolded(false);
                run.head().layout(layout(run.head(), now));
            }
        renumber();
        foldsChanged();
        invalidate();
        // The window as it is, unless its caret was folded away: then on the head, in sight.
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this) {
                if (ed.getDot() != null && ed.getDotLine().isHidden()) {
                    final Line visible = ed.getDotLine().previousVisible();
                    if (visible != null) {
                        ed.setDot(visible, 0);
                        ed.setMark(null);
                        ed.moveCaretToDotCol();
                        ed.setUpdateFlag(REFRAME);
                    }
                }
                ed.setUpdateFlag(REPAINT);
                ed.updateDisplay();
            }
    }

    /** The lane dotted below l's commit, or above it, where a run folded was; null if none. */
    LogGraph.Lane dottedLane(LogLine l, boolean below) {
        if (below) {
            final Run run = runHeaded.get(l);
            return run != null && run.folded() ? run.lane() : null;
        }
        return dottedAbove.get(l);
    }

    private static boolean plain(LogLine l, Map<String, Integer> children) {
        final LogCommit c = l.commit();
        return l.graph() != null
                && c.graphParents().size() == 1
                && !c.isMerge()
                && c.refs().isEmpty()
                && children.getOrDefault(key(l), 0) == 1
                && l.graph().straight();
    }

    // Whether l's commit is the first parent of above's, in its lane.
    private static boolean follows(LogLine l, LogLine above) {
        return l.graph().lane() == above.graph().lane()
                && key(l).equals(LogGraph.key(above.commit(), above.commit().graphParents().get(0)));
    }

    // Whether fewer than AHEAD lines are shown after line: the caret can't go
    // on far, however many lines a run folded at the end hides.
    private static boolean nearTheEnd(Line line) {
        int shown = 0;
        for (Line l = line.nextVisible(); l != null && shown < AHEAD; l = l.nextVisible())
            shown++;
        return shown < AHEAD;
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
        // A command that moved the caret: a jump still reading on gives up.
        if (jumping != null && !jumping.stands(this))
            jumping = null;
        // A run shown by the caret's landing in it, as by a search, is open.
        syncRuns();
        if (nearTheEnd(editor.getDotLine()))
            loadMore();
        if (isFull() && commitAt(editor) != details.shown())
            follow.restart();
    }

    /**
     * Puts editor's caret on the commit key names (LogGraph.key), the other
     * end of a lane's arrow; one not read yet is read on toward.
     */
    void jump(Editor editor, String key) {
        jumping = null;
        // In a run folded, shown as the caret lands there, as a search's is.
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof LogLine ll && key(ll).equals(key)) {
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
        jumping = new Jump(editor, key, editor.getDotLine());
        editor.status("Reading on to it… (move the caret to stop)");
        loadMore();
    }

    // A jump to a commit not read yet, made when its page is, unless the
    // caret has moved from where it was asked or its window shows another
    // buffer by then.
    private record Jump(Editor editor, String key, Line from) {
        boolean stands(LogBuffer log) {
            return editor.getBuffer() == log && editor.getDot() != null && editor.getDotLine() == from;
        }
    }

    private Jump jumping;

    /** The commit at editor's caret, or null. */
    static LogCommit commitAt(Editor editor) {
        return editor != null && editor.getDot() != null && editor.getDotLine() instanceof LogLine l
                ? l.commit()
                : null;
    }

    @Override
    public CommitDetails details() {
        return details;
    }

    @Override
    public void leave(Frame frame) {
        close(frame);
    }

    /** Shows the details of the commit at the caret of the window showing the log now. */
    void showDetails() {
        if (!isFull() || disposed)
            return;
        details.show(commitAt(Editor.windowShowing(this)));
    }

    // Its unified diff read again, of the file the files pane shows.
    @Override
    public void diffWhitespaceChanged() {
        if (!disposed && details != null)
            details.diffWhitespaceChanged();
    }

    // Side by side.

    // Whether the diff is side by side, as last chosen, kept in the session.
    private static final String SIDE_BY_SIDE_KEY = "vcsLog.sideBySide";

    /** Whether a log's diff shows side by side to begin with: as = last left one. */
    static boolean sideBySideAtFirst() {
        final SessionProperties session = Editor.getSessionProperties();
        return session != null && session.getBooleanProperty(SIDE_BY_SIDE_KEY, false);
    }

    /**
     * =: the diff switched between unified and side by side across frame,
     * the list and the files above the two sides, kept for the next log.
     */
    @Override
    public void diffView(Frame frame, Buffer focus) {
        if (!isFull() || disposed)
            return;
        // Not laid out, as with a window closed by hand: laid out again as it is.
        if (frame != null && frame.shownView() != this) {
            frame.showView(this, focus);
            return;
        }
        final SessionProperties session = Editor.getSessionProperties();
        if (session != null)
            session.setBooleanProperty(SIDE_BY_SIDE_KEY, !details.isSideBySide());
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
        details.toggleSides(frame, this, focus);
    }
}
