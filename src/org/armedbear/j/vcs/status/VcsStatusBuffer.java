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
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * The status of one repository, or of a project's several: sections of
 * files and commits, each file's diff shown inline when expanded. It is
 * read again in the background whenever its repositories change, and a
 * refresh keeps the caret on the thing it was on.
 */
public final class VcsStatusBuffer extends Buffer {
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

    private VcsStatusBuffer(List<VcsRepository> repositories) {
        this.repositories = List.copyOf(repositories);
        supportsUndo = false;
        type = TYPE_OUTPUT;
        mode = VcsStatusMode.getMode();
        formatter = mode.getFormatter(this);
        readOnly = true;
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.SHOW_CHANGE_MARKS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
        setProperty(Property.RAINBOW_DELIMITERS, false);
        setProperty(Property.FOLD_MARKERS, "always");
        final VcsRepository first = repositories.get(0);
        title = repositories.size() == 1
                ? first.backend().name() + " status: " + first.root().getFileName()
                : first.backend().name() + " status: " + repositories.size() + " repositories";
        withWriteLock(() -> {
            appendLine("Reading…");
            renumber();
        });
        setInitialized(true);
        setLoaded(true);
        for (VcsRepository r : repositories)
            r.addListener(listener);
    }

    /** The status buffer of these repositories, the one open if there is one. */
    public static VcsStatusBuffer of(List<VcsRepository> repositories) {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof VcsStatusBuffer s && s.repositories.equals(repositories))
                return s;
        final VcsStatusBuffer buffer = new VcsStatusBuffer(repositories);
        buffer.refresh();
        return buffer;
    }

    public List<VcsRepository> getRepositories() {
        return repositories;
    }

    @Override
    public int load() {
        return LOAD_COMPLETED;
    }

    @Override
    public String getFileNameForDisplay() {
        return title;
    }

    @Override
    public void dispose() {
        disposed = true;
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
                SwingUtilities.invokeLater(() -> show(read, diffs));
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

    private void show(Map<VcsRepository, StatusData> read, Set<String> diffs) {
        if (disposed)
            return;
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
            final Line dot = p == null ? lines.get(0) : find(byKey, oldKeys, p.dotKey(), p.dotIndex(), lines.get(0));
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
