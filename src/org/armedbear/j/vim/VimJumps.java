/*
 * VimJumps.java
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
import java.util.Iterator;
import java.util.List;

import org.armedbear.j.Buffer;
import org.armedbear.j.Marker;
import org.armedbear.j.Position;

/**
 * One buffer's jump list, for CTRL-O and CTRL-I: where the caret was before
 * each jump, oldest first. Vim's, without its travel between buffers.
 *
 * Entries are j {@link Marker}s, which follow their lines. A delete keeps the
 * first Line it takes, holding the text after, so an entry there moves on as
 * in vim; one on any other deleted line is dropped, where vim moves it too.
 * One entry per line: a new jump from a line already listed replaces that
 * entry, which is the duplicate cleanup vim does before each CTRL-O.
 */
final class VimJumps
{
    /** Vim's 'jumplist' length. */
    private static final int MAX = 100;

    private final Buffer buffer;
    private final List<Marker> entries = new ArrayList<Marker>();
    /** Where CTRL-O and CTRL-I are; the list's size when not travelling. */
    private int index;

    VimJumps(Buffer buffer)
    {
        this.buffer = buffer;
    }

    boolean isAtEnd()
    {
        return index == entries.size();
    }

    /** Vim's setpcmark: a jump is leaving here. */
    void push(Position pos)
    {
        prune();
        buffer.renumber();
        final int line = pos.lineNumber();
        for (Iterator<Marker> it = entries.iterator(); it.hasNext();)
            if (it.next().getPosition().lineNumber() == line)
                it.remove();
        entries.add(new Marker(buffer, pos));
        if (entries.size() > MAX)
            entries.remove(0);
        index = entries.size();
    }

    /**
     * CTRL-O (count below 0) or CTRL-I: the entry count steps away, or null
     * when there is none. The first CTRL-O after a jump lists where it is
     * typed, so CTRL-I can come back to it.
     */
    Position travel(Position from, int count)
    {
        prune();
        if (index + count < 0 || index + count >= entries.size())
            return null;
        if (index == entries.size()) {
            push(from);
            index = entries.size() - 1;
            if (index + count < 0)
                return null;
        }
        index += count;
        return entries.get(index).getPosition();
    }

    /** Drops entries whose line was deleted. */
    private void prune()
    {
        for (int i = entries.size() - 1; i >= 0; i--) {
            final Position pos = entries.get(i).getPosition();
            if (pos == null || !buffer.contains(pos.getLine())) {
                entries.remove(i);
                if (index > i)
                    --index;
            }
        }
    }
}
