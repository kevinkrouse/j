/*
 * CompareFolds.java
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

package org.armedbear.j.mode.compare;

import static org.armedbear.j.Constants.REFRAME;
import static org.armedbear.j.Constants.REPAINT;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Folds;
import org.armedbear.j.Line;
import org.armedbear.j.ViewBuffer;
import org.armedbear.j.VirtualText;
import org.armedbear.j.mode.compare.DiffAlignment.Kind;
import org.armedbear.j.mode.compare.DiffAlignment.Row;

/**
 * A comparison's folds, in place of its file's mode's, as vim's diff
 * mode and IntelliJ's diff viewer have theirs: each run of unchanged lines
 * but for the CONTEXT next to a change, the same lines on both sides, opened
 * and closed on both at once. Each is labeled with how many lines it hides.
 */
final class CompareFolds {
    // The unchanged lines kept by a change, as a hunk's context.
    static final int CONTEXT = 3;
    // The fewest lines a fold hides: one would only become its label.
    private static final int MIN = 2;

    // count lines from oldFrom on the old side and from newFrom on the new.
    private static final class Fold {
        final int oldFrom;
        final int newFrom;
        final int count;
        boolean closed = true;

        Fold(int oldFrom, int newFrom, int count) {
            this.oldFrom = oldFrom;
            this.newFrom = newFrom;
            this.count = count;
        }

        int from(boolean old) {
            return old ? oldFrom : newFrom;
        }

        // The same fold, as a later layout of the same texts makes it.
        List<Integer> key() {
            return List.of(oldFrom, newFrom, count);
        }

        // The line its marker and label are at: the one over it, or under
        // it at the top.
        int anchor(boolean old) {
            return from(old) > 0 ? from(old) - 1 : from(old) + count;
        }
    }

    private final CompareBuffer owner;
    private final List<Fold> folds = new ArrayList<>();
    private final Folds oldFolds = new SideFolds(true);
    private final Folds newFolds = new SideFolds(false);
    // While it hides and shows lines itself.
    private boolean applying;

    CompareFolds(CompareBuffer owner) {
        this.owner = owner;
    }

    /** A side's folds, as the fold commands, keys and gutter ask. */
    Folds of(boolean old) {
        return old ? oldFolds : newFolds;
    }

    /**
     * alignment's unchanged runs folded, none if nothing changed, but for
     * those open before that it has too, as after a change of mode.
     */
    void reset(DiffAlignment alignment) {
        final Set<List<Integer>> open = new HashSet<>();
        for (Fold f : folds)
            if (!f.closed)
                open.add(f.key());
        folds.clear();
        final List<Row> rows = alignment.rows();
        int r = 0;
        while (!alignment.changes().isEmpty() && r < rows.size()) {
            if (rows.get(r).kind() != Kind.SAME) {
                ++r;
                continue;
            }
            int end = r;
            while (end < rows.size() && rows.get(end).kind() == Kind.SAME)
                ++end;
            final int from = r == 0 ? r : r + CONTEXT;
            final int to = end == rows.size() ? end : end - CONTEXT;
            if (to - from >= MIN) {
                final Fold f = new Fold(rows.get(from).oldLine(), rows.get(from).newLine(), to - from);
                f.closed = !open.contains(f.key());
                folds.add(f);
            }
            r = end;
        }
        // Not its windows, still at the lines of the layout before: they are placed after.
        apply(true, false);
    }

    /** Where a jump to side's line l lands: l, or, l folded away, the line its fold's count is on. */
    Line handle(ViewBuffer side, Line l) {
        if (!l.isHidden())
            return l;
        final boolean old = side == owner.getOld();
        final Fold f = at(l, old, true);
        final Line anchor = f == null ? null : side.getLine(f.anchor(old));
        return anchor != null && !anchor.isHidden() ? anchor : CompareBuffer.shown(l);
    }

    /** side's labels again, in its formatter's colors now, as after a change of theme. */
    void relabel(ViewBuffer side) {
        label(side, CompareBuffer.lines(side), side == owner.getOld());
        for (Editor ed : side.windows())
            ed.getDisplay().repaint();
    }

    /**
     * side's lines hidden or shown some other way, as by a click past a
     * line: each fold as side has it now, on both sides. A fold partly
     * hidden there, and lines hidden outside the folds, are put back.
     */
    void read(ViewBuffer side) {
        if (applying)
            return;
        final boolean old = side == owner.getOld();
        final List<Line> lines = CompareBuffer.lines(side);
        for (Fold f : folds) {
            int hidden = 0;
            for (int i = f.from(old); i < f.from(old) + f.count && i < lines.size(); i++)
                if (lines.get(i).isHidden())
                    ++hidden;
            if (hidden == f.count)
                f.closed = true;
            else if (hidden == 0)
                f.closed = false;
        }
        apply(false, true);
    }

    private void set(Fold f, boolean closed) {
        f.closed = closed;
        apply(true, true);
    }

    // Each side's lines hidden as its closed folds have them, and no
    // others; then the windows off them, before anything is painted, as
    // the labels are: a window can't be at a line folded away; then each
    // closed fold labeled. Unless forced, nothing more if no line changed,
    // as on each renumbering that opened or closed nothing; with windows
    // false, the windows left as they are.
    private void apply(boolean force, boolean windows) {
        applying = true;
        try {
            final List<ViewBuffer> sides = List.of(owner.getOld(), owner);
            final List<List<Line>> lines = new ArrayList<>();
            boolean changed = false;
            for (ViewBuffer side : sides) {
                final List<Line> ls = CompareBuffer.lines(side);
                lines.add(ls);
                if (hide(ls, side == owner.getOld())) {
                    side.renumber();
                    changed = true;
                }
            }
            if (!changed && !force)
                return;
            if (windows)
                for (ViewBuffer side : sides)
                    for (Editor ed : side.windows())
                        CompareBuffer.offFolds(ed);
            for (int i = 0; i < sides.size(); i++) {
                final ViewBuffer side = sides.get(i);
                label(side, lines.get(i), side == owner.getOld());
                side.foldsChanged();
                if (!windows)
                    continue;
                for (Editor ed : side.windows()) {
                    ed.setUpdateFlag(REFRAME | REPAINT);
                    if (ed.getDot() != null)
                        ed.reframe();
                    ed.getDisplay().repaint();
                }
            }
        }
        finally {
            applying = false;
        }
    }

    // A side's lines hidden as its closed folds have them; whether any
    // changed.
    private boolean hide(List<Line> lines, boolean old) {
        final boolean[] hide = new boolean[lines.size()];
        for (Fold f : folds)
            if (f.closed)
                for (int i = f.from(old); i < f.from(old) + f.count && i < hide.length; i++)
                    hide[i] = true;
        boolean changed = false;
        for (int i = 0; i < hide.length; i++) {
            final Line l = lines.get(i);
            if (l.isHidden() != hide[i] || l.getHidden() > 1) {
                l.setHidden(hide[i] ? 1 : 0);
                changed = true;
            }
        }
        return changed;
    }

    // After the line over each closed fold, or under it at the top, as the
    // log's runs have theirs, "… 120 lines", which a click opens.
    private void label(ViewBuffer side, List<Line> lines, boolean old) {
        final List<VirtualText> labels = new ArrayList<>();
        final Color color = side.getFormatter() instanceof SideFormatter f ? f.foldColor() : null;
        for (Fold fold : folds) {
            if (!fold.closed || fold.anchor(old) >= lines.size())
                continue;
            final String text = Folds.label(fold.count + (fold.from(old) > 0 ? " lines" : " lines above"));
            final VirtualText.Run run = VirtualText.Run.of(text, color).withAction(() -> set(fold, false), "Show them");
            labels.add(VirtualText.eol(lines.get(fold.anchor(old)), List.of(run)));
        }
        side.getVirtualText().set("fold", labels);
    }

    // The fold whose marker is at line, or, with in, one line is in.
    private Fold at(Line line, boolean old, boolean in) {
        final int n = line.lineNumber();
        for (Fold f : folds)
            if (f.anchor(old) == n || in && n >= f.from(old) && n < f.from(old) + f.count)
                return f;
        return null;
    }

    // A side's folds: the diff's, opened and closed on both sides by the
    // fold commands, vim's z keys and the gutter's markers. There are no
    // others to make.
    private final class SideFolds implements Folds {
        private final boolean old;

        SideFolds(boolean old) {
            this.old = old;
        }

        @Override
        public Line[] getFoldRange(Editor editor, Line line) {
            return new Line[0];
        }

        @Override
        public boolean isFoldStart(Buffer buffer, Line line) {
            return at(line, old, false) != null;
        }

        @Override
        public boolean isFolded(Line line) {
            final Fold f = at(line, old, false);
            return f != null && f.closed;
        }

        @Override
        public void toggleFoldAt(Editor editor, Line line) {
            final Fold f = at(line, old, false);
            if (f != null)
                set(f, !f.closed);
        }

        @Override
        public boolean setFoldAt(Editor editor, Line line, Boolean open) {
            final Fold f = at(line, old, true);
            if (f == null)
                editor.status("No fold here");
            else
                set(f, open == null ? !f.closed : !open);
            return true;
        }

        @Override
        public boolean setAllFolds(Editor editor, boolean open) {
            for (Fold f : folds)
                f.closed = !open;
            apply(true, true);
            return true;
        }

        @Override
        public void foldAll(Editor editor) {
            setAllFolds(editor, false);
        }
    }
}
