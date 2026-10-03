/*
 * Formatter.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import org.armedbear.j.util.Utilities;

import java.awt.Color;

public abstract class Formatter implements Constants
{
    protected Buffer buffer;

    protected FormatTable formatTable;
    protected Color colorCaret;
    protected Color colorBackground;
    protected Color colorCurrentLineBackground;
    protected Color colorSelectionBackground;
    protected Color colorMatchingBracketBackground;
    protected Color colorSearchMatchBackground;
    protected Color colorCurrentSearchMatchBackground;

    protected LineSegmentList segmentList = new LineSegmentList();

    public abstract LineSegmentList formatLine(Line line);
    public abstract FormatTable getFormatTable();

    public boolean parseBuffer()
    {
        buffer.setNeedsParsing(false);
        return false;
    }

    // The mode whose language this formats: the buffer's, unless the
    // formatter is lent to a buffer in another, as Markdown lends one a
    // fenced block of Java.
    private Mode languageMode;

    /** Formats language's text, keywords and all, in a buffer of another. */
    public final void setLanguageMode(Mode language)
    {
        languageMode = language;
    }

    protected final Mode getLanguageMode()
    {
        return languageMode != null ? languageMode : buffer.getMode();
    }

    protected final boolean isKeyword(String s)
    {
        return getLanguageMode().isKeyword(s);
    }

    public Color getCaretColor()
    {
        if (colorCaret == null) {
             colorCaret = buffer.getMode().getColorProperty(Property.COLOR_CARET);
             if (colorCaret == null) {
                 colorCaret = buffer.getMode().getColorProperty(Property.COLOR_TEXT);
                 if (colorCaret == null)
                     colorCaret = DefaultTheme.getColor("caret");
             }
        }
        return colorCaret;
    }

    public Color getBackgroundColor()
    {
        if (colorBackground == null) {
            colorBackground = buffer.getMode().getColorProperty(Property.COLOR_BACKGROUND);
            if (colorBackground == null)
                colorBackground = DefaultTheme.getColor("background");
        }
        return colorBackground;
    }

    public Color getCurrentLineBackgroundColor()
    {
        if (colorCurrentLineBackground == null) {
            colorCurrentLineBackground = buffer.getMode().getColorProperty(Property.COLOR_CURRENT_LINE_BACKGROUND);
            if (colorCurrentLineBackground == null)
                colorCurrentLineBackground = DefaultTheme.getColor("currentLineBackground");
        }
        return colorCurrentLineBackground;
    }

    public Color getSelectionBackgroundColor()
    {
        if (colorSelectionBackground == null) {
            colorSelectionBackground = buffer.getMode().getColorProperty(Property.COLOR_SELECTION_BACKGROUND);
            if (colorSelectionBackground == null)
                colorSelectionBackground = DefaultTheme.getColor("selectionBackground");
        }
        return colorSelectionBackground;
    }

    public Color getMatchingBracketBackgroundColor()
    {
        if (colorMatchingBracketBackground == null) {
            colorMatchingBracketBackground = buffer.getMode().getColorProperty(Property.COLOR_MATCHING_BRACKET_BACKGROUND);
            if (colorMatchingBracketBackground == null)
                colorMatchingBracketBackground = DefaultTheme.getColor("matchingBracketBackground");
        }
        return colorMatchingBracketBackground;
    }

    /**
     * Behind the matches of a search, as vim's hlsearch paints them. A theme
     * that does not say falls back to its matching bracket background, which
     * it has already made readable behind its text.
     */
    public Color getSearchMatchBackgroundColor()
    {
        if (colorSearchMatchBackground == null) {
            final Mode mode = buffer.getMode();
            colorSearchMatchBackground =
                mode.getColorProperty(Property.COLOR_SEARCH_MATCH_BACKGROUND);
            if (colorSearchMatchBackground == null)
                colorSearchMatchBackground = mode.getColorProperty(
                    Property.COLOR_MATCHING_BRACKET_BACKGROUND);
            if (colorSearchMatchBackground == null)
                colorSearchMatchBackground =
                    DefaultTheme.getColor("searchMatchBackground");
        }
        return colorSearchMatchBackground;
    }

    /**
     * Behind the match a search being typed has the caret on. Unless a theme
     * says, the selection's color, which it has made to stand out.
     */
    public Color getCurrentSearchMatchBackgroundColor()
    {
        if (colorCurrentSearchMatchBackground == null) {
            colorCurrentSearchMatchBackground = buffer.getMode()
                .getColorProperty(Property.COLOR_CURRENT_SEARCH_MATCH_BACKGROUND);
            if (colorCurrentSearchMatchBackground == null)
                colorCurrentSearchMatchBackground =
                    getSelectionBackgroundColor();
        }
        return colorCurrentSearchMatchBackground;
    }

    // Bracket colors by depth, for a light background and a dark: hues far
    // enough apart that neighbouring depths read as different.
    private static final int[][] RAINBOW_LIGHT = {
        {0x70, 0x70, 0x70}, {0x22, 0x88, 0xcc}, {0x99, 0x66, 0xcc},
        {0x00, 0x88, 0x55}, {0xcc, 0x66, 0x00}, {0x00, 0x66, 0x99},
        {0xaa, 0x44, 0x99}, {0x66, 0x88, 0x00}, {0x88, 0x55, 0x33},
    };
    private static final int[][] RAINBOW_DARK = {
        {0xbb, 0xbb, 0xbb}, {0x77, 0xbb, 0xff}, {0xcc, 0x99, 0xff},
        {0x66, 0xdd, 0x99}, {0xff, 0xaa, 0x55}, {0x55, 0xcc, 0xdd},
        {0xff, 0x88, 0xcc}, {0xbb, 0xdd, 0x55}, {0xdd, 0xaa, 0x88},
    };

    private Color[] rainbowColors;
    private Color unmatchedDelimiterColor;

    /**
     * The color of a bracket at a depth, from 1 outermost, for
     * rainbowDelimiters: color.rainbowDelimiter1, 2 and on as far as a theme
     * sets them, round again after the last. Without them, a palette made for
     * the background. Depth 0 is a closing bracket nothing opened, in
     * color.unmatchedDelimiter.
     */
    public Color getRainbowColor(int depth)
    {
        if (rainbowColors == null) {
            final Preferences prefs = Editor.preferences();
            java.util.ArrayList<Color> colors = new java.util.ArrayList<>();
            Color c;
            while ((c = prefs.getColorProperty("color.rainbowDelimiter" +
                                                (colors.size() + 1))) != null)
                colors.add(c);
            if (colors.isEmpty()) {
                final boolean dark = DefaultTheme.isDark(getBackgroundColor());
                for (int[] rgb : dark ? RAINBOW_DARK : RAINBOW_LIGHT)
                    colors.add(new Color(rgb[0], rgb[1], rgb[2]));
            }
            rainbowColors = colors.toArray(new Color[colors.size()]);
            unmatchedDelimiterColor =
                prefs.getColorProperty("color.unmatchedDelimiter");
            if (unmatchedDelimiterColor == null)
                unmatchedDelimiterColor = new Color(0xdd, 0x22, 0x22);
        }
        if (depth <= 0)
            return unmatchedDelimiterColor;
        return rainbowColors[(depth - 1) % rainbowColors.length];
    }

    public Color getColor(int format)
    {
        FormatTableEntry entry = getFormatTable().lookup(format);
        if (entry != null)
            return entry.getColor();
        return DefaultTheme.getColor("text");
    }

    /** The format's TextStyle: bold, italic, underline, strikethrough. */
    public int getStyle(int format)
    {
        FormatTableEntry entry = getFormatTable().lookup(format);
        if (entry != null)
            return entry.getStyle();
        return TextStyle.PLAIN;
    }

    /*
    protected FormatTableEntry getFormatTableEntry(int format)
    {
        return getFormatTable().lookup(format);
    }
    */

    /**
     * A color to show a swatch of in the gutter beside line, as for a line
     * that defines one, or null.
     */
    public Color getGutterColor(Line line)
    {
        return null;
    }

    /**
     * The background of a whole line, from the gutter to the right edge, as
     * a code block's is shaded; or null for the display's own.
     */
    public Color getLineBackground(Line line)
    {
        return null;
    }

    /**
     * The background behind the text of a format, as inline code's is
     * shaded; or null for none.
     */
    public Color getRunBackground(int format)
    {
        return null;
    }

    /**
     * A shade of the background that sets text apart without fighting it,
     * as code's: a theme's color.thing, if it has one. On a light background,
     * half as far from it as the current line's highlight, so as not to be
     * mistaken for it, and a little cool, as GitHub's code is, where the
     * highlight is usually gray. On a dark one, the background with a little
     * of the text mixed in.
     */
    protected Color getShade(String modeName, String thing)
    {
        final Preferences prefs = Editor.preferences();
        Color color = modeName != null
            ? prefs.getColorProperty(modeName + ".color." + thing) : null;
        if (color == null)
            color = prefs.getColorProperty("color." + thing);
        if (color != null)
            return color;
        final Color bg = getBackgroundColor();
        if (DefaultTheme.isDark(bg)) {
            final Color fg = getColor(0);
            final double amount = 0.12;
            return new Color(
                (int) Math.round(bg.getRed() + (fg.getRed() - bg.getRed()) * amount),
                (int) Math.round(bg.getGreen() + (fg.getGreen() - bg.getGreen()) * amount),
                (int) Math.round(bg.getBlue() + (fg.getBlue() - bg.getBlue()) * amount));
        }
        // How much darker the current line is, halved; at least enough to
        // see where a theme's current line is not darker at all.
        final Color line = getCurrentLineBackgroundColor();
        final double step = Math.max(6, ((bg.getRed() - line.getRed())
            + (bg.getGreen() - line.getGreen())
            + (bg.getBlue() - line.getBlue())) / 6.0);
        // Darker in red than in blue: cool.
        return new Color(darker(bg.getRed(), step), darker(bg.getGreen(), step * 0.78),
                         darker(bg.getBlue(), step * 0.55));
    }

    private static int darker(int value, double by)
    {
        return Math.max(0, (int) Math.round(value - by));
    }

    /**
     * Whether this formatter marks markup to hide (LineSegment.isHidden),
     * so that the display shows it only around the caret. A formatter that
     * can hide markup says so, as conceals lets it.
     */
    public boolean hidesMarkup()
    {
        return false;
    }

    /**
     * Whether the conceal property, a list such as "markup,headings", names
     * kind: what markup of its own a formatter may hide. "none", or nothing,
     * hides none.
     */
    protected final boolean conceals(String kind)
    {
        final String value = buffer.getStringProperty(Property.CONCEAL);
        if (value == null)
            return false;
        for (String name : value.split("[\\s,]+"))
            if (name.equalsIgnoreCase(kind))
                return true;
        return false;
    }

    /**
     * Adds a segment the display hides unless the caret is in item, a
     * number of the line's own from 1, or LineSegment.BLOCK.
     */
    protected final void addSegment(String text, int begin, int end, int format,
                                    boolean hidden, int item)
    {
        final LineSegment segment = new LineSegment(text, begin, end, format);
        segment.setHidden(hidden);
        segment.setItem(item);
        segmentList.addSegment(segment);
    }

    /**
     * The first and last lines of the block line is part of whose hidden
     * markup shows together, as a fence's opening and closing lines do with
     * the caret anywhere in it; or null.
     */
    public Line[] getHiddenBlock(Line line)
    {
        return null;
    }

    public boolean getUnderline(int format)
    {
        return (getStyle(format) & TextStyle.UNDERLINE) != 0;
    }

    public void reset()
    {
        colorCaret = null;
        colorBackground = null;
        colorCurrentLineBackground = null;
        colorSelectionBackground = null;
        colorMatchingBracketBackground = null;
        colorSearchMatchBackground = null;
        colorCurrentSearchMatchBackground = null;
        rainbowColors = null;
        unmatchedDelimiterColor = null;
        formatTable = null;
    }

    protected final void addSegment(String text, int begin, int end, int format)
    {
        segmentList.addSegment(new LineSegment(text, begin, end, format));
    }

    protected final void addSegment(String text, int begin, int format)
    {
        segmentList.addSegment(new LineSegment(text, begin, text.length(), format));
    }

    protected final void addSegment(String text, int format)
    {
        segmentList.addSegment(new LineSegment(text, format));
    }

    protected final LineSegment getLastSegment()
    {
        return segmentList.getLastSegment();
    }

    protected final void clearSegmentList()
    {
        segmentList.clear();
    }

    protected final String getDetabbedText(Line line)
    {
        if (Editor.tabsAreVisible())
            return Utilities.makeTabsVisible(line.getText(), buffer.getTabWidth());
        return Utilities.detab(line.getText(), buffer.getTabWidth());
    }
}
