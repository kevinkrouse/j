/*
 * DiffPane.java
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.ViewPane;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * A pane showing one file's diff, drawn as the status buffer draws one, its
 * hunks folding: the log's diff of a commit's file, and the status's of the
 * file at its caret.
 */
public abstract class DiffPane<B extends Buffer> extends ViewPane<B> {
    private VcsRepository repository;
    // What its lines' keys start with: the status's section's, so a hunk
    // keeps the status's key.
    private String key;
    private FileItem item;
    // Hunks closed or opened by hand, by key; open by default.
    private final Map<String, Boolean> expanded = new HashMap<>();

    protected DiffPane(Mode mode, B owner, String title, String placeholder) {
        super(mode, owner, title, placeholder);
    }

    /** The file shown, or null. */
    public FileItem item() {
        return item;
    }

    /**
     * Shows item's diff, its lines keyed under key, the caret on the line
     * keyed at, else the first change. The same file again keeps its hunks
     * as they were opened or closed, and each window's place.
     */
    protected void show(VcsRepository repository, String key, FileItem item, String at) {
        final boolean same = this.item != null
                && this.repository == repository
                && key.equals(this.key)
                && item.path().equals(this.item.path());
        if (!same)
            expanded.clear();
        this.repository = repository;
        this.key = key;
        this.item = item;
        render(at, same && at == null);
    }

    /** Shows nothing but message. */
    protected void clear(String message) {
        repository = null;
        key = null;
        item = null;
        expanded.clear();
        replace(List.of(StatusLine.of(Kind.MESSAGE, message, "message")), null);
    }

    /**
     * Lays the lines out again, the caret on the line keyed at, at the top,
     * or on the first change. Kept, each window's caret and top stay on
     * their lines, or on their hunks when those close.
     */
    private void render(String at, boolean kept) {
        if (item == null)
            return;
        final Map<Editor, StatusLine[]> was = new HashMap<>();
        if (kept)
            for (Editor ed : windows())
                if (ed.getDot() != null
                        && ed.getDotLine() instanceof StatusLine d
                        && ed.getTopLine() instanceof StatusLine t)
                    was.put(ed, new StatusLine[] { d, t });
        final List<StatusLine> lines = StatusRenderer.renderFiles(
            repository,
            key,
            List.of(item),
            (k, kind) -> kind == Kind.FILE || expanded.getOrDefault(k, true));
        Line first = null;
        for (StatusLine l : lines)
            if (at != null ? l.key().equals(at) : l.kind() == Kind.DIFF && !l.diffLine().isContext()) {
                first = l;
                break;
            }
        fill(lines);
        for (Editor ed : windows()) {
            final StatusLine[] w = was.get(ed);
            final Line dot = at == null && w != null ? same(lines, w[0]) : first;
            final Line top = w != null ? same(lines, w[1]) : at != null ? first : null;
            place(ed, dot != null ? dot : getFirstLine(), top != null ? top : getFirstLine());
        }
    }

    // The line keyed as l was, or its hunk's line; null if neither is there.
    private static Line same(List<StatusLine> lines, StatusLine l) {
        for (StatusLine s : lines)
            if (s.key().equals(l.key()))
                return s;
        for (StatusLine s : lines)
            if (s.key().equals(l.node()))
                return s;
        return null;
    }

    /** The key of the hunk line is in or heads, or null. */
    static String hunkOf(StatusLine line) {
        return line.kind() == Kind.HUNK ? line.key() : line.kind() == Kind.DIFF ? line.node() : null;
    }

    /** Opens or closes the hunk line is in or heads. False if there is none. */
    public boolean toggle(StatusLine line) {
        final String hunk = hunkOf(line);
        if (hunk == null)
            return false;
        setExpanded(hunk, !expanded.getOrDefault(hunk, true));
        return true;
    }

    /** Opens or closes the hunk line is in or heads, or toggles it for null, as a fold. */
    public void setFold(StatusLine line, Boolean open) {
        final String hunk = hunkOf(line);
        if (hunk != null)
            setExpanded(hunk, open != null ? open : !expanded.getOrDefault(hunk, true));
    }

    /** Opens or closes every hunk. */
    public void setAll(boolean open) {
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.HUNK)
                expanded.put(s.key(), open);
        render(null, true);
    }

    /** Closes every hunk, or opens them all when none is open. */
    public void toggleAll() {
        boolean anyOpen = false;
        for (Line l : StatusLine.hunks(this))
            anyOpen |= ((StatusLine) l).isOpen();
        setAll(!anyOpen);
    }

    void setExpanded(String hunk, boolean open) {
        expanded.put(hunk, open);
        render(hunk, true);
    }
}
