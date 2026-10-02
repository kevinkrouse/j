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
import java.awt.Font;

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

    protected final boolean isKeyword(String s)
    {
        return buffer.isKeyword(s);
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
     * says, the selection's colour, which it has made to stand out.
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

    // Bracket colours by depth, for a light background and a dark: hues far
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
     * The colour of a bracket at a depth, from 1 outermost, for
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
                final Color bg = getBackgroundColor();
                final boolean dark = (bg.getRed() * 299 + bg.getGreen() * 587 +
                                      bg.getBlue() * 114) / 1000 < 128;
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

    public int getStyle(int format)
    {
        FormatTableEntry entry = getFormatTable().lookup(format);
        if (entry != null)
            return entry.getStyle();
        return Font.PLAIN;
    }

    /*
    protected FormatTableEntry getFormatTableEntry(int format)
    {
        return getFormatTable().lookup(format);
    }
    */

    public boolean getUnderline(int format)
    {
        return false;
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
