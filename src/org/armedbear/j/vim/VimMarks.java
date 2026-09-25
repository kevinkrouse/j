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
import org.armedbear.j.Marker;
import org.armedbear.j.Position;

/**
 * Named places in a buffer: {@code ma} to set one, {@code `a} to come back.
 *
 * Built on j's {@link Marker}, so a mark follows the text it was put on when
 * lines above it are added or removed -- {@code Region.delete} already adjusts
 * every live marker, and a mark that did not move with its text would be worse
 * than no mark at all.
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

    public void set(char name, Buffer buffer, Position pos)
    {
        marks.put(Character.valueOf(name), new Marker(buffer, pos));
    }

    /**
     * Where a mark is now, or null if it was never set or its buffer is gone.
     */
    public Position get(char name, Buffer buffer)
    {
        final Marker marker = marks.get(Character.valueOf(name));
        if (marker == null || marker.getBuffer() != buffer)
            return null;
        return marker.getPosition();
    }

    /** Forgets one mark, for {@code :delmarks}. */
    public void remove(char name)
    {
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
        final List<Position> positions = new ArrayList<Position>();
        for (Marker marker : marks.values()) {
            if (marker.getBuffer() != buffer)
                continue;
            final Position pos = marker.getPosition();
            if (pos != null)
                positions.add(pos);
        }
        return positions;
    }
}
