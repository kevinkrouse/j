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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsDiffCommands;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.log.CommitDetails;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogScope;
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
        implements FrameView, DiffWhitespace.Listener, CommitDetails.Host {
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
        follow.setRepeats(false);
        for (VcsRepository r : repositories)
            r.addListener(listener);
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
                final Whitespace ignored = DiffWhitespace.ignored();
                final Map<VcsRepository, StatusData> read = new LinkedHashMap<>();
                final Set<String> diffs = ConcurrentHashMap.newKeySet();
                for (VcsRepository r : repositories) {
                    final String rk = r.root().toString();
                    StatusData d;
                    try {
                        d = r.backend().readStatus(r, (section, path) -> {
                            final String key = rk + "|" + section + "|" + path;
                            if (!isExpanded(key, Kind.FILE))
                                return false;
                            diffs.add(key);
                            return true;
                        }, ignored);
                    }
                    catch (RuntimeException e) {
                        Log.error(e);
                        d = StatusData.failed(e.toString());
                    }
                    read.put(r, d != null ? d : StatusData.failed(r.backend().name() + " has no status"));
                }
                SwingUtilities.invokeLater(() -> show(read, diffs, startedAt, ignored));
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

    private void show(Map<VcsRepository, StatusData> read, Set<String> diffs, long startedAt, Whitespace ignored) {
        if (disposed)
            return;
        readIgnoring = ignored;
        // A file the status has closed may have changed with nothing it shows.
        followAgain(StatusDiffBuffer.Follow.REFRESHED);
        if (startedAt > wroteAt && writing) {
            writing = false;
            updateToolbars();
        }
        // A refresh comes with each save and each return to j's window: most
        // find nothing new, and leave the lines, and the selection, as they are.
        if (getFirstLine() instanceof StatusLine
                && diffsRead.containsAll(diffs)
                && read.entrySet().stream().allMatch(e -> e.getValue().equals(data.get(e.getKey()))))
            return;
        data.putAll(read);
        diffsRead.addAll(diffs);
        render();
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
        wroteAt = Long.MAX_VALUE;
        writing = true;
        ++writes;
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
    Editor reveal(Frame frame, String section, String key) {
        final Editor ed = frame != null ? frame.findEditor(this) : Editor.windowShowing(this);
        if (ed == null)
            return null;
        if (key != null && !isExpanded(section, Kind.SECTION)) {
            expanded.put(section, true);
            render();
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
        for (Line l = line.previous(); l != null; l = l.previous())
            if (l instanceof StatusLine s && s.isExpandable() && key.startsWith(s.key() + "|"))
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
        final List<StatusRenderer.Part> parts = new ArrayList<>();
        for (VcsRepository r : repositories) {
            final StatusData d = data.get(r);
            if (d != null)
                parts.add(
                    new StatusRenderer.Part(r,
                            d,
                            repositories.size() > 1 ? Repositories.colorIndex(r, repositories) : -1));
        }
        if (parts.isEmpty())
            return;
        final List<StatusLine> lines = StatusRenderer.render(parts, this::isExpanded, diffsRead::contains);
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

    /** Side by side, the new side's line, from 1, at editor's caret to visit; -1, saying why, for none. */
    int comparedLineAt(Editor editor) {
        return sides == null ? -1 : sides.lineAt(editor, diffFile());
    }

    /**
     * The open buffer a comparison of item follows as it is edited, or
     * null: not for what is staged or in a conflict, which have no one text
     * staged to follow, nor for a file deleted from the working tree, which
     * is not its buffer's text. The disk looked at only for an open buffer.
     */
    private static Buffer followed(VcsRepository r, String section, FileItem item) {
        if ("staged".equals(section)
                || "unmerged".equals(section)
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

    // The key of the file the unified diff shows, or null.
    private String diffFile() {
        return diff == null || diff.item() == null ? null : fileKey(diff.repository(), diff.section(), diff.item());
    }

    private static String fileKey(VcsRepository r, String section, FileItem item) {
        return r.root() + "|" + section + "|" + item.path();
    }

    /** Whether the comparison shows the file the unified diff does, read. */
    boolean comparesDiffFile() {
        return sides != null && sides.shows(diffFile());
    }

    // The side-by-side form, part of this, its keys the diff's.
    private SideBySide newSides() {
        return new SideBySide(this, km -> {
            km.mapKey('=', "vcsStatusDiffView");
            km.mapKey('q', "vcsStatusDiff");
            km.mapKey(KeyEvent.VK_ENTER, 0, "vcsStatusVisit");
        });
    }

    /**
     * The diff beside shows item, of r's section, for why, or nothing for
     * null: side by side, the comparison shows it too. What is staged is
     * against HEAD; else, the file's buffer if it is open, followed as
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
        if (!moved && why == StatusDiffBuffer.Follow.REDRAWN && (buffer != null) == sides.comparison().isFollowing())
            return;
        final SideBySide ss = sides;
        final int asked = ss.ask(file);
        final CompareBuffer c = ss.comparison();
        if (item == null) {
            c.follow(null);
            ss.show(asked, SideBySide.plainText(), "", Side.NONE, "No file at the caret", Side.NONE, null);
            return;
        }
        final String path = item.path();
        final File f = File.getInstance(r.root().resolve(path).toString());
        final boolean staged = "staged".equals(section);
        if (buffer != null) {
            if (!moved && c.isFollowing())
                return;
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
                ss.show(asked, buffer.getMode(), read.title(), read.side(), path + " (buffer)", side, alignment);
                return c;
            });
            return;
        }
        final Mode mode = SideBySide.modeFor(f.getName());
        final Charset charset = SideBySide.charset();
        final boolean conflicted = "unmerged".equals(section);
        final Whitespace ignored = DiffWhitespace.ignored();
        Background.start("vcs status compare", () -> {
            String oldTitle;
            Side oldSide = Side.NONE;
            String newTitle;
            Side newSide = Side.NONE;
            try {
                final Bases bases = r.backend().readBases(r, path);
                final byte[] head = bases == null ? null : bases.head();
                final byte[] index = bases == null ? null : bases.index();
                final byte[] base = staged ? head : index;
                oldTitle = path + baseNote(bases != null, base != null, staged, conflicted);
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
            catch (RuntimeException e) {
                Log.error(e);
                oldTitle = path + " (not read)";
                newTitle = path;
            }
            final DiffAlignment alignment = DiffAlignment.of(oldSide, newSide, ignored);
            final String ot = oldTitle;
            final Side os = oldSide;
            final String nt = newTitle;
            final Side ns = newSide;
            SwingUtilities.invokeLater(() -> {
                if (!ss.isCurrent(asked))
                    return;
                c.follow(null);
                ss.show(asked, mode, ot, os, nt, ns, alignment);
            });
        });
    }

    // What the old side is, or why it has nothing.
    private static String baseNote(boolean read, boolean there, boolean staged, boolean conflicted) {
        if (!read)
            return conflicted ? " (in a conflict)" : " (not read)";
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
