/*
 * JumpList.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the caret was before each jump -- a search, a tag, a go-to-line --
 * oldest first, for jumpBack and jumpForward, which are vim's CTRL-O and
 * CTRL-I, and pushPosition and popPosition.
 *
 * <p>Entries are {@link Marker}s: they follow their text as it is edited,
 * and may be in any buffer, so going back can switch buffers, and opens a
 * file again that was closed since. One entry per line: a jump from a line
 * already listed replaces that entry, as vim does. One list for the whole of
 * j, where vim keeps one per window.
 */
public final class JumpList {
    /** Vim's 'jumplist' length. */
    private static final int MAX = 100;

    private static final List<Marker> entries = new ArrayList<>();
    /** Where jumpBack and jumpForward are; the size when not travelling. */
    private static int index;

    private JumpList() {}

    /** A jump is leaving from here. */
    public static void record(Buffer buffer, Position pos) {
        record(new Marker(buffer, pos));
    }

    public static synchronized void record(Marker marker) {
        final Position pos = marker.getPosition();
        if (pos == null)
            return;
        marker.getBuffer().renumber();
        final int line = pos.lineNumber();
        entries.removeIf(m -> sameLine(m, marker.getBuffer(), line));
        entries.add(marker);
        if (entries.size() > MAX)
            entries.remove(0);
        index = entries.size();
    }

    private static boolean sameLine(Marker m, Buffer buffer, int line) {
        final Position pos = m.getPosition();
        return m.getBuffer() == buffer && pos != null && pos.lineNumber() == line;
    }

    /**
     * The entry count steps away -- back for a count below 0 -- or null when
     * there is none. Leaving the end of the list records where the caret is,
     * so that going forward again comes back to it.
     */
    public static synchronized Marker travel(Buffer buffer, Position here, int count) {
        if (index + count < 0 || index + count >= entries.size())
            return null;
        if (index == entries.size()) {
            record(buffer, here);
            index = entries.size() - 1;
            if (index + count < 0)
                return null;
        }
        index += count;
        return entries.get(index);
    }

    /** Whether the last jump is the latest thing done: not travelling. */
    public static synchronized boolean isAtEnd() {
        return index == entries.size();
    }

    /** For Marker.getAllMarkers, so that edits move the entries too. */
    static synchronized List<Marker> getEntries() {
        return Collections.unmodifiableList(new ArrayList<Marker>(entries));
    }

    /** The entries, oldest first. */
    public static synchronized List<Marker> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Where travelling has got to: an index, or the size when not travelling. */
    public static synchronized int index() {
        return index;
    }

    /** Goes to entry i, as travelling there with jumpBack and jumpForward would. */
    public static void goTo(Editor editor, int i) {
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        Marker to;
        synchronized (JumpList.class) {
            if (i < 0 || i >= entries.size())
                return;
            to = entries.get(i);
            // Leaving the end records the caret, which may drop an entry on its
            // line and shift the rest: find the target again, not its index.
            if (index == entries.size())
                record(editor.getBuffer(), dot);
            index = entries.indexOf(to);
            if (index < 0) {
                // It was on the caret's line, and the caret's entry replaced it.
                index = entries.size() - 1;
                to = entries.get(index);
            }
        }
        to.gotoMarker(editor);
    }

    /** Forgets every entry. */
    public static synchronized void clear() {
        entries.clear();
        index = 0;
    }

    // ------------------------------------------------------------ commands

    /** {@code jumpBack} -- to where the caret was before the last jump. */
    public static void jumpBack() {
        go(-1, "No earlier position");
    }

    /** {@code jumpForward} -- back again to where jumpBack came from. */
    public static void jumpForward() {
        go(1, "No later position");
    }

    private static void go(int count, String none) {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Marker to = travel(editor.getBuffer(), dot, count);
        if (to == null)
            editor.status(none);
        else
            to.gotoMarker(editor);
    }
}
