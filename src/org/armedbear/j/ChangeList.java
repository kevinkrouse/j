/*
 * ChangeList.java
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each buffer was changed, oldest first, as vim's change list: an edit
 * on the line of the latest entry moves that entry rather than adding one.
 * olderChange and newerChange travel it, as vim's g; and g, do.
 */
public final class ChangeList {
    /** Vim's change list length. */
    private static final int MAX = 100;

    private static final Map<Buffer, List<Marker>> lists = new HashMap<>();
    // Where travelling has got to, by buffer; the size when not travelling.
    private static final Map<Buffer, Integer> indexes = new HashMap<>();

    private ChangeList() {}

    /** Buffer was changed: records the caret, if it's the current editor's buffer. */
    static synchronized void changed(Buffer buffer) {
        final Editor editor = Editor.currentEditor();
        if (editor == null || editor.getBuffer() != buffer || editor.getDot() == null)
            return;
        final Position dot = editor.getDot();
        List<Marker> list = lists.computeIfAbsent(buffer, b -> new ArrayList<>());
        if (!list.isEmpty()) {
            Marker last = list.get(list.size() - 1);
            Position pos = last.getPosition();
            if (pos != null && pos.getLine() == dot.getLine()) {
                last.setPosition(dot);
                indexes.put(buffer, list.size());
                return;
            }
        }
        list.add(new Marker(buffer, dot));
        if (list.size() > MAX)
            list.remove(0);
        indexes.put(buffer, list.size());
    }

    /** The buffer's changes, oldest first. */
    public static synchronized List<Marker> getEntries(Buffer buffer) {
        List<Marker> list = lists.get(buffer);
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** Where travelling has got to: an index, or the size when not travelling. */
    public static synchronized int getIndex(Buffer buffer) {
        Integer i = indexes.get(buffer);
        List<Marker> list = lists.get(buffer);
        return i != null ? i : list == null ? 0 : list.size();
    }

    /** Goes to entry index of the buffer's list, as travelling there would. */
    public static void goTo(Editor editor, int index) {
        Marker to;
        synchronized (ChangeList.class) {
            List<Marker> list = lists.get(editor.getBuffer());
            if (list == null || index < 0 || index >= list.size())
                return;
            indexes.put(editor.getBuffer(), index);
            to = list.get(index);
        }
        // Not holding the lock: moving the caret takes the buffer's, which an
        // edit holds while it records a change here.
        to.gotoMarker(editor);
    }

    /** Forgets a closed buffer's list. */
    static synchronized void forget(Buffer buffer) {
        lists.remove(buffer);
        indexes.remove(buffer);
    }

    /** For Marker.getAllMarkers, so that edits move the entries too. */
    static synchronized List<Marker> getAllEntries() {
        List<Marker> all = new ArrayList<>();
        for (List<Marker> list : lists.values())
            all.addAll(list);
        return all;
    }

    // ------------------------------------------------------------ commands

    /** {@code olderChange} -- to the change before, as vim's g;. */
    public static void olderChange(Editor editor) {
        travel(editor, -1, "At start of changelist");
    }

    /** {@code newerChange} -- to the change after, as vim's g,. */
    public static void newerChange(Editor editor) {
        travel(editor, 1, "At end of changelist");
    }

    private static void travel(Editor editor, int count, String none) {
        int target;
        synchronized (ChangeList.class) {
            List<Marker> list = lists.get(editor.getBuffer());
            if (list == null || list.isEmpty()) {
                editor.status("Changelist is empty");
                return;
            }
            target = getIndex(editor.getBuffer()) + count;
            if (target < 0 || target >= list.size()) {
                editor.status(none);
                return;
            }
        }
        goTo(editor, target);
    }
}
