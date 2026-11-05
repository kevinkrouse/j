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

package org.armedbear.j.mode.compare;

import java.awt.Color;
import java.util.List;
import java.util.function.IntPredicate;
import org.armedbear.j.ChangeColors;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.mode.compare.DiffAlignment.Change;
import org.armedbear.j.mode.compare.DiffAlignment.Kind;

/**
 * A side of a comparison in its file's colors, the file's mode's
 * formatter's, with each line of a change shaded: removed on red at the old
 * side, added on green at the new, changed on blue on both, the words that
 * differ in a changed pair a stronger blue. Unaligned, a rule in the
 * change's color where the other side has lines this one lacks.
 */
final class SideFormatter extends Formatter {
    private final Formatter own;
    private final DiffAlignment alignment;
    // The other side's lines, which a changed line's words are against.
    private final List<String> other;
    private final boolean old;
    // Unaligned, the kind of the change whose lines the other side has
    // before each line, and after the last at the end; else empty.
    private final Kind[] rules;
    private Color added;
    private Color removed;
    private Color changed;
    private Color changedWords;
    private Color filler;
    private Color fold;
    private Color addedEdge;
    private Color removedEdge;
    private Color changedEdge;
    // A changed row's gutter, dimmer than its line, and a picked row's line
    // and gutter, brighter, by kind.
    private Color[] gutters;
    private Color[] pickedShades;
    // Whether the side's line, by number, is picked, as the view it is part of says.
    private IntPredicate picked = n -> false;
    // Run when the colors are made again, as for a new theme: the fillers
    // take the new filler color.
    private Runnable onReset;

    SideFormatter(Formatter own, DiffAlignment alignment, List<String> other, boolean old, boolean aligned) {
        this.own = own;
        this.alignment = alignment;
        this.other = other;
        this.old = old;
        final int size = old ? alignment.oldSize() : alignment.newSize();
        rules = new Kind[aligned ? 0 : size + 1];
        for (Change c : aligned ? List.<Change>of() : alignment.changes())
            if ((old ? c.oldCount() : c.newCount()) == 0)
                rules[old ? c.oldStart() : c.newStart()] = old ? Kind.ADDED : Kind.REMOVED;
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
        final Kind kind = kindOf(line);
        if (kind == Kind.SAME)
            return own.getLineBackground(line);
        return picked.test(line.lineNumber()) ? pickedShades[kind.ordinal()] : shade(kind);
    }

    @Override
    public Color getGutterBackground(Line line) {
        shades();
        final Kind kind = past(line) ? Kind.SAME : kindOf(line);
        if (kind == Kind.SAME)
            return own.getGutterBackground(line);
        return picked.test(line.lineNumber()) ? pickedShades[kind.ordinal()] : gutters[kind.ordinal()];
    }

    /** picked says which of its lines, by number, are picked: drawn brighter. */
    void pickedBy(IntPredicate picked) {
        this.picked = picked;
    }

    @Override
    public Color getLineRule(Line line, boolean bottom) {
        final int n = line.lineNumber();
        // Over the line before which the other side has lines, or under the
        // last for lines after it.
        final int at = !bottom ? n : n == rules.length - 2 ? n + 1 : -1;
        return n >= 0 && at >= 0 && at < rules.length && rules[at] != null ? edge(rules[at]) : null;
    }

    // Across the gutter too, as a changed line's shade is.
    @Override
    public boolean isLineRuleThroughGutter() {
        return true;
    }

    @Override
    public int[] getSpans(Line line) {
        if (kindOf(line) != Kind.CHANGED)
            return null;
        final int pair = alignment.otherLine(line.lineNumber(), old);
        if (pair < 0 || pair >= other.size())
            return null;
        final WordDiff.Result r =
                old ? WordDiff.diff(line.getText(), other.get(pair)) : WordDiff.diff(other.get(pair), line.getText());
        final List<int[]> words = old ? r.removed() : r.added();
        final int[] spans = new int[words.size() * 2];
        for (int i = 0; i < words.size(); i++) {
            spans[2 * i] = words.get(i)[0];
            spans[2 * i + 1] = words.get(i)[1];
        }
        return spans;
    }

    @Override
    public Color getSpanColor(Line line) {
        shades();
        return changedWords;
    }

    /** The color of a fold's label, dimmer than the text. */
    Color foldColor() {
        shades();
        return fold;
    }

    /** A change's lines' color, of kind, and its band's in the track. */
    Color shade(Kind kind) {
        shades();
        return switch (kind) {
            case ADDED -> added;
            case REMOVED -> removed;
            default -> changed;
        };
    }

    /** A band's edge in the track and a rule's color, stronger. */
    Color edge(Kind kind) {
        shades();
        return switch (kind) {
            case ADDED -> addedEdge;
            case REMOVED -> removedEdge;
            default -> changedEdge;
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
        added = DefaultTheme.blend(bg, ChangeColors.added(dark), amount);
        removed = DefaultTheme.blend(bg, ChangeColors.removed(dark), amount);
        changed = DefaultTheme.blend(bg, ChangeColors.modified(dark), amount);
        changedWords = DefaultTheme.blend(bg, ChangeColors.modified(dark), dark ? 0.38 : 0.30);
        filler = DefaultTheme.blend(bg, getColor(0), 0.05);
        fold = DefaultTheme.blend(bg, getColor(0), 0.55);
        addedEdge = ChangeColors.rule(bg, ChangeColors.added(dark));
        removedEdge = ChangeColors.rule(bg, ChangeColors.removed(dark));
        changedEdge = ChangeColors.rule(bg, ChangeColors.modified(dark));
        gutters = new Color[Kind.values().length];
        pickedShades = new Color[Kind.values().length];
        for (Kind k : Kind.values()) {
            final Color c = k == Kind.ADDED ? ChangeColors.added(dark)
                    : k == Kind.REMOVED ? ChangeColors.removed(dark)
                    : ChangeColors.modified(dark);
            gutters[k.ordinal()] = DefaultTheme.blend(bg, c, amount * 0.6);
            pickedShades[k.ordinal()] = ChangeColors.picked(bg, c);
        }
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
