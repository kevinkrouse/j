/*
 * VimMarks.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Marker;
import org.armedbear.j.Position;

/**
 * Named places in a buffer: {@code ma} to set one, {@code `a} to come back.
 *
 * Built on j's {@link Marker}, which holds its {@code Line}: a mark follows its
 * line when lines above it are added or removed. {@code Region.delete} does
 * not adjust these markers -- they are not among {@code Marker.getAllMarkers}
 * -- so a mark on a deleted line is gone, which is what vim does with a named
 * mark. So is one on a line J joins to the one above, which vim moves along
 * with the text.
 *
 * <p>A to Z, vim's file marks, are j's bookmarks
 * ({@link Editor#getBookmark}): one set for every window, in any file, and
 * moved by edits as j's bookmarks are.
 */
public final class VimMarks
{
    private final Map<Character, Marker> marks =
        new HashMap<Character, Marker>();

    /** True for a name that {@code m} accepts. */
    public static boolean isValidName(char name)
    {
        return (name >= 'a' && name <= 'z') || (name >= 'A' && name <= 'Z');
    }

    /** A to Z: a file mark, one of j's bookmarks. */
    public static boolean isFileMark(char name)
    {
        return name >= 'A' && name <= 'Z';
    }

    public void set(char name, Buffer buffer, Position pos)
    {
        if (isFileMark(name))
            Editor.setBookmark(name, new Marker(buffer, pos));
        else
            marks.put(Character.valueOf(name), new Marker(buffer, pos));
    }

    /** A file mark, wherever it is, or null if it is not set. */
    public static Marker getFileMark(char name)
    {
        return isFileMark(name) ? Editor.getBookmark(name) : null;
    }

    /**
     * Notes what a command changed or yanked: '[ at its start, '] on its last
     * character, and for a change '. at where it was made.
     *
     * @param end    the exclusive end; at the start for a delete
     * @param change where '. goes, or null for a yank or a change that
     *               changed nothing
     */
    public void noteChange(Buffer buffer, Position start, Position end,
                           Position change)
    {
        set('[', buffer, start);
        final Position last = new Position(end);
        if (!last.equals(start)) {
            if (last.getOffset() > 0)
                last.setOffset(CodePoints.previous(last.getLine(),
                                                   last.getOffset()));
            else
                last.prev();
        }
        set(']', buffer, last);
        if (change != null)
            set('.', buffer, change);
    }

    /** A change that began at start: '. goes there too. */
    public void noteEdit(Buffer buffer, Position start, Position end)
    {
        noteChange(buffer, start, end, start);
    }

    /**
     * Notes lines an ex command changed: '[ and '] at the starts of the
     * first and last, and '. at the start of the one it changed first.
     */
    public void noteLines(Buffer buffer, Line first, Line last, Line changed)
    {
        set('[', buffer, new Position(first, 0));
        set(']', buffer, new Position(last, 0));
        set('.', buffer, new Position(changed, 0));
    }

    /**
     * Where a mark is now, or null if it was never set or its buffer is gone.
     */
    public Position get(char name, Buffer buffer)
    {
        final Marker marker = isFileMark(name) ? Editor.getBookmark(name)
            : marks.get(Character.valueOf(name));
        if (marker == null || marker.getBuffer() != buffer)
            return null;
        // A mark on a deleted line went with it, as in vim.
        final Position pos = marker.getPosition();
        return pos != null && buffer.contains(pos.getLine()) ? pos : null;
    }

    /** Forgets one mark, for {@code :delmarks}. */
    public void remove(char name)
    {
        if (isFileMark(name))
            Editor.setBookmark(name, null);
        else
            marks.remove(Character.valueOf(name));
    }

    public void clear()
    {
        marks.clear();
    }

    /**
     * The nearest mark after a position, for {@code ]`}.
     *
     * Vim walks the marks in buffer order rather than in the order they were
     * set, so this sorts rather than remembering.
     */
    public Position next(Buffer buffer, Position from)
    {
        Position best = null;
        for (Position pos : positionsIn(buffer))
            if (pos.isAfter(from) && (best == null || pos.isBefore(best)))
                best = pos;
        return best;
    }

    /** The nearest mark before a position, for {@code [`}. */
    public Position previous(Buffer buffer, Position from)
    {
        Position best = null;
        for (Position pos : positionsIn(buffer))
            if (pos.isBefore(from) && (best == null || pos.isAfter(best)))
                best = pos;
        return best;
    }

    private List<Position> positionsIn(Buffer buffer)
    {
        // Lowercase marks only, as in vim: not '< or '[ or the rest.
        final List<Position> positions = new ArrayList<Position>();
        for (Map.Entry<Character, Marker> entry : marks.entrySet()) {
            final char name = entry.getKey().charValue();
            final Marker marker = entry.getValue();
            if (name < 'a' || name > 'z' || marker.getBuffer() != buffer)
                continue;
            final Position pos = marker.getPosition();
            if (pos != null)
                positions.add(pos);
        }
        return positions;
    }
}
