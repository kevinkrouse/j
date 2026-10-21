/*
 * SideFormatter.java
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

package org.armedbear.j.vcs.sidebyside;

import java.awt.Color;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.vcs.VcsColors;
import org.armedbear.j.vcs.diff.DiffAlignment;
import org.armedbear.j.vcs.diff.DiffAlignment.Kind;

/**
 * A side of a side-by-side diff in its file's colors, the file's mode's
 * formatter's, with each line of a change shaded: removed on red at the old
 * side, added on green at the new, changed on blue on both.
 */
final class SideFormatter extends Formatter {
    private final Formatter own;
    private final DiffAlignment alignment;
    private final boolean old;
    private Color added;
    private Color removed;
    private Color changed;
    private Color filler;
    // Run when the colors are made again, as for a new theme: the fillers
    // take the new filler color.
    private Runnable onReset;

    SideFormatter(Formatter own, DiffAlignment alignment, boolean old) {
        this.own = own;
        this.alignment = alignment;
        this.old = old;
    }

    /** The mode's formatter it is over. */
    Formatter own() {
        return own;
    }

    void onReset(Runnable r) {
        onReset = r;
    }

    // Whether line is past the side's lines: the blank line an empty side has.
    private boolean past(Line line) {
        return line.lineNumber() >= (old ? alignment.oldSize() : alignment.newSize());
    }

    /** The kind of the row line is at, SAME past the side's lines. */
    Kind kindOf(Line line) {
        final int n = line.lineNumber();
        if (n < 0 || past(line))
            return Kind.SAME;
        return alignment.rows().get(old ? alignment.rowOfOld(n) : alignment.rowOfNew(n)).kind();
    }

    @Override
    public Color getLineBackground(Line line) {
        shades();
        // An empty side's blank line is a filler's row.
        if (past(line) && alignment.rows().size() > 0)
            return filler;
        return switch (kindOf(line)) {
            case ADDED -> added;
            case REMOVED -> removed;
            case CHANGED -> changed;
            case SAME -> own.getLineBackground(line);
        };
    }

    /** The background of a filler row, where the other side has lines. */
    Color fillerBackground() {
        shades();
        return filler;
    }

    private void shades() {
        if (added != null)
            return;
        final Color bg = getBackgroundColor();
        final boolean dark = DefaultTheme.isDark(bg);
        final double amount = dark ? 0.16 : 0.12;
        added = DefaultTheme.blend(bg, VcsColors.added(dark), amount);
        removed = DefaultTheme.blend(bg, VcsColors.removed(dark), amount);
        changed = DefaultTheme.blend(bg, VcsColors.modified(dark), amount);
        filler = DefaultTheme.blend(bg, getColor(0), 0.05);
    }

    // The rest is the file's formatter's.

    @Override
    public LineSegmentList formatLine(Line line) {
        return own.formatLine(line);
    }

    @Override
    public FormatTable getFormatTable() {
        return own.getFormatTable();
    }

    @Override
    public boolean parseBuffer() {
        return own.parseBuffer();
    }

    @Override
    public Color getCaretColor() {
        return own.getCaretColor();
    }

    @Override
    public Color getBackgroundColor() {
        return own.getBackgroundColor();
    }

    @Override
    public Color getCurrentLineBackgroundColor() {
        return own.getCurrentLineBackgroundColor();
    }

    @Override
    public Color getSelectionBackgroundColor() {
        return own.getSelectionBackgroundColor();
    }

    @Override
    public Color getMatchingBracketBackgroundColor() {
        return own.getMatchingBracketBackgroundColor();
    }

    @Override
    public Color getSearchMatchBackgroundColor() {
        return own.getSearchMatchBackgroundColor();
    }

    @Override
    public Color getCurrentSearchMatchBackgroundColor() {
        return own.getCurrentSearchMatchBackgroundColor();
    }

    @Override
    public Color getRainbowColor(int depth) {
        return own.getRainbowColor(depth);
    }

    @Override
    public Color getColor(int format) {
        return own.getColor(format);
    }

    @Override
    public int getStyle(int format) {
        return own.getStyle(format);
    }

    @Override
    public Color getGutterColor(Line line) {
        return own.getGutterColor(line);
    }

    @Override
    public Color getHoverLinkColor() {
        return own.getHoverLinkColor();
    }

    @Override
    public boolean endsOnColumn(int format) {
        return own.endsOnColumn(format);
    }

    @Override
    public Color getRunBackground(int format) {
        return own.getRunBackground(format);
    }

    @Override
    public boolean hidesMarkup() {
        return own.hidesMarkup();
    }

    @Override
    public Line[] getHiddenBlock(Line line) {
        return own.getHiddenBlock(line);
    }

    @Override
    public boolean getUnderline(int format) {
        return own.getUnderline(format);
    }

    @Override
    public void reset() {
        own.reset();
        added = null;
        if (onReset != null)
            onReset.run();
    }
}
