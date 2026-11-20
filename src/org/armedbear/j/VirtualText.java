/*
 * VirtualText.java
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

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Text the display draws but the buffer does not hold: after a line's end,
 * at the window's right edge, as whole rows above or below a line or over
 * the buffer's first, or as a picture replacing a range of lines. The
 * caret, search, copying and saving never see it. Kept in the buffer's
 * {@link VirtualTextLayer}.
 *
 * @param line the line it belongs to, which it goes with when the line is
 *     deleted; null for TOP. For REPLACE, the first line of the range.
 * @param rows its text: one row for EOL and RIGHT, any number for the rest
 * @param priority lower first: nearer the text for EOL, higher for ABOVE
 * @param background the rows' background, or null for the window's
 * @param scrolls whether ABOVE and BELOW rows scroll sideways with the text
 * @param foreground for BACKGROUND, the line's text color, or null for its own
 * @param last for REPLACE, the last line of the range it replaces; null otherwise
 * @param painter for REPLACE, what draws the picture over the range; null otherwise
 */
public record VirtualText(Line line, Placement placement, List<List<Run>> rows, int priority, Color background,
        boolean scrolls, Color foreground, Line last, Painter painter) {

    public enum Placement {
        /** After the end of the line, a space from it. */
        EOL,
        /** At the window's right edge, left out if it would cover the text. */
        RIGHT,
        /** Whole rows over the line. */
        ABOVE,
        /** Whole rows under the line. */
        BELOW,
        /** Whole rows over the buffer's first line, which scroll away with it. */
        TOP,
        /** The line's background, behind its text, and maybe its text's color; no rows. */
        BACKGROUND,
        /**
         * A picture drawn in place of a range of lines, from {@code line} to
         * {@code last}. Collapsed (the picture shows) while the caret is
         * outside the range; revealed (the lines draw their own text) when
         * the caret is inside it. The buffer's text is never touched.
         */
        REPLACE
    }

    /**
     * Draws a REPLACE picture over a line range and reports how tall it is.
     * Called on the EDT during paint, so it must be cheap: draw what is
     * already loaded, never render or touch the filesystem.
     */
    public interface Painter {
        /** Paints the picture into the given box, in window coordinates. */
        void paint(Graphics2D g, int x, int y, int width, int height);

        /** The picture's height in pixels at the current scale. */
        int getHeight();
    }

    /**
     * A piece of virtual text in one color and style.
     *
     * @param color null for dimmed text
     * @param style {@link TextStyle} bits
     * @param action run when the run is clicked, or null
     * @param tooltip shown when the mouse is over it, or null
     */
    public record Run(String text, Color color, int style, Runnable action, String tooltip) {
        public Run {
            if (text == null)
                throw new IllegalArgumentException("null text");
        }

        public static Run of(String text) {
            return new Run(text, null, TextStyle.PLAIN, null, null);
        }

        public static Run of(String text, Color color) {
            return new Run(text, color, TextStyle.PLAIN, null, null);
        }

        public static Run bold(String text) {
            return new Run(text, null, TextStyle.BOLD, null, null);
        }

        public Run withAction(Runnable action, String tooltip) {
            return new Run(text, color, style, action, tooltip);
        }
    }

    public VirtualText {
        if (placement == null || rows == null)
            throw new IllegalArgumentException("placement and rows are required");
        if ((placement == Placement.TOP) != (line == null))
            throw new IllegalArgumentException(placement + (line == null ? " needs a line" : " takes no line"));
        if ((placement == Placement.EOL || placement == Placement.RIGHT) && rows.size() != 1)
            throw new IllegalArgumentException(placement + " is one row");
        if (placement == Placement.BACKGROUND && (!rows.isEmpty() || background == null))
            throw new IllegalArgumentException(placement + " takes no rows and a color");
        if (placement != Placement.BACKGROUND && foreground != null)
            throw new IllegalArgumentException(placement + " takes no foreground");
        if (placement == Placement.REPLACE) {
            if (last == null || painter == null)
                throw new IllegalArgumentException("REPLACE needs a last line and a painter");
            if (!rows.isEmpty())
                throw new IllegalArgumentException("REPLACE takes no rows");
        } else if (last != null || painter != null) {
            throw new IllegalArgumentException(placement + " takes no last line or painter");
        }
        rows = rows.stream().map(List::copyOf).toList();
    }

    public static VirtualText eol(Line line, List<Run> runs) {
        return new VirtualText(line, Placement.EOL, List.of(runs), 0, null, false, null, null, null);
    }

    public static VirtualText right(Line line, List<Run> runs) {
        return new VirtualText(line, Placement.RIGHT, List.of(runs), 0, null, false, null, null, null);
    }

    public static VirtualText above(Line line, List<List<Run>> rows) {
        return new VirtualText(line, Placement.ABOVE, rows, 0, null, true, null, null, null);
    }

    public static VirtualText below(Line line, List<List<Run>> rows) {
        return new VirtualText(line, Placement.BELOW, rows, 0, null, true, null, null, null);
    }

    public static VirtualText top(List<List<Run>> rows) {
        return new VirtualText(null, Placement.TOP, rows, 0, null, false, null, null, null);
    }

    public static VirtualText background(Line line, Color color) {
        return background(line, color, null);
    }

    /** line's background, and its text in foreground. */
    public static VirtualText background(Line line, Color color, Color foreground) {
        return new VirtualText(line, Placement.BACKGROUND, List.of(), 0, color, false, foreground, null, null);
    }

    /** A picture replacing the lines from {@code first} to {@code last}. */
    public static VirtualText replace(Line first, Line last, Painter painter) {
        return new VirtualText(first, Placement.REPLACE, List.of(), 0, null, false, null, last, painter);
    }

    public VirtualText withPriority(int priority) {
        return new VirtualText(line, placement, rows, priority, background, scrolls, foreground, last, painter);
    }

    public VirtualText withBackground(Color background) {
        return new VirtualText(line, placement, rows, priority, background, scrolls, foreground, last, painter);
    }

    /** The rows it adds to the window's height. REPLACE adds none; its
     * height is the painter's pixels, accounted for separately. */
    public int height() {
        return switch (placement) {
            case ABOVE, BELOW, TOP -> rows.size();
            default -> 0;
        };
    }

    /** Whether line is within this REPLACE's range, inclusive. */
    public boolean covers(Line l) {
        if (placement != Placement.REPLACE || l == null)
            return false;
        return l.lineNumber() >= line.lineNumber() && l.lineNumber() <= last.lineNumber();
    }

    /** The text of a row, without styles. */
    public static String text(List<Run> row) {
        final StringBuilder sb = new StringBuilder();
        for (Run run : row)
            sb.append(run.text());
        return sb.toString();
    }
}
