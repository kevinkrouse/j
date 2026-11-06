/*
 * VcsStatusBuffer.java
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

package org.armedbear.j.vcs.status;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Mode;
import org.armedbear.j.PerFrame;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.Sidebar;
import org.armedbear.j.ViewBuffer;
import org.armedbear.j.VirtualText;
import org.armedbear.j.WindowCommands;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.WindowLayout.Split;
import org.armedbear.j.WindowLayout.Window;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.CompareCommands;
import org.armedbear.j.mode.compare.DiffAlignment;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.mode.compare.Following;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.PickState;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsDiffCommands;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.log.CommitDetails;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogScope;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.armedbear.j.vcs.signs.StatusView;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusData.StashItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * The status of one repository, or of a project's several: sections of
 * files and commits, each file's diff shown inline when expanded. It is
 * read again in the background whenever its repositories change, and a
 * refresh keeps the caret on the thing it was on. With the diff beside it,
 * it is a frame view, the diff following its caret: unified at the right,
 * or side by side under it.
 */
public final class VcsStatusBuffer extends ViewBuffer
        implements FrameView, DiffWhitespace.Listener, CommitDetails.Host, StatusView.Source {
    // How long the caret rests on a file before the diff beside follows it.
    private static final int FOLLOW_DELAY = 100;

    private final List<VcsRepository> repositories;
    // What was last read of each repository, on the event thread.
    private final Map<VcsRepository, StatusData> data = new LinkedHashMap<>();
    // Nodes opened or closed by hand, by key; read by the background reads too.
    private final Map<String, Boolean> expanded = new ConcurrentHashMap<>();
    // Files whose diffs have been read, so that none means there is none.
    private final Set<String> diffsRead = new HashSet<>();
    // A change to a repository: its commits' refs read again too.
    private final Consumer<VcsRepository> listener = r -> {
        SwingUtilities.invokeLater(this::forgetCommits);
        refresh();
    };
    // How deep nodes are open by default: 1 sections closed, 2 files closed,
    // 3 hunks closed, 4 all open.
    private volatile int depth = 2;
    private boolean reading;
    private boolean readAgain;
    private volatile boolean disposed;
    // A write is running, or its refresh not shown yet: what the lines show is old.
    private volatile boolean writing;
    private final AtomicLong clock = new AtomicLong();
    private volatile long wroteAt;
    // The diff beside it, or null; what it was last shown to follow, and
    // why it follows next, null for nothing new.
    private StatusDiffBuffer diff;
    private String followed;
    private StatusDiffBuffer.Follow pending;
    private final Timer follow = new Timer(FOLLOW_DELAY, e -> followCaret());
    // The windows before the diff beside took the frame; null when it isn't shown.
    private WindowLayout.Node before;
    // Whether the diff is side by side, and then its side-by-side form,
    // which shows the file the diff beside has.
    private boolean sideBySide;
    private SideBySide sides;
    // In place of the diff, while the caret is on a commit: its details, as
    // the log shows them; else null.
    private CommitDetails commits;
    // The commits read for them, by repository and hash.
    private final Map<String, LogCommit> commitsRead = new HashMap<>();

    private VcsStatusBuffer(List<VcsRepository> repositories) {
        super(VcsStatusMode.getMode(), title(repositories), "Reading…");
        this.repositories = List.copyOf(repositories);
        setProperty(Property.FOLD_MARKERS, "always");
        addGutterColumn(new StatusPickColumn(this));
        follow.setRepeats(false);
        for (VcsRepository r : repositories)
            r.addListener(listener);
        SignsUpdater.addSource(this);
        // Its first read takes the diffs its changelists want.
        knowLists();
    }

    private static String title(List<VcsRepository> repositories) {
        final VcsRepository first = repositories.get(0);
        return repositories.size() == 1
                ? first.backend().name() + " status: " + first.root().getFileName()
                : first.backend().name() + " status: " + repositories.size() + " repositories";
    }

    /** The status buffer of these repositories open, or null. */
    public static VcsStatusBuffer find(List<VcsRepository> repositories) {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof VcsStatusBuffer s && s.repositories.equals(repositories))
                return s;
        return null;
    }

    /** The status buffer of these repositories, the one open if there is one. */
    public static VcsStatusBuffer of(List<VcsRepository> repositories) {
        final VcsStatusBuffer open = find(repositories);
        if (open != null)
            return open;
        final VcsStatusBuffer buffer = new VcsStatusBuffer(repositories);
        buffer.refresh();
        return buffer;
    }

    public List<VcsRepository> getRepositories() {
        return repositories;
    }

    @Override
    public void dispose() {
        disposed = true;
        follow.stop();
        // Its picks gone from its files' buffers, their boxes and marks,
        // and its diffs from their signs.
        SignsUpdater.removeSource(this);
        picks.clear();
        signsChanged(u -> true);
        for (VcsRepository r : repositories)
            r.removeListener(listener);
        trees.forget();
        super.dispose();
    }

    // Reading.

    // Its diffs read again, ignoring the whitespace diffs ignore now.
    @Override
    public void diffWhitespaceChanged() {
        if (commits != null)
            commits.diffWhitespaceChanged();
        refresh();
    }

    /** Reads its repositories again in the background, then shows them. */
    public void refresh() {
        synchronized (this) {
            if (disposed)
                return;
            if (reading) {
                readAgain = true;
                return;
            }
            reading = true;
        }
        Background.start("vcs status", this::readLoop);
    }

    private void readLoop() {
        try {
            while (true) {
                final long startedAt = clock.incrementAndGet();
                final long wallAt = System.currentTimeMillis();
                final Whitespace ignored = DiffWhitespace.ignored();
                final Map<VcsRepository, StatusData> read = new LinkedHashMap<>();
                final Set<String> diffs = ConcurrentHashMap.newKeySet();
                for (VcsRepository r : repositories) {
                    StatusData d;
                    try {
                        d = r.backend().readStatus(r, (section, path) -> {
                            final String key = StatusRenderer.fileKey(r, section, path);
                            if (!isExpanded(key, Kind.FILE)
                                    && !partlyPicked.contains(key)
                                    && !listedKeys.contains(key)
                                    && !wanted.contains(key)
                                    && !viewed.contains(key))
                                return false;
                            diffs.add(key);
                            return true;
                        }, ignored);
                    }
                    catch (RuntimeException e) {
                        Log.error(e);
                        d = StatusData.failed(e.toString());
                    }
                    // Changelists take the work tree's changes whitespace and all.
                    if (d != null && d.error() == null && ignored != Whitespace.NONE && listing.contains(r))
                        d = withFullUnstaged(r, d, diffs);
                    read.put(r, d != null ? d : StatusData.failed(r.backend().name() + " has no status"));
                }
                SwingUtilities.invokeLater(() -> show(read, diffs, startedAt, wallAt, ignored));
                synchronized (this) {
                    if (!readAgain || disposed) {
                        reading = false;
                        return;
                    }
                    readAgain = false;
                }
            }
        }
        catch (Throwable e) {
            synchronized (this) {
                reading = false;
                readAgain = false;
            }
            throw e;
        }
    }

    // d with its unstaged section read again with whitespace shown, the
    // diffs it takes added to diffs; d as it is if that read fails.
    private StatusData withFullUnstaged(VcsRepository r, StatusData d, Set<String> diffs) {
        final StatusData full;
        try {
            full = r.backend().readStatus(r, (section, path) -> {
                final String key = StatusRenderer.fileKey(r, section, path);
                if (!StatusRenderer.UNSTAGED.equals(section)
                        || !isExpanded(key, Kind.FILE)
                                && !partlyPicked.contains(key)
                                && !listedKeys.contains(key)
                                && !wanted.contains(key)
                                && !viewed.contains(key))
                    return false;
                diffs.add(key);
                return true;
            }, Whitespace.NONE);
        }
        catch (RuntimeException e) {
            Log.error(e);
            return d;
        }
        if (full == null || full.error() != null)
            return d;
        final Section unstaged =
                full.sections().stream().filter(x -> x.id().equals(StatusRenderer.UNSTAGED)).findFirst().orElse(null);
        final List<Section> sections = new ArrayList<>();
        for (Section x : d.sections())
            sections.add(x.id().equals(StatusRenderer.UNSTAGED) && unstaged != null ? unstaged : x);
        return new StatusData(d.header(), d.inProgress(), sections, d.error());
    }

    private void show(
            Map<VcsRepository, StatusData> read,
            Set<String> diffs,
            long startedAt,
            long wallAt,
            Whitespace ignored) {
        if (disposed)
            return;
        final long before = shownReadAt;
        shownReadAt = wallAt;
        // Its files' signs, which its diffs place, and what of them is
        // picked, as read now: all, or with nothing new read, those saved
        // since the read before, whose picks were hidden till this one.
        if (showRead(read, diffs, startedAt, ignored))
            signsChanged(u -> true);
        else
            signsChanged(u -> u.getBuffer().getFileChangedAt() >= before);
        // The picks from files waiting on this read.
        final List<PendingPick> waiting = List.copyOf(pendingPicks);
        pendingPicks.clear();
        for (PendingPick p : waiting)
            pickUnstagedAt(p, diffs);
        // What waits on diffs this read took, or a second read whatever it took.
        final List<PendingRead> reads = List.copyOf(pendingReads);
        pendingReads.clear();
        for (PendingRead r : reads)
            if (diffs.containsAll(r.keys()) || r.tried()) {
                wanted.removeAll(r.keys());
                r.then().run();
            } else {
                pendingReads.add(new PendingRead(r.keys(), r.then(), true));
                refresh();
            }
    }

    // What waits on a read of files' diffs, by their lines' keys; whether a read has been tried.
    private record PendingRead(Set<String> keys, Runnable then, boolean tried) {}

    private final List<PendingRead> pendingReads = new ArrayList<>();

    /**
     * Reads the diffs of the files whose lines are keyed keys, then runs
     * then, after the read that has them, or the second after, whatever
     * it took.
     */
    void readDiffsThen(Set<String> keys, Runnable then) {
        wanted.addAll(keys);
        pendingReads.add(new PendingRead(Set.copyOf(keys), then, false));
        refresh();
    }

    // Whether it found anything new, and showed it.
    private boolean showRead(
            Map<VcsRepository, StatusData> read,
            Set<String> diffs,
            long startedAt,
            Whitespace ignored) {
        final boolean newWhitespace = ignored != readIgnoring;
        readIgnoring = ignored;
        // A file the status has closed may have changed with nothing it shows.
        followAgain(StatusDiffBuffer.Follow.REFRESHED);
        final boolean wrote = startedAt > wroteAt && writing;
        if (wrote) {
            writing = false;
            updateToolbars();
        }
        // A refresh comes with each save and each return to j's window: most
        // find nothing new, and leave the lines, and the selection, as they
        // are; but a pick waiting on a diff that did not come is dropped.
        if (getFirstLine() instanceof StatusLine
                && diffsRead.containsAll(diffs)
                && !picks.unread(this)
                && read.entrySet().stream().allMatch(e -> e.getValue().equals(data.get(e.getKey()))))
            return false;
        data.putAll(read);
        diffsRead.addAll(diffs);
        // j's own write takes the changes picks were of whichever way they
        // were chosen: a pick it drops is not news.
        render(diffs, wrote ? null : newWhitespace ? Drop.WHITESPACE : Drop.GONE);
        return true;
    }

    // Why picks a render drops went, as the status bar says it.
    private enum Drop {
        GONE("its change is gone or changed", "their changes are gone or changed"),
        WHITESPACE("whitespace shown or ignored changed its hunk", "whitespace shown or ignored changed their hunks");

        final String one;
        final String many;

        Drop(String one, String many) {
            this.one = one;
            this.many = many;
        }
    }

    // The whitespace its diffs were last read ignoring.
    private Whitespace readIgnoring = Whitespace.NONE;

    /** The whitespace the diffs it shows were read ignoring. */
    Whitespace readIgnoring() {
        return readIgnoring;
    }

    // Each frame's toolbar showing it, as what its buttons can do changes.
    private void updateToolbars() {
        for (int i = 0; i < Editor.getFrameCount(); i++)
            Editor.getFrame(i).updateToolbar();
    }

    /** Whether a write is running, or its refresh not shown yet. */
    public boolean isWriting() {
        return writing;
    }

    /**
     * A write starts. Until it is done no read can count as after it, even
     * one already running, whose data may be from before it.
     */
    void startWriting() {
        wroteBefore = wroteAt;
        wroteAt = Long.MAX_VALUE;
        writing = true;
        ++writes;
    }

    // wroteAt before the write started, for one given up.
    private long wroteBefore;

    /** A write started is not made after all: as before it, with no read to wait for. */
    void gaveUpWriting() {
        wroteAt = wroteBefore;
        writing = false;
        --writes;
        updateToolbars();
    }

    // How many writes have started: the diff beside is stale until it has
    // been shown from a read after the last.
    private int writes;

    int writes() {
        return writes;
    }

    /**
     * A write is done: writing until a read that starts from now is shown.
     * The repositories' refresh, which tells this buffer, starts it.
     */
    void wrote() {
        wroteAt = clock.incrementAndGet();
    }

    // Its sections and files in each frame's sidebar it is in.
    private final PerFrame<StatusTree> trees = new PerFrame<>(frame -> {
        final StatusTree t = new StatusTree(this, frame);
        t.update();
        return t;
    });

    /** Its tree in frame's sidebar, or with null its own. */
    StatusTree tree(Frame frame) {
        return trees.in(frame);
    }

    /**
     * The caret of the window on it in frame, the current one, to the line
     * keyed key, or to its section's heading for null, the section opened
     * if it was closed, and the diff beside following; the window, or null
     * if none there shows it. With no frame, as in tests, any window's.
     */
    // The line keyed key, or null.
    private StatusLine lineOf(String key) {
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.key().equals(key))
                return s;
        return null;
    }

    Editor reveal(Frame frame, String section, String key) {
        final Editor ed = frame != null ? frame.findEditor(this) : Editor.windowShowing(this);
        if (ed == null)
            return null;
        if (key != null && !isExpanded(section, Kind.SECTION)) {
            expanded.put(section, true);
            render();
        }
        // A file under a changelist closed: the list opened.
        if (key != null && lineOf(key) == null)
            for (Line l = getFirstLine(); l != null; l = l.next())
                if (l instanceof StatusLine s
                        && s.kind() == Kind.LIST
                        && !s.isOpen()
                        && s.members().stream().anyMatch(m -> key.equals(StatusPicks.fileKey(s, m.file())))) {
                    expanded.put(s.key(), true);
                    render();
                    break;
                }
        final String wanted = key != null ? key : section;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.key().equals(wanted)) {
                ed.moveDotTo(l, 0);
                ed.setUpdateFlag(REFRAME);
                ed.updateDisplay();
                break;
            }
        afterCommand(ed);
        if (frame != null) {
            WindowCommands.switchWindow(frame.getCurrentEditor(), ed);
            frame.updateToolbar();
        }
        return ed;
    }

    /** What was last read of a repository, or null. */
    StatusData getData(VcsRepository repository) {
        return data.get(repository);
    }

    // Expanding.

    boolean isExpanded(String key, Kind kind) {
        final Boolean b = expanded.get(key);
        if (b != null)
            return b;
        // A file split across changelists, or its hunk, under another list
        // than its first: as under its first, until opened or closed itself.
        final String first = StatusRenderer.first(key);
        if (!first.equals(key))
            return isExpanded(first, kind);
        return switch (kind) {
            // A section of commits between branches only deeper.
            case SECTION -> depth >= (StatusData.RANGES.contains(key.substring(key.lastIndexOf('|') + 1)) ? 3 : 2);
            // A stash opens to its files as a file to its hunks.
            case FILE, COMMIT -> depth >= 3;
            case HUNK -> depth != 3; // A file opened by hand shows its hunks.
            default -> true;
        };
    }

    /**
     * The node around the one line heads or is in: a file's section, a
     * hunk's file; null if there is none.
     */
    public StatusLine parent(StatusLine line) {
        final StatusLine head = StatusLine.head(line);
        final String key = head != null ? head.key() : line.key();
        final boolean file = (head != null ? head : line).kind() == Kind.FILE;
        for (Line l = line.previous(); l != null; l = l.previous())
            if (l instanceof StatusLine s && s.isExpandable()
            // A file's under a changelist, the list's heading above it.
                    && (key.startsWith(s.key() + "|") || file && s.kind() == Kind.LIST))
                return s;
        return null;
    }

    /** Opens or closes the node line heads or is in. False if there is none. */
    public boolean toggle(StatusLine line) {
        final StatusLine head = StatusLine.head(line);
        if (head == null || !head.isExpandable())
            return false;
        setExpanded(head, !isExpanded(head.key(), head.kind()));
        return true;
    }

    public void setExpanded(StatusLine head, boolean open) {
        expanded.put(head.key(), open);
        final boolean unread = head.kind() == Kind.FILE && head.file() != null && head.file().diff() == null
                || head.stash() != null && head.stash().files() == null;
        if (open && unread) {
            diffsRead.remove(head.key());
            render(); // "Reading the diff..." until it is read.
            refresh();
        } else
            render();
    }

    /**
     * Opens nodes this deep and closes the rest: 1 shows the sections, 2
     * their files, 3 the files' hunks, 4 the hunks' lines.
     */
    public void setDepth(int depth) {
        this.depth = Math.max(1, Math.min(4, depth));
        expanded.clear();
        if (this.depth >= 3)
            refresh(); // The diffs of every file.
        render();
    }

    public int getDepth() {
        return depth;
    }

    // Showing.

    // The caret's place in a window, as keys, to find again after a render.
    private record Place(String dotKey, int offset, String topKey, int dotIndex, int topIndex, String markKey,
            int markOffset) {}

    /** Lays the lines out again from what was read, keeping each window's caret on its thing. */
    void render() {
        render(Set.of(), Drop.GONE);
    }

    // As render, the diffs of the files keyed asked just read; a pick it
    // drops said so, why, unless null.
    private void render(Set<String> asked, Drop why) {
        // The files, and the diffs' hunks' ids, looked up once, for the lines and the picks.
        picks.newRead();
        final List<StatusRenderer.Part> parts = new ArrayList<>();
        for (VcsRepository r : repositories) {
            final StatusData d = data.get(r);
            if (d != null)
                parts.add(
                    new StatusRenderer.Part(r,
                            d,
                            repositories.size() > 1 ? Repositories.colorIndex(r, repositories) : -1,
                            listed(r, d)));
        }
        if (parts.isEmpty())
            return;
        knowLists();
        final List<StatusLine> lines = StatusRenderer.render(parts, this::isExpanded, diffsRead::contains, picks::ids);
        for (StatusTree t : trees.all())
            t.update();
        followAgain(StatusDiffBuffer.Follow.REDRAWN);

        final List<String> oldKeys = new ArrayList<>();
        for (Line l = getFirstLine(); l != null; l = l.next())
            oldKeys.add(l instanceof StatusLine s ? s.key() : null);
        final Map<Editor, Place> places = new HashMap<>();
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this && ed.getDot() != null)
                places.put(ed, place(ed));

        empty();
        withWriteLock(() -> {
            for (StatusLine l : lines)
                appendLine(l);
            renumber();
        });
        invalidate();
        // What each repository has in progress, finished by links after its line.
        final List<VirtualText> finishing = new ArrayList<>();
        for (StatusLine l : lines)
            if (l.kind() == StatusLine.Kind.MESSAGE && l.key().endsWith(StatusRenderer.PROGRESS))
                finishing.add(VirtualText.eol(l, FinishCommands.links(this, l.repository())));
        getVirtualText().set(FinishCommands.OWNER, finishing);
        // What is picked, less what is gone.
        final int dropped = picks.retain(this, asked);
        showPicks();
        if (dropped > 0 && why != null)
            for (Editor ed : Editor.getEditorList())
                if (ed.getBuffer() == this)
                    ed.status(
                        dropped == 1 ? "A pick was dropped: " + why.one : dropped + " picks were dropped: " + why.many);

        final Map<String, StatusLine> byKey = new HashMap<>();
        for (StatusLine l : lines)
            byKey.put(l.key(), l);
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() != this)
                continue;
            final Place p = places.get(ed);
            final Line dot = p == null ? lines.get(0) : findDot(byKey, oldKeys, p.dotKey(), p.dotIndex(), lines.get(0));
            final Line top = p == null ? lines.get(0) : find(byKey, oldKeys, p.topKey(), p.topIndex(), lines.get(0));
            ed.setTopLine(top);
            ed.setDot(
                dot,
                p != null && dot instanceof StatusLine s && s.key().equals(p.dotKey())
                        ? Math.min(p.offset(), dot.length())
                        : 0);
            final StatusLine mark = p == null || p.markKey() == null ? null : byKey.get(p.markKey());
            ed.setMark(mark == null ? null : new Position(mark, Math.min(p.markOffset(), mark.length())));
            ed.moveCaretToDotCol();
            ed.setUpdateFlag(REFRAME | REPAINT);
            ed.updateDisplay();
        }
        updateToolbars();
    }

    private static Place place(Editor ed) {
        final Line dot = ed.getDotLine();
        final Line top = ed.getDisplay().getTopLine();
        final Position mark = ed.getMark();
        return new Place(dot instanceof StatusLine s ? s.key() : null,
                ed.getDotOffset(),
                top instanceof StatusLine t ? t.key() : null,
                dot.lineNumber(),
                top == null ? 0 : top.lineNumber(),
                mark != null && mark.getLine() instanceof StatusLine m ? m.key() : null,
                mark == null ? 0 : mark.getOffset());
    }

    /**
     * Where the caret goes when its line is gone. Inside a node now closed,
     * the node's heading; else, as when a file is staged away, the next
     * thing still there, or the one before.
     */
    private static Line findDot(
            Map<String, StatusLine> byKey,
            List<String> oldKeys,
            String key,
            int index,
            Line first) {
        if (key == null || byKey.containsKey(key))
            return find(byKey, oldKeys, key, index, first);
        StatusLine around = null;
        for (StatusLine l : byKey.values())
            if (key.startsWith(l.key() + "|") && (around == null || l.key().length() > around.key().length()))
                around = l;
        if (around != null && around.isExpandable() && !around.isOpen())
            return around;
        for (int i = index + 1; i < oldKeys.size(); i++) {
            final String k = oldKeys.get(i);
            if (k != null && !k.contains("|blank|") && byKey.containsKey(k))
                return byKey.get(k);
        }
        return find(byKey, oldKeys, key, index, first);
    }

    // The line with key, else the nearest before where it was that is still there.
    private static Line find(Map<String, StatusLine> byKey, List<String> oldKeys, String key, int index, Line first) {
        if (key != null && byKey.containsKey(key))
            return byKey.get(key);
        for (int i = Math.min(index, oldKeys.size() - 1); i >= 0; i--) {
            final String k = oldKeys.get(i);
            if (k != null && byKey.containsKey(k))
                return byKey.get(k);
        }
        return first;
    }

    // The diff beside it.

    /** The diff beside it, or null. */
    StatusDiffBuffer getDiff() {
        return diff;
    }

    /** Opens the diff beside it, across frame when there is one. */
    void openDiff(Frame frame) {
        if (diff != null || disposed)
            return;
        diff = new StatusDiffBuffer(this);
        diff.addGutterColumn(new StatusPickColumn(this));
        followed = null;
        pending = null;
        if (sideBySide)
            sides = newSides();
        if (commitAt(Editor.windowShowing(this)) != null)
            commits = new CommitDetails(this, sideBySide);
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
        if (frame != null)
            frame.showView(this, this);
        followCaret();
    }

    /** Closes the diff beside it: the windows from before it come back. */
    void closeDiff(Frame frame) {
        closeDiff(frame, false);
    }

    /**
     * Closes the diff beside it, back to the status: the windows from before
     * it, the focused one showing the status if none of them does.
     */
    void closeDiffToStatus(Frame frame) {
        if (before != null) {
            boolean has = false;
            for (Window w : WindowLayout.windows(before))
                has |= w.buffer() == this;
            if (!has)
                before = WindowLayout.showing(before, this);
        }
        closeDiff(frame);
    }

    // Closing too, the status is left out of the windows from before.
    private void closeDiff(Frame frame, boolean closing) {
        if (diff == null)
            return;
        final StatusDiffBuffer d = diff;
        final CompareBuffer c = sides != null ? sides.comparison() : null;
        follow.stop();
        final CommitDetails details = commits;
        final List<Buffer> parts = new ArrayList<>(List.of(d));
        if (c != null)
            parts.addAll(List.of(c, c.getOld()));
        if (details != null)
            for (Buffer b : details.panes())
                if (!parts.contains(b))
                    parts.add(b);
        if (closing)
            parts.add(this);
        // Its children the details' panes still, as the frame lays them out.
        if (frame != null)
            frame.leaveView(this, parts);
        commits = null;
        diff = null;
        sides = null;
        before = null;
        // With no windows from before, the diff's own shows the status.
        for (Buffer part : parts)
            if (part instanceof ViewBuffer v && part != this)
                for (Editor ed : v.windows())
                    ed.activate(this);
        if (Editor.getBufferList().contains(d))
            d.kill();
        if (c != null)
            c.close(null);
        if (details != null)
            details.close();
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
    }

    @Override
    public void afterCommand(Editor editor) {
        if (diff != null && editor.getDot() != null && editor.getDotLine() instanceof StatusLine s) {
            final StatusLine c = commitOf(s);
            final String target = c != null ? commitKey(c) : StatusDiffBuffer.target(s);
            if (target != null && !target.equals(followed))
                follow.restart();
        }
    }

    // The diff beside to follow the caret again soon, for why, or for a
    // stronger reason already asked.
    private void followAgain(StatusDiffBuffer.Follow why) {
        if (diff == null)
            return;
        if (pending == null || why.compareTo(pending) > 0)
            pending = why;
        follow.restart();
    }

    // The diff beside shows what the caret of a window on it is on: at its
    // hunk when that is new, else where it was.
    void followCaret() {
        final StatusDiffBuffer.Follow why = pending;
        pending = null;
        if (diff == null)
            return;
        final Editor ed = Editor.windowShowing(this);
        final StatusLine s = ed != null && ed.getDot() != null && ed.getDotLine() instanceof StatusLine l ? l : null;
        final StatusLine c = s == null ? null : commitOf(s);
        if (c != null) {
            followCommit(c);
            return;
        }
        final String target = s == null ? null : StatusDiffBuffer.target(s);
        // A file at the caret: its diff in place of a commit's details.
        if (target != null && commits != null)
            leaveCommit();
        if (target == null) {
            // No file at the caret: the diff keeps its own, read again with
            // the status, or cleared once its section no longer has it.
            if (why != null) {
                final StatusLine own = fileLine(diff.fileKey());
                if (own != null)
                    diff.follow(own, why);
                else {
                    followed = null;
                    diff.none();
                }
            }
            return;
        }
        final boolean moved = !target.equals(followed) || diff.item() == null;
        if (!moved && why == null)
            return;
        followed = target;
        diff.follow(s, moved ? StatusDiffBuffer.Follow.MOVED : why);
    }

    // The file's line keyed so, or null.
    private StatusLine fileLine(String key) {
        if (key == null)
            return null;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == StatusLine.Kind.FILE && s.key().equals(key))
                return s;
        return null;
    }

    // Side by side.

    /** The comparison showing the diff side by side, or null. */
    CompareBuffer getCompare() {
        return sides != null ? sides.comparison() : null;
    }

    /**
     * =: the diff beside opened as it was last shown, or else switched
     * between unified and side by side across frame. The window focused
     * stays the status, or the diff in its new form, at the same line of the
     * file: a removed line's on the old side, the rest on the new.
     */
    @Override
    public void diffView(Frame frame, Buffer focus) {
        if (diff == null) {
            openDiff(frame);
            return;
        }
        // Open but not laid out, as with its window closed by hand: laid out again as it is.
        final boolean shown = isDiffShown(frame);
        if (!shown && frame != null) {
            frame.showView(this, focus);
            return;
        }
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
        if (commits != null) {
            // The file's diff takes it up as it comes back.
            sideBySide = !commits.isSideBySide();
            commits.toggleSides(frame, this, focus);
            return;
        }
        sideBySide = sides == null;
        if (sideBySide) {
            sides = newSides();
            sides.replace(frame, this, focus, diff, diffFile());
            diffShown(diff.repository(), diff.section(), diff.item(), StatusDiffBuffer.Follow.MOVED);
            return;
        }
        final SideBySide was = sides;
        sides = null;
        was.restore(frame, this, focus, diff, diffFile()).run();
    }

    // Picking.

    private final StatusPicks picks = new StatusPicks();
    private final RowPicks comparedPicks = new RowPicks(this, this::comparedOf);
    // Whether picks were last shown, to repaint once when the last goes.
    private boolean picksShown;
    // The files partly picked, for a refresh to read their diffs, open or not.
    private volatile Set<String> partlyPicked = Set.of();
    // The keys of the unstaged files whose changes are kept in changelists
    // one by one, whose diffs a read takes to sort them.
    private volatile Set<String> listedKeys = Set.of();

    /** Its repositories' changelists changed: its lines shown again, its files sorted again. */
    void listsChanged() {
        render(Set.of(), null);
    }

    @Override
    public void listsChanged(VcsRepository r) {
        if (repositories.contains(r))
            listsChanged();
    }

    /** r's lists, if the status sorts its work tree's changes into them: there is one besides the default. */
    RepositoryLists changelists(VcsRepository r) {
        return RepositoryLists.shown(r);
    }

    // The repositories whose changelists it shows; read with their work
    // trees' changes whitespace and all.
    private volatile Set<VcsRepository> listing = Set.of();
    // The last sorting of each repository's files: of which lists, and of
    // which read.
    private final Map<VcsRepository, Listed> lastListed = new HashMap<>();

    private record Listed(Changelists lists, StatusData data, ListedChanges sorted) {}

    // r's unstaged files, as d has them, in its changelists; null if it
    // doesn't sort them. Those kept one change at a time whose diffs
    // aren't read, read.
    private ListedChanges listed(VcsRepository r, StatusData d) {
        final RepositoryLists lists = changelists(r);
        if (lists == null)
            return null;
        final Changelists c = lists.lists();
        final Listed last = lastListed.get(r);
        if (last != null && last.lists() == c && last.data() == d)
            return last.sorted();
        final List<FileItem> files = files(r, StatusRenderer.UNSTAGED);
        final Set<String> keys = keysOf(r, c);
        for (FileItem f : files) {
            final String key = StatusRenderer.fileKey(r, StatusRenderer.UNSTAGED, f.path());
            if (keys.contains(key) && f.diff() == null && !diffsRead.contains(key)) {
                SwingUtilities.invokeLater(this::refresh);
                break;
            }
        }
        final ListedChanges sorted = ListedChanges.of(c, files, picks::ids);
        lastListed.put(r, new Listed(c, d, sorted));
        return sorted;
    }

    // The keys of r's files c keeps change by change, whose diffs a read takes.
    private static Set<String> keysOf(VcsRepository r, Changelists c) {
        final Set<String> out = new HashSet<>();
        for (Map.Entry<String, Changelists.Assignment> e : c.files().entrySet())
            if (e.getValue().whole() == null)
                out.add(StatusRenderer.fileKey(r, StatusRenderer.UNSTAGED, e.getKey()));
        return out;
    }

    // Which repositories' lists it shows, and the files whose diffs they
    // want read, as they are now: read again if a repository's lists now
    // show while whitespace is ignored, as they take the full diff.
    private void knowLists() {
        final Set<String> keys = new HashSet<>();
        final Set<VcsRepository> shown = new HashSet<>();
        for (VcsRepository r : repositories) {
            final RepositoryLists lists = changelists(r);
            if (lists != null) {
                shown.add(r);
                keys.addAll(keysOf(r, lists.lists()));
            }
        }
        listedKeys = Set.copyOf(keys);
        final boolean more = !listing.containsAll(shown);
        listing = Set.copyOf(shown);
        if (more && readIgnoring != Whitespace.NONE)
            refresh();
    }

    // The owner of the "2/5" after a file partly picked.
    private static final String PICKS = "vcs.status.picks";

    /** What is picked in it. */
    StatusPicks picks() {
        return picks;
    }

    /** Its picks in the side-by-side comparison. */
    RowPicks comparedPicks() {
        return comparedPicks;
    }

    /** Picks each of ls that can be, or, if each is picked, unpicks them; and shows it. */
    void pick(List<StatusLine> ls) {
        picks.toggle(ls);
        showPicks();
    }

    /** Picks what ls show if on, else unpicks it, and shows it. */
    void pick(List<StatusLine> ls, boolean on) {
        picks.set(ls, on);
        showPicks();
    }

    /** Unpicks everything, and shows it. */
    boolean unpickAll() {
        if (picks.isEmpty())
            return false;
        picks.clear();
        showPicks();
        return true;
    }

    /** Unpicks those of keys still picked, and shows it. */
    void unpick(Set<String> keys) {
        if (keys.isEmpty())
            return;
        picks.remove(keys);
        showPicks();
    }

    /**
     * Shows what is picked: after a file partly picked, how many of its
     * hunks are; the gutter repainted, its column there only while
     * something is picked.
     */
    void showPicks() {
        // Nothing picked now nor before: nothing to show or repaint.
        if (picks.isEmpty() && !picksShown)
            return;
        picksShown = !picks.isEmpty();
        final List<VirtualText> counts = new ArrayList<>();
        if (!picks.isEmpty())
            for (Line l = getFirstLine(); l != null; l = l.next())
                if (l instanceof StatusLine s) {
                    final int[] n = picks.counts(s);
                    if (n != null)
                        counts.add(VirtualText.eol(s, List.of(VirtualText.Run.of("  " + n[0] + "/" + n[1]))));
                }
        getVirtualText().set(PICKS, counts);
        // A file newly partly picked whose diff is unread, as one closed
        // here picked beside: read again, its diff with it.
        final Set<String> was = partlyPicked;
        partlyPicked = picks.partlyPicked();
        if (!was.containsAll(partlyPicked) && picks.unread(this))
            refresh();
        repaint();
        if (diff != null)
            diff.repaint();
        if (sides != null) {
            sides.comparison().repaint();
            sides.comparison().getOld().repaint();
        }
        // The signs of the files picked now or before, in their buffers.
        final List<StatusPicks.Pick> now = picks.paths();
        final List<StatusPicks.Pick> touched = new ArrayList<>(signsPicked);
        touched.addAll(now);
        signsPicked = now;
        signsChanged(
            u -> touched.stream().anyMatch(f -> f.repository() == u.getRepository() && f.path().equals(u.getPath())));
    }

    // The files picked as their signs last showed.
    private List<StatusPicks.Pick> signsPicked = List.of();

    // The signs of those of its files in their buffers which shows again.
    private void signsChanged(Predicate<SignsUpdater> which) {
        // The views of buffers closed, gone.
        final org.armedbear.j.BufferList open = Editor.getBufferList();
        for (var it = views.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<Buffer, FileView> e = it.next();
            if (!open.contains(e.getKey())) {
                viewed.remove(e.getValue().key);
                it.remove();
            }
        }
        for (Buffer b : open) {
            final SignsUpdater u = b.getSignsUpdater();
            if (u != null && repositories.contains(u.getRepository()) && which.test(u))
                u.statusChanged();
        }
    }

    // When the read shown began, by j's clock.
    private long shownReadAt;

    /**
     * Whether buffer's file was saved, or reloaded, since the read shown
     * began: its diff, as read, may not be the file's now.
     */
    boolean savedSinceRead(Buffer buffer) {
        final long at = buffer == null ? 0 : buffer.getFileChangedAt();
        return at != 0 && at >= shownReadAt;
    }

    /**
     * Of r's file at path, the rows, from 0, as the working tree has them,
     * of its unstaged changes picked; null for none.
     */
    StatusView.Picked pickedRows(VcsRepository r, String path) {
        final FileItem item = unstaged(r, path);
        return item == null ? null : picks.rows(StatusRenderer.fileKey(r, UNSTAGED, path), item);
    }

    /** r's file at path, as its unstaged changes' picks are of it; null with none, or none read. */
    RowPicks.Of unstagedOf(VcsRepository r, String path) {
        final FileItem item = unstaged(r, path);
        if (item == null || item.diff() == null)
            return null;
        // The last asked for, kept: a file's column asks as it paints.
        if (lastUnstaged == null || lastUnstaged.item() != item || lastUnstaged.repository() != r)
            lastUnstaged = new RowPicks.Of(r, UNSTAGED, item, StatusRenderer.fileKey(r, UNSTAGED, path));
        return lastUnstaged;
    }

    private RowPicks.Of lastUnstaged;

    // Its views of its files' buffers, those closed dropped as it tells
    // the signs; and the keys of their files' lines, whose diffs each read
    // takes, so that their signs are by git's diff.
    private final Map<Buffer, FileView> views = new HashMap<>();
    private final Set<String> viewed = ConcurrentHashMap.newKeySet();

    @Override
    public StatusView viewOf(SignsUpdater u) {
        if (disposed || !repositories.contains(u.getRepository()))
            return null;
        final FileView v = views.get(u.getBuffer());
        if (v != null && v.r == u.getRepository() && v.path.equals(u.getPath()))
            return v;
        if (v != null)
            viewed.remove(v.key);
        final FileView made = new FileView(u.getBuffer(), u.getRepository(), u.getPath());
        views.put(u.getBuffer(), made);
        // Its diff read, if the status has the file and hasn't.
        if (viewed.add(made.key)) {
            final FileItem item = unstaged(made.r, made.path);
            if (item != null && item.diff() == null)
                refresh();
        }
        return made;
    }

    /**
     * Its view of r's file at path, shown in buffer: git's diff of its
     * unstaged changes, as read, its picks, and its boxes. The diff and the
     * picks none while the buffer is edited, or saved since the read.
     */
    private final class FileView implements StatusView {
        final Buffer buffer;
        final VcsRepository r;
        final String path;
        // Its line's key in the status.
        final String key;
        final RowPicks rows;
        private EditorPickColumn column;

        FileView(Buffer buffer, VcsRepository r, String path) {
            this.buffer = buffer;
            this.r = r;
            this.path = path;
            key = StatusRenderer.fileKey(r, UNSTAGED, path);
            rows = new RowPicks(VcsStatusBuffer.this, () -> current() ? unstagedOf(r, path) : null);
        }

        // Whether its diff, as read, may be the buffer's.
        private boolean current() {
            return !disposed && !buffer.isModified() && !savedSinceRead(buffer);
        }

        @Override
        public List<Diff.Edit> edits() {
            final RowPicks.Of of = current() ? unstagedOf(r, path) : null;
            if (of == null)
                return null;
            final List<Diff.Edit> out = new ArrayList<>();
            for (Hunk h : of.item().diff().hunks())
                out.addAll(h.edits());
            return out;
        }

        @Override
        public StatusView.Picked picked() {
            return current() ? pickedRows(r, path) : null;
        }

        @Override
        public boolean hasPicks() {
            return !disposed && picks.has(key);
        }

        @Override
        public org.armedbear.j.GutterColumn column() {
            if (column == null)
                column = new EditorPickColumn(buffer, rows);
            return column;
        }

        @Override
        public PickState stateAt(int row) {
            return rows.changeState(row);
        }

        @Override
        public void pick(Editor editor, int from, int to, boolean lines, Runnable then) {
            pickUnstagedAt(new PendingPick(editor, this, editor.getDotLineNumber(), from, to, lines, then), null);
        }
    }

    // The section of the work tree's changes, as git's backend names it.
    private static final String UNSTAGED = "unstaged";

    // r's file at path as the unstaged section has it, or null.
    private FileItem unstaged(VcsRepository r, String path) {
        return picks.item(this, new StatusPicks.Pick(r, UNSTAGED, path));
    }

    // A pick from a file's buffer, view's, the caret at row caret: of the
    // change at row from, or of the changed lines in rows from to to if
    // lines; waiting on a read of the status or the file's diff, said in
    // editor, then then run if editor still shows the file there.
    private record PendingPick(Editor editor, FileView view, int caret, int from, int to, boolean lines,
            Runnable then) {}

    private final List<PendingPick> pendingPicks = new ArrayList<>();
    // The keys of files whose diffs a read takes for a pick waiting on them.
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();

    /**
     * Picks the unstaged changes of the file editor shows, as it is saved,
     * at rows from to to, as its view picks them; its diff read first if
     * the status hasn't, and the status itself.
     */
    void pickUnstagedAt(Editor editor, int from, int to, boolean lines, Runnable then) {
        final SignsUpdater u = editor.getBuffer().getSignsUpdater();
        final StatusView v = u == null ? null : viewOf(u);
        if (v != null)
            v.pick(editor, from, to, lines, then);
    }

    // p picked, or left waiting on a read, read the read just shown, whose
    // diffs it took; null for none yet. A file saved since the read waits
    // for the next, its diff then the file's.
    private void pickUnstagedAt(PendingPick p, Set<String> read) {
        final FileView v = p.view();
        final String fk = StatusRenderer.fileKey(v.r, UNSTAGED, v.path);
        if (data.get(v.r) == null || savedSinceRead(v.buffer)) {
            pendingPicks.add(p);
            refresh();
            return;
        }
        final FileItem item = unstaged(v.r, v.path);
        if (item != null && item.diff() == null) {
            if (read == null || !read.contains(fk)) {
                wanted.add(fk);
                pendingPicks.add(p);
                refresh();
                return;
            }
            wanted.remove(fk);
            done(p, "Couldn't read " + v.path + "'s diff");
            return;
        }
        wanted.remove(fk);
        done(p, item == null ? "No unstaged change here to pick" : pick(p, fk, item));
    }

    // p done, said; its then run if its editor is still where it was.
    private void done(PendingPick p, String said) {
        if (p.editor().getBuffer() != p.view().buffer || p.editor().getDotLineNumber() != p.caret())
            return;
        p.editor().status(said);
        p.then().run();
    }

    // p's change or lines of item, fk its line's key, picked or unpicked: what is said.
    private String pick(PendingPick p, String fk, FileItem item) {
        final FileView v = p.view();
        final Map<String, BitSet> idx = p.lines() ? v.rows.signs(p.from(), p.to()) : v.rows.changeAt(p.from());
        if (idx == null)
            return "No unstaged change here to pick";
        picks.toggleLines(fk, v.r, UNSTAGED, item, idx);
        showPicks();
        final String what = p.lines() ? " lines of " : " a change of ";
        return (picks.linesState(fk, idx) == PickState.NONE ? "Unpicked" : "Picked") + what + v.path + " in the status";
    }

    /**
     * The file the comparison shows, as the diff beside has it, if its
     * hunks can be picked: read, not a conflict's, and its lines git's,
     * not a buffer's with edits unsaved; else null.
     */
    FileItem comparedItem() {
        final FileItem item = sides == null || diff == null ? null : diff.item();
        if (item == null || item.diff() == null || item.change().isConflicted() || !sides.shows(diffFile()))
            return null;
        return comparedEdited() || compared != null && savedSinceRead(compared) ? null : item;
    }

    // The buffer the comparison's new side follows, or null.
    private Buffer compared;

    // The compared file, as its picks are of it, or null; the last kept,
    // as side by side asks as it paints each row.
    private RowPicks.Of comparedOf() {
        final FileItem item = comparedItem();
        if (item == null)
            return null;
        if (lastCompared == null || lastCompared.item() != item || !lastCompared.key().equals(diffFile()))
            lastCompared = new RowPicks.Of(diff.repository(), diff.section(), item, diffFile());
        return lastCompared;
    }

    private RowPicks.Of lastCompared;

    /** Whether the new side is a buffer's with edits unsaved. */
    boolean comparedEdited() {
        return sides != null && compared != null && sides.comparison().isFollowing() && compared.isModified();
    }

    /** Whether the new side is a buffer's saved since the read shown, its diff then not yet read. */
    boolean comparedSaved() {
        return sides != null && compared != null && savedSinceRead(compared);
    }

    // A commit's details.

    /** The commit at editor's caret, or the stash the caret is in; null for none. */
    private static StatusLine commitAt(Editor editor) {
        return editor != null && editor.getDot() != null && editor.getDotLine() instanceof StatusLine s
                ? commitOf(s)
                : null;
    }

    /** The commit line is, or the stash it is in, the line of its files and diffs; null for none. */
    static StatusLine commitOf(StatusLine line) {
        if (line.kind() == StatusLine.Kind.COMMIT)
            return line.commit() != null ? line : null;
        if (!StatusData.STASHES.equals(line.section()))
            return null;
        for (Line l = line.previous(); l != null; l = l.previous())
            if (l instanceof StatusLine s && s.kind() == StatusLine.Kind.COMMIT)
                return s.stash() != null && line.key().startsWith(s.key() + "|") ? s : null;
        return null;
    }

    // What following a commit's line shows, as a key.
    private static String commitKey(StatusLine line) {
        return line.repository().root() + "|commit|" + line.commit().hash();
    }

    // The details of the commit at line, in place of the diff: read once,
    // then kept.
    private void followCommit(StatusLine line) {
        final String key = commitKey(line);
        if (key.equals(followed) && commits != null)
            return;
        followed = key;
        if (commits == null)
            layOut(() -> commits = new CommitDetails(this, sideBySide));
        final CommitDetails details = commits;
        final LogCommit known = commitsRead.get(key);
        if (known != null) {
            details.show(known);
            return;
        }
        final VcsRepository r = line.repository();
        final String hash = line.commit().hash();
        Background.then("vcs status commit", () -> {
            final List<LogCommit> read = r.backend().readLog(r, LogQuery.ALL.withRevisions(List.of(hash)), 0, 1);
            return read == null || read.isEmpty() ? null : read.get(0);
        }, c -> {
            if (c == null || commits != details || !key.equals(followed))
                return;
            commitsRead.put(key, c);
            details.show(c);
        });
    }

    // The commits read forgotten, as their refs may have moved: the one
    // shown is read again when the caret is next followed.
    private void forgetCommits() {
        commitsRead.clear();
        if (commits != null)
            followed = null;
    }

    // Back to the file's diff from a commit's details, unified or side by
    // side as = last left them.
    private void leaveCommit() {
        final CommitDetails was = commits;
        followed = null;
        layOut(() -> {
            commits = null;
            if (sideBySide && sides == null)
                sides = newSides();
            else if (!sideBySide && sides != null) {
                sides.close();
                sides = null;
            }
        });
        was.close();
    }

    // change made to its panes, then its view laid out again, in the frame
    // showing it, the focus kept. Shown is as of before: the new panes have
    // no windows yet.
    private void layOut(Runnable change) {
        final Editor ed = Editor.windowShowing(this);
        final Frame frame = ed == null ? null : ed.getFrame();
        final boolean shown = frame != null && frame.shownView() == this;
        change.run();
        if (!shown)
            return;
        final Editor current = frame.getCurrentEditor();
        frame.layOutView(this, current != null ? current.getBuffer() : this);
    }

    @Override
    public CommitDetails details() {
        return commits;
    }

    @Override
    public VcsRepository repositoryOf(LogCommit c) {
        return c.repository() != null ? c.repository() : repositories.get(0);
    }

    @Override
    public LogScope getScope() {
        return LogScope.of(repositories);
    }

    @Override
    public boolean isStash(LogCommit c) {
        for (StatusData d : data.values())
            for (Section s : d.sections())
                if (s.id().equals(StatusData.STASHES))
                    for (Item i : s.items())
                        if (i instanceof StashItem st && st.hash().equals(c.id()))
                            return true;
        return false;
    }

    @Override
    public int laneColorOf(LogCommit c) {
        return -1;
    }

    @Override
    public String getTimeStyle() {
        return TimeStyle.valid(Editor.preferences().getStringProperty(Property.VCS_LOG_TIME_STYLE), TimeStyle.AGE);
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void leave(Frame frame) {
        closeDiffToStatus(frame);
    }

    /**
     * The new side's line, from 1, at the caret of editor in a side, to
     * visit, given to then; nothing, saying why, for none. An unmerged
     * file's sides are ours and theirs: a side's line is the working
     * tree's by a diff of the two, off the event thread, as git's combined
     * diff leaves out what merged cleanly.
     */
    void comparedLineAt(Editor editor, IntConsumer then) {
        if (sides == null)
            return;
        final FileItem item = diff == null ? null : diff.item();
        if (item == null || !item.change().isConflicted() || !sides.shows(diffFile())) {
            final int n = sides.lineAt(editor, diffFile());
            if (n >= 0)
                then.accept(n);
            return;
        }
        final List<String> side = lines(editor.getBuffer());
        final int at = editor.getDotLineNumber();
        final Path path = diff.repository().root().resolve(item.path());
        final File f = File.getInstance(path.toString());
        final Buffer open = f == null ? null : Editor.getBufferList().findBuffer(f);
        final List<String> buffered = open != null && open.isLoaded() ? lines(open) : null;
        Background.then("vcs compared line", () -> {
            List<String> working = buffered;
            if (working == null)
                try {
                    // Malformed bytes replaced, not refused: a line is a line.
                    working = new String(java.nio.file.Files.readAllBytes(path), SideBySide.charset()).lines().toList();
                }
                catch (IOException e) {
                    return -1;
                }
            return VcsStatusMode.sideToWorking(side, working, at);
        }, n -> {
            if (n == null || n < 0)
                editor.status("Can't read " + item.path());
            else
                then.accept(n);
        });
    }

    private static List<String> lines(Buffer b) {
        final List<String> out = new ArrayList<>();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }

    /**
     * The open buffer a comparison of item follows as it is edited, or
     * null: not for what is staged or in a conflict, which have no one text
     * staged to follow, nor for a file deleted from the working tree, which
     * is not its buffer's text. The disk looked at only for an open buffer.
     */
    private static Buffer followed(VcsRepository r, String section, FileItem item) {
        if ("staged".equals(section)
                || StatusData.UNMERGED.equals(section)
                || item.change().unstaged() == FileChange.Kind.DELETED)
            return null;
        final File f = File.getInstance(r.root().resolve(item.path()).toString());
        final Buffer b = Editor.getBufferList().findBuffer(f);
        if (b == null
                || !b.isLoaded()
                || b.getType() != Buffer.TYPE_NORMAL
                || b.getFileType() == FILETYPE_BINARY
                || !f.isFile())
            return null;
        return b;
    }

    // The file the unified diff shows, and its key, made once a file, as
    // a side's every row asks for it.
    private FileItem diffFileOf;
    private String diffFileKey;

    /** The key of the file the unified diff shows, or null. */
    String diffFile() {
        final FileItem item = diff == null ? null : diff.item();
        if (item != diffFileOf) {
            diffFileOf = item;
            diffFileKey = item == null ? null : fileKey(diff.repository(), diff.section(), item);
        }
        return diffFileKey;
    }

    private static String fileKey(VcsRepository r, String section, FileItem item) {
        return StatusRenderer.fileKey(r, section, item.path());
    }

    /** Whether the comparison shows the file the unified diff does, read. */
    boolean comparesDiffFile() {
        return sides != null && sides.shows(diffFile());
    }

    // The side-by-side form, part of this, its keys the diff's.
    private SideBySide newSides() {
        final SideBySide ss = new SideBySide(this, km -> {
            km.mapKey('=', "vcsStatusDiffView");
            km.mapKey('q', "vcsStatusDiff");
            km.mapKey(KeyEvent.VK_ENTER, 0, "vcsStatusVisit");
            VcsStatusMode.pickKeys(km);
        });
        ss.comparison().alignBy(this::gitAlignment);
        ss.comparison().markPickedBy(comparedPicks::isPicked);
        // In vim, ; repeats an f or t in the source shown; on a selection it picks.
        ss.comparison().embedModal(km -> km.unmapKey(';'));
        ss.comparison().embedVisual(VcsStatusMode::pickKeys);
        // On the new side, as IntelliJ has them, but for what it can't show.
        ss.comparison().addGutterColumn(new ComparedPickColumn(this, false));
        ss.comparison().getOld().addGutterColumn(new ComparedPickColumn(this, true));
        return ss;
    }

    /**
     * The diff beside shows item, of r's section, for why, or nothing for
     * null: side by side, the comparison shows it too. What is staged is
     * against HEAD; an unmerged file, ours against theirs, as the index
     * holds them; else, the file's buffer if it is open, followed as
     * vcsDiff follows it, or else the file, against what is staged.
     */
    void diffShown(VcsRepository r, String section, FileItem item, StatusDiffBuffer.Follow why) {
        // Read again after a write: its buttons can stage again.
        updateToolbars();
        if (sides == null)
            return;
        final String file = item == null ? null : fileKey(r, section, item);
        final boolean moved = file == null || !sides.isAsked(file);
        final Buffer buffer = item == null ? null : followed(r, section, item);
        // Read again only for a new file, or when the status is and what
        // the comparison follows has changed, as when a file is deleted; a
        // buffer's comparison follows the buffer itself.
        if (!moved && why == StatusDiffBuffer.Follow.REDRAWN && (buffer != null) == sides.comparison().isFollowing()) {
            // The diff read again may be of the comparison's text where the last wasn't.
            sides.comparison().align();
            return;
        }
        final SideBySide ss = sides;
        final int asked = ss.ask(file);
        final CompareBuffer c = ss.comparison();
        compared = null;
        if (item == null) {
            c.follow(null);
            ss.show(asked, SideBySide.plainText(), "", Side.NONE, "No file at the caret", Side.NONE, null);
            return;
        }
        final String path = item.path();
        final File f = File.getInstance(r.root().resolve(path).toString());
        final boolean staged = "staged".equals(section);
        if (buffer != null) {
            compared = buffer;
            if (!moved && c.isFollowing()) {
                c.align();
                return;
            }
            Following.open(buffer, VcsDiffCommands.staged(buffer, r, path), (read, side, alignment) -> {
                if (!ss.isCurrent(asked))
                    return null;
                if (read == null) {
                    // Not read, as while another git holds the index's lock:
                    // read again when the status next is.
                    c.follow(null);
                    ss.forget();
                    ss.show(
                        asked,
                        buffer.getMode(),
                        path + " (not read)",
                        Side.NONE,
                        path + " (buffer)",
                        Side.NONE,
                        null);
                    return null;
                }
                final DiffAlignment hunks = buffer.isModified()
                        ? null
                        : gitAlignment(item, DiffWhitespace.ignored(), readIgnoring, read.side(), side);
                ss.show(
                    asked,
                    buffer.getMode(),
                    read.title(),
                    read.side(),
                    path + " (buffer)",
                    side,
                    hunks != null ? hunks : alignment);
                return c;
            });
            return;
        }
        final Mode mode = SideBySide.modeFor(f.getName());
        final Charset charset = SideBySide.charset();
        final boolean conflicted = StatusData.UNMERGED.equals(section);
        final Whitespace ignored = DiffWhitespace.ignored();
        final Whitespace diffIgnoring = readIgnoring;
        Background.start("vcs status compare", () -> {
            String oldTitle;
            Side oldSide = Side.NONE;
            String newTitle;
            Side newSide = Side.NONE;
            boolean notRead = false;
            try {
                if (conflicted) {
                    // Ours against theirs, as the index holds them.
                    final FileChange.Conflict kind = item.change().conflict();
                    final VcsBackend.ConflictSides read = r.backend().readConflictSides(r, path);
                    final byte[] ours = read == null ? null : read.ours();
                    final byte[] theirs = read == null ? null : read.theirs();
                    oldTitle = path + (read == null ? " (not read)" : sideNote(ours != null, true, kind));
                    newTitle = path + (read == null ? " (not read)" : sideNote(theirs != null, false, kind));
                    notRead = read == null;
                    if (ours != null && Side.isBinary(ours) || theirs != null && Side.isBinary(theirs)) {
                        oldTitle = path + " (binary)";
                        newTitle = path + " (binary)";
                    } else {
                        oldSide = ours == null ? Side.NONE : Side.ofFile(ours, charset);
                        newSide = theirs == null ? Side.NONE : Side.ofFile(theirs, charset);
                    }
                } else {
                    final Bases bases = r.backend().readBases(r, path);
                    final byte[] head = bases == null ? null : bases.head();
                    final byte[] index = bases == null ? null : bases.index();
                    final byte[] base = staged ? head : index;
                    oldTitle = path + baseNote(bases != null, base != null, staged);
                    newTitle = staged ? path + " (staged)" : path;
                    final boolean binary = staged
                            ? head != null && Side.isBinary(head) || index != null && Side.isBinary(index)
                            : base != null && Side.isBinary(base) || isBinary(f);
                    if (binary) {
                        oldTitle = path + " (binary)";
                        newTitle = path + " (binary)";
                    } else {
                        oldSide = base == null ? Side.NONE : Side.ofFile(base, charset);
                        newSide = staged ? index == null ? Side.NONE : Side.ofFile(index, charset) : read(f, charset);
                    }
                }
            }
            catch (RuntimeException e) {
                Log.error(e);
                oldTitle = path + " (not read)";
                newTitle = path;
                notRead = true;
            }
            final DiffAlignment hunks = conflicted ? null : gitAlignment(item, ignored, diffIgnoring, oldSide, newSide);
            final DiffAlignment alignment = hunks != null ? hunks : DiffAlignment.of(oldSide, newSide, ignored);
            final String ot = oldTitle;
            final Side os = oldSide;
            final String nt = newTitle;
            final Side ns = newSide;
            final boolean again = notRead;
            SwingUtilities.invokeLater(() -> {
                if (!ss.isCurrent(asked))
                    return;
                c.follow(null);
                // Not read, as while another git holds the index's lock:
                // read again when the status next is.
                if (again)
                    ss.forget();
                ss.show(asked, mode, ot, os, nt, ns, alignment);
            });
        });
    }

    // The comparison's texts aligned as the file's diff, as the status read
    // it, has them, if it is of them; else null, for its own diff.
    private DiffAlignment gitAlignment(Side oldSide, Side newSide) {
        final FileItem item = comparedItem();
        return item == null ? null : gitAlignment(item, DiffWhitespace.ignored(), readIgnoring, oldSide, newSide);
    }

    // The sides aligned as item's diff, as the status read it, has them,
    // so the comparison's changes are the unified diff's and its picks';
    // null if that diff is unread, read ignoring other whitespace than
    // ignored, or not of these sides.
    private static DiffAlignment gitAlignment(
            FileItem item,
            Whitespace ignored,
            Whitespace read,
            Side oldSide,
            Side newSide) {
        final FileDiff d = item.diff();
        if (d == null || ignored != read)
            return null;
        return SideBySide.ofHunks(d, oldSide, newSide, ignored);
    }

    // Which side of a conflict, or why it has nothing.
    private static String sideNote(boolean there, boolean ours, FileChange.Conflict kind) {
        if (there)
            return ours ? " (ours)" : " (theirs)";
        if (kind == (ours ? FileChange.Conflict.DELETED_BY_US : FileChange.Conflict.DELETED_BY_THEM)
                || kind == FileChange.Conflict.BOTH_DELETED)
            return ours ? " (deleted by us)" : " (deleted by them)";
        return ours ? " (not in ours)" : " (not in theirs)";
    }

    // What the old side is, or why it has nothing.
    private static String baseNote(boolean read, boolean there, boolean staged) {
        if (!read)
            return " (not read)";
        if (!there)
            return staged ? " (not in HEAD)" : " (not in the index)";
        return staged ? " (HEAD)" : " (staged)";
    }

    // Whether the file is not text, as diff FILE judges.
    private static boolean isBinary(File file) {
        final int type = Utilities.getFileType(file);
        return type == FILETYPE_BINARY || type == FILETYPE_ZIP || type == FILETYPE_GZIP || type == FILETYPE_JPEG;
    }

    // The file's text, or none if it is not there or can't be read.
    private static Side read(File file, Charset charset) {
        if (!file.isFile())
            return Side.NONE;
        try {
            return Side.read(file, charset);
        }
        catch (IOException e) {
            Log.debug(e.toString());
            return Side.NONE;
        }
    }

    /** Whether the diff beside is open and laid out across the frame. */
    boolean isDiffShown(Frame frame) {
        return diff != null && frame != null && frame.shownView() == this;
    }

    @Override
    public FrameView getFrameView() {
        return diff != null && !disposed ? this : null;
    }

    @Override
    public List<Buffer> getChildren() {
        if (commits != null)
            return commits.panes();
        final CompareBuffer c = getCompare();
        return diff == null ? List.of() : c != null ? List.of(c, c.getOld()) : List.of(diff);
    }

    // The status at the left, the diff at the right; side by side, the
    // status at the top, the comparison under it.
    @Override
    public WindowLayout.Node layout(Frame frame, Buffer focus) {
        if (commits != null)
            return commits.layout(this, focus);
        final CompareBuffer c = getCompare();
        if (c != null)
            return new Split(false,
                    List.of(Window.showing(this, CompareBuffer.of(focus) != c), c.panes(focus)),
                    List.of(0.5, 0.5));
        return new Split(true,
                List.of(Window.showing(this, focus != diff), Window.showing(diff, focus == diff)),
                List.of(0.45, 0.55));
    }

    @Override
    public WindowLayout.Node before() {
        return before;
    }

    @Override
    public void setBefore(WindowLayout.Node before) {
        this.before = before;
    }

    // The status's commands the diff beside has no keys for, run in the status.
    private static final Set<String> STATUS_ONLY = Set.of(
        "vcsStageAll",
        "vcsUnstageAll",
        "vcsStatusDepth",
        "vcsStatusNextSection",
        "vcsStatusPreviousSection",
        "vcsLogPopup");

    /**
     * Where a command from the toolbar or a menu runs, from from: a diff's
     * in the diff; committing, and what the diff beside has no keys for,
     * in the status; from a side of the comparison, a vcs command it has
     * no key for in the status; the rest, as the editor's own, in from.
     */
    @Override
    public Editor windowFor(Editor from, String command) {
        final Editor diffs = commits != null
                ? CompareCommands.windowFor(from, command, commits.unified(), commits.comparison())
                : CompareCommands.windowFor(from, command, diff, getCompare());
        if (diffs != null)
            return diffs;
        final Editor status = from.getFrame() != null ? from.getFrame().findEditor(this) : null;
        return status != null && runsInStatus(command, from.getBuffer()) ? status : from;
    }

    // Whether command, from a window on from, one of its buffers, runs in the status.
    static boolean runsInStatus(String command, Buffer from) {
        if (!command.startsWith("vcs"))
            return false;
        return command.startsWith("vcsCommit")
                || STATUS_ONLY.contains(command)
                || CompareBuffer.of(from) != null && !CompareCommands.sideRuns(from, command);
    }

    // The diff killed on its own closes as D does.
    @Override
    public boolean partKilled(Buffer part) {
        if (part != diff && part != getCompare() && (commits == null || !commits.has(part)))
            return false;
        final Editor current = Editor.currentEditor();
        closeDiff(current != null ? current.getFrame() : null);
        return true;
    }

    // Closed, the diff beside goes first, and the windows from before come back.
    @Override
    public synchronized void kill() {
        if (diff != null) {
            final Editor current = Editor.currentEditor();
            closeDiff(current != null ? current.getFrame() : null, true);
        }
        super.kill();
    }

    /** The files of a section of a repository, as last read. */
    List<FileItem> files(VcsRepository repository, String section) {
        final List<FileItem> files = new ArrayList<>();
        final StatusData d = data.get(repository);
        if (d != null)
            for (Section s : d.sections())
                if (s.id().equals(section))
                    for (Item item : s.items())
                        if (item instanceof FileItem f)
                            files.add(f);
        return files;
    }
}
