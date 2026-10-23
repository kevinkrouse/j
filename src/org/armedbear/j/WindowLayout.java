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
        /** A window of editor's buffer, at its caret and top. */
        public static Window of(Editor editor, boolean focused) {
            final Position dot = editor.getDot();
            final Line top = editor.getTopLine();
            return new Window(editor.getBuffer(),
                    dot == null ? 0 : dot.lineNumber(),
                    dot == null ? 0 : dot.getOffset(),
                    top == null ? 0 : top.lineNumber(),
                    focused);
        }

        /**
         * A window of buffer, shown in none: where its last window left it,
         * by line number if its lines have been replaced since; or its start.
         */
        public static Window of(Buffer buffer, boolean focused) {
            final View view = buffer.getLastView();
            if (view == null)
                return new Window(buffer, 0, 0, 0, focused);
            final Position dot = view.getDot();
            final Line top = view.getTopLine();
            return new Window(buffer,
                    dot != null ? dot.lineNumber() : view.getDotLineNumber(),
                    dot != null ? dot.getOffset() : view.getDotOffset(),
                    top != null ? top.lineNumber() : view.getTopLineNumber(),
                    focused);
        }

        /** A window of buffer with its caret and top where a window showing it has them now, or its last one left them. */
        public static Window showing(Buffer buffer, boolean focused) {
            final Editor ed = Editor.windowShowing(buffer);
            return ed != null ? of(ed, focused) : of(buffer, focused);
        }

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
     * The layout with its focused window, or else its first, showing buffer
     * where its last window left it, focused as that window was.
     */
    public static Node showing(Node root, Buffer buffer) {
        final List<Window> all = windows(root);
        Window target = all.get(0);
        for (Window w : all)
            if (w.focused()) {
                target = w;
                break;
            }
        final Window was = target;
        return map(root, w -> w == was ? Window.of(buffer, was.focused()) : w);
    }

    /** The layout with each window replaced by what f makes of it. */
    static Node map(Node node, java.util.function.UnaryOperator<Window> f) {
        if (node instanceof Window w)
            return f.apply(w);
        final Split s = (Split) node;
        final List<Node> children = new ArrayList<>();
        for (Node child : s.children())
            children.add(map(child, f));
        return new Split(s.row(), children, s.shares());
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
