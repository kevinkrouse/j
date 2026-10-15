/*
 * WindowLayout.java
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
import java.util.List;
import java.util.function.Predicate;

/**
 * A frame's windows as a tree: the splits, each one's share of its split,
 * and each window's buffer, caret and top line, and which had the focus.
 * The transient panel is left out. {@link Frame#captureLayout} takes one
 * before a view that takes the whole frame, as the log, and
 * {@link Frame#restoreLayout} puts it back after.
 */
public final class WindowLayout {
    private WindowLayout() {}

    /** A split or a window. */
    public sealed interface Node permits Split, Window {}

    /** Windows side by side (row) or stacked, each with its share of the split. */
    public record Split(boolean row, List<Node> children, List<Double> shares) implements Node {
        public Split {
            children = List.copyOf(children);
            shares = List.copyOf(shares);
        }
    }

    /** A window: its buffer, its caret, its top line, and whether it had the focus. */
    public record Window(Buffer buffer, int dotLine, int dotOffset, int topLine, boolean focused) implements Node {
        /** Puts editor's caret and top line back, as near as the buffer allows now. */
        void applyTo(Editor editor) {
            if (editor.getBuffer() != buffer || editor.getDot() == null)
                return;
            final Line dot = line(dotLine);
            if (dot == null)
                return;
            editor.setDot(dot, Math.min(dotOffset, dot.length()));
            editor.setMark(null);
            editor.moveCaretToDotCol();
            final Line top = line(topLine);
            if (top != null)
                editor.setTopLine(top);
        }

        // The line numbered n, or the last when the buffer is shorter now.
        private Line line(int n) {
            final Line l = buffer.getLine(Math.max(0, n));
            return l != null ? l : buffer.getLastLine();
        }
    }

    /** The windows, in order: left to right, top to bottom. */
    public static List<Window> windows(Node root) {
        final List<Window> out = new ArrayList<>();
        windows(root, out);
        return out;
    }

    private static void windows(Node node, List<Window> out) {
        if (node instanceof Window w)
            out.add(w);
        else if (node instanceof Split s)
            for (Node child : s.children())
                windows(child, out);
    }

    /**
     * The layout with only the windows whose buffer keep takes, their
     * splits' shares made up to the whole again; null if none is left.
     */
    static Node keeping(Node node, Predicate<Buffer> keep) {
        if (node instanceof Window w)
            return keep.test(w.buffer()) ? w : null;
        final Split s = (Split) node;
        final List<Node> children = new ArrayList<>();
        final List<Double> shares = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < s.children().size(); i++) {
            final Node child = keeping(s.children().get(i), keep);
            if (child != null) {
                children.add(child);
                shares.add(s.shares().get(i));
                total += s.shares().get(i);
            }
        }
        if (children.isEmpty())
            return null;
        if (children.size() == 1)
            return children.get(0);
        for (int i = 0; i < shares.size(); i++)
            shares.set(i, total > 0 ? shares.get(i) / total : 1.0 / shares.size());
        return new Split(s.row(), children, shares);
    }
}
