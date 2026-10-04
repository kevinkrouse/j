/*
 * ListStyles.java
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * listStyles: every style the theme gives, each drawn in itself, with the
 * names it was resolved through and where its color and style came from. For
 * finding out why something is the color it is.
 *
 * With no argument, the shared styles and the current buffer's mode; with
 * "all", every mode's.
 */
public final class ListStyles {
    private ListStyles() {}

    public static void listStyles() {
        listStyles(null);
    }

    public static void listStyles(String arg) {
        final Editor editor = Editor.currentEditor();
        final boolean all = arg != null && arg.trim().equalsIgnoreCase("all");
        final OutputBuffer buf = makeBuffer(editor.getBuffer(), all);
        editor.makeNext(buf);
        editor.displayInOtherWindow(buf);
    }

    /** The listing for buffer's mode, or every mode's. */
    /*package*/ static OutputBuffer makeBuffer(Buffer buffer, boolean all) {
        final List<FormatTable> tables = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final FormatTable builtIn = table(DefaultTheme.getBuiltInNames());
        tables.add(builtIn);
        titles.add("Built-in styles");
        final FormatTable shared = table(DefaultTheme.getSharedStyleNames());
        tables.add(shared);
        titles.add("Shared styles");

        final Set<String> seen = new HashSet<>();
        final Formatter current = buffer.getFormatter();
        if (current != null && !(current instanceof StylesFormatter))
            addTable(tables, titles, seen, current);
        if (all) {
            for (ModeListEntry entry : ModeList.getInstance()) {
                try {
                    final Mode mode = entry.getMode(true);
                    if (mode != null)
                        addTable(tables, titles, seen, mode.getFormatter(buffer));
                }
                catch (RuntimeException e) {
                    Log.debug("listStyles " + entry.getDisplayName() + ": " + e);
                }
            }
        }

        final Preferences prefs = Editor.preferences();
        final String theme = prefs.getStringProperty(Property.THEME);
        final Listing listing = new Listing(tables);
        listing.add("Theme: " + (theme != null ? theme : "none"), TEXT);
        listing.add(
            "Sources dimmed are j's defaults; the others are the " +
                "theme's or your preferences'.",
            MUTED
        );
        for (int i = 0; i < tables.size(); i++) {
            final FormatTable table = tables.get(i);
            listing.add("", TEXT);
            listing.add(
                titles.get(i) +
                    (table.isDarkBackground()
                        ? " (dark background)"
                        : " (light background)"),
                HEADING
            );
            listing.addColumnHeadings();
            for (FormatTableEntry entry : table.getEntries())
                listing.add(entry);
        }

        final OutputBuffer buf = OutputBuffer.getOutputBuffer(listing.text());
        buf.setProperty(Property.RAINBOW_DELIMITERS, false);
        buf.setFormatter(new StylesFormatter(buf, listing, shared));
        buf.setTitle(all ? "listStyles all" : "listStyles");
        return buf;
    }

    // The styles of names as no mode in particular has them.
    private static FormatTable table(List<String> names) {
        final FormatTable table = new FormatTable(null);
        int format = 0;
        for (String name : names)
            table.addEntryFromPrefs(format++, name);
        return table;
    }

    private static void addTable(
        List<FormatTable> tables,
        List<String> titles,
        Set<String> seen,
        Formatter formatter
    ) {
        if (formatter == null)
            return;
        final FormatTable table = formatter.getFormatTable();
        if (table == null || table.getEntries().isEmpty())
            return;
        final String key = String.valueOf(table.getModeName());
        if (seen.add(key)) {
            tables.add(table);
            titles.add(
                table.getModeName() != null
                    ? table.getModeName()
                    : formatter.getClass().getSimpleName()
            );
        }
    }

    // Formats of the listing's text; an entry's name is in FIRST_ROW plus
    // its line number.
    private static final int TEXT = 0;
    private static final int HEADING = 1;
    private static final int MUTED = 2;
    private static final int FIRST_ROW = 3;

    private static final String[] COLUMNS =
        { "name", "color", "style", "links to", "color from", "style from" };

    private static final String GAP = "  ";
    private static final String INDENT = "  ";

    /**
     * The listing's lines, a table for each mode in columns as wide as the
     * widest of any table, so that they line up from one to the next; and
     * for each line, the runs of it to draw other than as text.
     */
    private static final class Listing {
        private final StringBuilder sb = new StringBuilder();
        private final List<FormatTableEntry> entries = new ArrayList<>();
        private final List<int[]> runs = new ArrayList<>();
        private final int[] widths = new int[COLUMNS.length];

        Listing(List<FormatTable> tables) {
            for (int i = 0; i < COLUMNS.length; i++)
                widths[i] = COLUMNS[i].length();
            for (FormatTable table : tables)
                for (FormatTableEntry entry : table.getEntries()) {
                    final String[] cells = cells(entry);
                    for (int i = 0; i < cells.length; i++)
                        widths[i] = Math.max(widths[i], cells[i].length());
                }
        }

        void add(String text, int format) {
            sb.append(text).append('\n');
            entries.add(null);
            runs.add(text.isEmpty() ? null : new int[] { 0, text.length(), format });
        }

        void addColumnHeadings() {
            final int start = sb.length();
            row(COLUMNS);
            final String text = sb.substring(start, sb.length() - 1);
            entries.add(null);
            runs.add(new int[] { 0, text.length(), MUTED });
        }

        // "  heading1   #0550ae  bold italic  → heading  color.heading  default"
        void add(FormatTableEntry entry) {
            final int lineNumber = entries.size();
            final String[] cells = cells(entry);
            final int[] starts = row(cells);
            entries.add(entry);
            // The name in itself; the sources that are only defaults dimmed,
            // so that what the theme set stands out.
            final List<Integer> list = new ArrayList<>();
            // A background's color is for behind text, not text: its swatch
            // shows it, and its name stays readable.
            addRun(
                list,
                starts[0],
                cells[0].length(),
                isBackground(entry) ? TEXT : FIRST_ROW + lineNumber
            );
            addRun(list, starts[3], cells[3].length(), MUTED);
            for (int i = 4; i <= 5; i++)
                if (isDefault(cells[i]))
                    addRun(list, starts[i], cells[i].length(), MUTED);
            final int[] array = new int[list.size()];
            for (int i = 0; i < array.length; i++)
                array[i] = list.get(i);
            runs.add(array);
        }

        private static void addRun(
            List<Integer> list,
            int start,
            int length,
            int format
        ) {
            if (length == 0)
                return;
            list.add(start);
            list.add(start + length);
            list.add(format);
        }

        // Appends the cells padded to their columns; returns where each began.
        private int[] row(String[] cells) {
            final int lineStart = sb.length();
            final int[] starts = new int[cells.length];
            sb.append(INDENT);
            for (int i = 0; i < cells.length; i++) {
                if (i > 0)
                    sb.append(GAP);
                starts[i] = sb.length() - lineStart;
                sb.append(cells[i]);
                if (i < cells.length - 1)
                    for (int n = cells[i].length(); n < widths[i]; n++)
                        sb.append(' ');
            }
            sb.append('\n');
            return starts;
        }

        String text() {
            return sb.toString();
        }

        FormatTableEntry entry(int lineNumber) {
            return lineNumber >= 0 && lineNumber < entries.size()
                ? entries.get(lineNumber)
                : null;
        }

        int[] runs(int lineNumber) {
            return lineNumber >= 0 && lineNumber < runs.size()
                ? runs.get(lineNumber)
                : null;
        }
    }

    private static String[] cells(FormatTableEntry entry) {
        final List<String> names = entry.getNames();
        return new String[] {
            entry.getName(),
            hex(entry.getColor()),
            TextStyle.toString(entry.getStyle()),
            names.size() > 1
                ? "\u2192 " + String.join(" \u2192 ", names.subList(1, names.size()))
                : "",
            source(entry.getColorSource(), entry.getName()),
            source(entry.getStyleSource(), entry.getName()),
        };
    }

    // "color.heading" as it is; "default" for DefaultTheme's for the entry's
    // own name, "default text" for another's; "-" for nothing.
    private static String source(String source, String name) {
        if (source == null)
            return "\u2014";
        if (source.equals("default " + name))
            return "default";
        return source;
    }

    private static boolean isBackground(FormatTableEntry entry) {
        return entry.getName().endsWith("ackground");
    }

    private static boolean isDefault(String source) {
        return source.startsWith("default") || source.equals("\u2014");
    }

    private static String hex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xffffff);
    }

    /**
     * Each entry's name in its own color and style, and its color as a
     * swatch in the gutter; the headings bold, and what is only explanation
     * dimmed.
     */
    private static final class StylesFormatter extends Formatter {
        private final Listing listing;
        private final FormatTable shared;

        StylesFormatter(Buffer buffer, Listing listing, FormatTable shared) {
            this.buffer = buffer;
            this.listing = listing;
            this.shared = shared;
        }

        @Override
        public LineSegmentList formatLine(Line line) {
            clearSegmentList();
            final String text = line.getText();
            final int[] runs = listing.runs(line.lineNumber());
            int at = 0;
            if (runs != null) {
                for (int i = 0; i + 2 < runs.length; i += 3) {
                    final int start = Math.min(runs[i], text.length());
                    final int end = Math.min(runs[i + 1], text.length());
                    if (start > at)
                        addSegment(text, at, start, TEXT);
                    if (end > start)
                        addSegment(text, start, end, runs[i + 2]);
                    at = Math.max(at, end);
                }
            }
            if (at < text.length() || text.isEmpty())
                addSegment(text, at, TEXT);
            return segmentList;
        }

        private FormatTableEntry entryFor(int format) {
            return format >= FIRST_ROW ? listing.entry(format - FIRST_ROW) : null;
        }

        @Override
        public Color getColor(int format) {
            final FormatTableEntry entry = entryFor(format);
            if (entry != null)
                return entry.getColor();
            if (format == MUTED)
                return mutedColor();
            return super.getColor(TEXT);
        }

        // The shared muted, for whichever background the theme has.
        private Color mutedColor() {
            for (FormatTableEntry entry : shared.getEntries())
                if (entry.getName().equals("muted"))
                    return entry.getColor();
            return super.getColor(TEXT);
        }

        @Override
        public int getStyle(int format) {
            if (format == HEADING)
                return TextStyle.BOLD;
            final FormatTableEntry entry = entryFor(format);
            return entry != null ? entry.getStyle() : TextStyle.PLAIN;
        }

        @Override
        public Color getGutterColor(Line line) {
            final FormatTableEntry entry = listing.entry(line.lineNumber());
            return entry != null ? entry.getColor() : null;
        }

        @Override
        public FormatTable getFormatTable() {
            if (formatTable == null) {
                formatTable = new FormatTable(null);
                formatTable.addEntryFromPrefs(TEXT, "text");
            }
            return formatTable;
        }
    }
}
