/*
 * VirtualTextLayer.java
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.armedbear.j.VirtualText.Placement;

/**
 * A buffer's virtual text, by owner ("vcs.blame", "hint") and by line. An
 * owner replaces all of its own at once. A line's text goes with the line:
 * once the line is deleted, nothing asks for it, and it is dropped the next
 * time the rows are counted.
 */
public final class VirtualTextLayer {
    private static final Comparator<VirtualText> ORDER = Comparator.comparingInt(VirtualText::priority);

    private final Buffer buffer;
    private final Map<String, List<VirtualText>> byOwner = new LinkedHashMap<>();
    private final Map<Line, List<VirtualText>> byLine = new IdentityHashMap<>();
    private final List<VirtualText> top = new ArrayList<>();

    // The lines with rows of their own that are still in the buffer, as of
    // the buffer's last change.
    private List<Line> rowLines;
    private int rowLinesModCount;
    private Line rowLinesFirstLine;
    // Counts changes to which lines have rows.
    private int version;
    // The buffer is read without its lock, and never while holding this
    // layer's: Buffer.firstVisibleLine takes neither.

    VirtualTextLayer(Buffer buffer) {
        this.buffer = buffer;
    }

    /** Replaces owner's virtual text with list, and repaints. */
    public void set(String owner, List<VirtualText> list) {
        final boolean rowsChanged;
        final List<Line> changed = new ArrayList<>();
        synchronized (this) {
            final List<VirtualText> old = byOwner.getOrDefault(owner, List.of());
            if (old.isEmpty() && list.isEmpty())
                return;
            rowsChanged = hasRows(old) || hasRows(list);
            for (VirtualText v : old) {
                remove(v);
                if (v.line() != null)
                    changed.add(v.line());
            }
            if (list.isEmpty())
                byOwner.remove(owner);
            else
                byOwner.put(owner, List.copyOf(list));
            for (VirtualText v : list) {
                add(v);
                if (v.line() != null)
                    changed.add(v.line());
            }
            if (rowsChanged) {
                rowLines = null;
                version++;
            }
        }
        repaint(rowsChanged, changed);
    }

    /** What changes when any line's rows of virtual text do. */
    public synchronized int rowsVersion() {
        return version;
    }

    public void clear(String owner) {
        set(owner, List.of());
    }

    /** Owner's virtual text. */
    public synchronized List<VirtualText> get(String owner) {
        return byOwner.getOrDefault(owner, List.of());
    }

    public synchronized boolean isEmpty() {
        return byOwner.isEmpty();
    }

    private static boolean hasRows(List<VirtualText> list) {
        for (VirtualText v : list)
            if (v.height() > 0)
                return true;
        return false;
    }

    private void add(VirtualText v) {
        final List<VirtualText> list =
                v.line() == null ? top : byLine.computeIfAbsent(v.line(), l -> new ArrayList<>());
        list.add(v);
        list.sort(ORDER);
    }

    private void remove(VirtualText v) {
        if (v.line() == null) {
            top.remove(v);
            return;
        }
        final List<VirtualText> list = byLine.get(v.line());
        if (list != null) {
            list.remove(v);
            if (list.isEmpty())
                byLine.remove(v.line());
        }
    }

    /** Line's virtual text placed so, in order. */
    public synchronized List<VirtualText> on(Line line, Placement placement) {
        final List<VirtualText> all = placement == Placement.TOP ? top : byLine.get(line);
        if (all == null || all.isEmpty())
            return List.of();
        final List<VirtualText> list = new ArrayList<>();
        for (VirtualText v : all)
            if (v.placement() == placement)
                list.add(v);
        return list;
    }

    /** Whether line has any virtual text. */
    public boolean has(Line line) {
        final Line first = buffer.firstVisibleLine();
        synchronized (this) {
            return byLine.containsKey(line) || (!top.isEmpty() && line == first);
        }
    }

    /** Whether line is where TOP's rows go: the first line shown. */
    public boolean isTopLine(Line line) {
        return line != null && line == buffer.firstVisibleLine();
    }

    /** The rows drawn over line: its own, and TOP's over the first line shown. */
    public int rowsAbove(Line line) {
        return rowsAbove(line, line == buffer.firstVisibleLine());
    }

    /** The same, for a walk of the lines that knows whether line is the first shown. */
    public synchronized int rowsAbove(Line line, boolean firstShown) {
        return rows(byLine.get(line), Placement.ABOVE) + (firstShown ? rows(top, Placement.TOP) : 0);
    }

    /** The rows drawn under line. */
    public synchronized int rowsBelow(Line line) {
        return rows(byLine.get(line), Placement.BELOW);
    }

    private static int rows(List<VirtualText> list, Placement placement) {
        if (list == null)
            return 0;
        int rows = 0;
        for (VirtualText v : list)
            if (v.placement() == placement)
                rows += v.height();
        return rows;
    }

    /**
     * The rows before line's own: TOP's, unless line is the first shown, and
     * each earlier visible line's. Wants the buffer's lines numbered.
     */
    public int rowsBefore(Line line) {
        final List<Line> lines = rowLines();
        final Line first = buffer.firstVisibleLine();
        synchronized (this) {
            int rows = 0;
            for (Line l : lines)
                if (!l.isHidden() && l.lineNumber() < line.lineNumber())
                    rows += rows(byLine.get(l), Placement.ABOVE) + rows(byLine.get(l), Placement.BELOW);
            if (!top.isEmpty() && line != first)
                rows += rows(top, Placement.TOP);
            return rows;
        }
    }

    /** All the rows of virtual text over and under the visible lines. */
    public int totalRows() {
        final List<Line> lines = rowLines();
        final boolean shown = buffer.firstVisibleLine() != null;
        synchronized (this) {
            int rows = shown ? rows(top, Placement.TOP) : 0;
            for (Line l : lines)
                if (!l.isHidden())
                    rows += rows(byLine.get(l), Placement.ABOVE) + rows(byLine.get(l), Placement.BELOW);
            return rows;
        }
    }

    // The lines with rows, dropping the text of lines no longer in the
    // buffer: one walk of the buffer, outside this layer's lock, and only
    // after a change.
    private List<Line> rowLines() {
        final int modCount = buffer.getModCount();
        final Line first = buffer.peekFirstLine();
        final java.util.Set<Line> candidates = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final int seen;
        synchronized (this) {
            if (rowLines != null && rowLinesModCount == modCount && rowLinesFirstLine == first)
                return rowLines;
            seen = version;
            for (Map.Entry<Line, List<VirtualText>> e : byLine.entrySet())
                if (hasRows(e.getValue()))
                    candidates.add(e.getKey());
        }
        final List<Line> lines = new ArrayList<>();
        for (Line l = first; l != null && !candidates.isEmpty(); l = l.next())
            if (candidates.remove(l))
                lines.add(l);
        synchronized (this) {
            for (Line gone : candidates)
                drop(gone);
            lines.removeIf(l -> !byLine.containsKey(l));
            // Rows given meanwhile are looked for next time.
            if (version == seen) {
                rowLines = lines;
                rowLinesModCount = modCount;
                rowLinesFirstLine = first;
            }
            return lines;
        }
    }

    private void drop(Line line) {
        final List<VirtualText> list = byLine.remove(line);
        if (list == null)
            return;
        for (Map.Entry<String, List<VirtualText>> e : byOwner.entrySet()) {
            final List<VirtualText> kept = new ArrayList<>(e.getValue());
            if (kept.removeIf(v -> v.line() == line))
                e.setValue(List.copyOf(kept));
        }
        byOwner.values().removeIf(List::isEmpty);
    }

    // Rows change the window's height, so the whole of it; otherwise the
    // lines whose text changed.
    private void repaint(boolean rowsChanged, List<Line> lines) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> repaint(rowsChanged, lines));
            return;
        }
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() != buffer)
                continue;
            if (rowsChanged) {
                ed.getDisplay().rowsChanged();
                ed.repaintDisplay();
                ed.updateScrollBars();
            } else {
                for (Line line : lines)
                    ed.getDisplay().lineChanged(line);
                ed.getDisplay().repaintChangedLines();
            }
        }
    }
}
