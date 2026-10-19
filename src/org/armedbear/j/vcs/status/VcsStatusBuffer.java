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
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.Sidebar;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.WindowLayout.Split;
import org.armedbear.j.WindowLayout.Window;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.ViewBuffer;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * The status of one repository, or of a project's several: sections of
 * files and commits, each file's diff shown inline when expanded. It is
 * read again in the background whenever its repositories change, and a
 * refresh keeps the caret on the thing it was on. With the diff beside it,
 * it is a frame view, the diff following its caret.
 */
public final class VcsStatusBuffer extends ViewBuffer implements FrameView {
    // How long the caret rests on a file before the diff beside follows it.
    private static final int FOLLOW_DELAY = 100;

    private final List<VcsRepository> repositories;
    // What was last read of each repository, on the event thread.
    private final Map<VcsRepository, StatusData> data = new LinkedHashMap<>();
    // Nodes opened or closed by hand, by key; read by the background reads too.
    private final Map<String, Boolean> expanded = new ConcurrentHashMap<>();
    // Files whose diffs have been read, so that none means there is none.
    private final Set<String> diffsRead = new HashSet<>();
    private final Consumer<VcsRepository> listener = r -> refresh();
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
        super.dispose();
    }

    // Reading.

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
                        });
                    }
                    catch (RuntimeException e) {
                        Log.error(e);
                        d = StatusData.failed(e.toString());
                    }
                    read.put(r, d != null ? d : StatusData.failed(r.backend().name() + " has no status"));
                }
                SwingUtilities.invokeLater(() -> show(read, diffs, startedAt));
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

    private void show(Map<VcsRepository, StatusData> read, Set<String> diffs, long startedAt) {
        if (disposed)
            return;
        // A file the status has closed may have changed with nothing it shows.
        followAgain(StatusDiffBuffer.Follow.REFRESHED);
        if (startedAt > wroteAt)
            writing = false;
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
    }

    /**
     * A write is done: writing until a read that starts from now is shown.
     * The repositories' refresh, which tells this buffer, starts it.
     */
    void wrote() {
        wroteAt = clock.incrementAndGet();
    }

    /** What was last read of a repository, or null. For tests. */
    StatusData getData(VcsRepository repository) {
        return data.get(repository);
    }

    // Expanding.

    boolean isExpanded(String key, Kind kind) {
        final Boolean b = expanded.get(key);
        if (b != null)
            return b;
        return switch (kind) {
            case SECTION -> depth >= 2;
            case FILE -> depth >= 3;
            case HUNK -> depth != 3; // A file opened by hand shows its hunks.
            default -> true;
        };
    }

    /** The line that heads the node line is in or heads, or null. */
    public StatusLine head(StatusLine line) {
        if (line.node() == null)
            return null;
        for (Line l = line; l != null; l = l.previous())
            if (l instanceof StatusLine s && s.key().equals(line.node()))
                return s;
        return null;
    }

    /**
     * The node around the one line heads or is in: a file's section, a
     * hunk's file; null if there is none.
     */
    public StatusLine parent(StatusLine line) {
        final StatusLine head = head(line);
        final String key = head != null ? head.key() : line.key();
        for (Line l = line.previous(); l != null; l = l.previous())
            if (l instanceof StatusLine s && s.isExpandable() && key.startsWith(s.key() + "|"))
                return s;
        return null;
    }

    /** Opens or closes the node line heads or is in. False if there is none. */
    public boolean toggle(StatusLine line) {
        final StatusLine head = head(line);
        if (head == null || !head.isExpandable())
            return false;
        setExpanded(head, !isExpanded(head.key(), head.kind()));
        return true;
    }

    public void setExpanded(StatusLine head, boolean open) {
        expanded.put(head.key(), open);
        if (open && head.kind() == Kind.FILE && head.file() != null && head.file().diff() == null) {
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
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
        if (frame != null)
            frame.showView(this, this);
        followCaret();
    }

    /** Closes the diff beside it: the windows from before it come back. */
    void closeDiff(Frame frame) {
        closeDiff(frame, false);
    }

    // Closing too, the status is left out of the windows from before.
    private void closeDiff(Frame frame, boolean closing) {
        if (diff == null)
            return;
        final StatusDiffBuffer d = diff;
        follow.stop();
        if (frame != null)
            frame.leaveView(this, closing ? List.of(this, d) : List.of(d));
        diff = null;
        before = null;
        // With no windows from before, the diff's own shows the status.
        for (Editor ed : d.windows())
            ed.activate(this);
        if (Editor.getBufferList().contains(d))
            d.kill();
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
    }

    @Override
    public void afterCommand(Editor editor) {
        if (diff != null && editor.getDot() != null && editor.getDotLine() instanceof StatusLine s) {
            final String target = StatusDiffBuffer.target(s);
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
        if (ed == null || ed.getDot() == null || !(ed.getDotLine() instanceof StatusLine s))
            return;
        final String target = StatusDiffBuffer.target(s);
        if (target == null)
            return;
        final boolean moved = !target.equals(followed) || diff.item() == null;
        if (!moved && why == null)
            return;
        followed = target;
        diff.follow(s, moved ? StatusDiffBuffer.Follow.MOVED : why);
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
        return diff == null ? List.of() : List.of(diff);
    }

    // The status at the left, the diff at the right.
    @Override
    public WindowLayout.Node layout(Frame frame, Buffer focus) {
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

    // The diff killed on its own closes as D does.
    @Override
    public boolean partKilled(Buffer part) {
        if (part != diff)
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
