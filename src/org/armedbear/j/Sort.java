/*
 * Sort.java
 *
 * Copyright (C) 2002-2004 Peter Graves
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.undo.CompoundEdit;

/**
 * Putting lines in order, for {@code sortLines} and for vim's {@code :sort}.
 *
 * The comparison is over a <em>key</em> taken from each line rather than over
 * the line itself, which is what every option here really selects: a pattern
 * picks out the text to compare, and a radix reads a number out of it. With
 * no options at all the key is the whole line, which is what {@code sortLines}
 * has always done.
 */
public final class Sort
{
    private Sort()
    {
    }

    /**
     * How to compare, in vim's {@code :sort} vocabulary.
     *
     * The flag letters are vim's, so a j key map and an ex command describe
     * the same sort the same way.
     */
    public static final class Options
    {
        /** i -- compare without regard to case. */
        public boolean ignoreCase;
        /** u -- drop a line whose key repeats the one before it. */
        public boolean unique;
        /** ! -- reverse the result. */
        public boolean reverse;
        /** r -- compare the match itself rather than what follows it. */
        public boolean useMatch;
        /** n, x, o, b -- the base to read a number in, or 0 for text. */
        public int radix;
        /** f -- compare the first floating point number in the line. */
        public boolean real;
        /** The pattern picking out the key, or null for the whole line. */
        public Pattern pattern;

        /**
         * Reads a vim {@code :sort} argument: flag letters, then an optional
         * {@code /pattern/}.
         *
         * @throws IllegalArgumentException for a flag that is not one of
         *         vim's, so a typo is reported rather than ignored
         */
        public static Options parse(String args)
        {
            final Options options = new Options();
            final String s = args == null ? "" : args.trim();
            final int slash = s.indexOf('/');
            final String flags = (slash < 0 ? s : s.substring(0, slash))
                                 .replace(" ", "");
            char kind = 0;
            for (int i = 0; i < flags.length(); i++) {
                final char c = flags.charAt(i);
                switch (c) {
                    case 'i': options.ignoreCase = true; break;
                    case 'u': options.unique = true; break;
                    case 'r': options.useMatch = true; break;
                    // l sorts by the locale. The comparison here is by code
                    // point, which is what the C locale gives, so it is
                    // accepted and changes nothing.
                    case 'l': break;
                    case 'n': case 'f': case 'x': case 'o': case 'b':
                        // Mutually exclusive, and vim says so rather than
                        // letting the last one win.
                        if (kind != 0 && kind != c)
                            throw new IllegalArgumentException(
                                "E474: Invalid argument");
                        kind = c;
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "E475: Invalid argument: " + c);
                }
            }
            switch (kind) {
                case 'x': options.radix = 16; break;
                case 'o': options.radix = 8; break;
                case 'b': options.radix = 2; break;
                case 'n': options.radix = 10; break;
                case 'f': options.real = true; break;
                default: break;
            }
            if (slash >= 0) {
                final int close = s.indexOf('/', slash + 1);
                final String source = close < 0 ? s.substring(slash + 1)
                                                : s.substring(slash + 1, close);
                if (!source.isEmpty())
                    options.pattern = compile(source);
            }
            return options;
        }

        private static Pattern compile(String source)
        {
            try {
                return Pattern.compile(source);
            }
            catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                    "E486: Pattern not found: " + source);
            }
        }
    }

    /** A line and the key it is compared by. */
    private static final class Entry
    {
        final String text;
        final String key;
        /** The number read from the key, or null when the sort is textual. */
        final Long number;
        /** The float read from the key, for f. */
        double real;
        /** False when a radix is in force and the line has no number. */
        final boolean keyed;

        Entry(String text, String key, Long number, boolean keyed)
        {
            this.text = text;
            this.key = key;
            this.number = number;
            this.keyed = keyed;
        }
    }

    /**
     * The {@code sortLines} command: sorts the lines the selection covers.
     *
     * A whole-line comparison, as it has always been. The parameterised form
     * takes vim's flags.
     */
    public static void sortLines()
    {
        sortLines("");
    }

    /**
     * {@code sortLines} with vim's {@code :sort} flags, as in
     * {@code sortLines n} or {@code sortLines ru /:/}.
     */
    public static void sortLines(String parameters)
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getMark() == null)
            return;
        final Region region = new Region(editor);
        if (region.getEndLineNumber() - region.getBeginLineNumber() < 2)
            return;
        if (!editor.checkReadOnly())
            return;
        final Options options;
        try {
            options = Options.parse(parameters);
        }
        catch (IllegalArgumentException e) {
            editor.status(e.getMessage());
            return;
        }
        final Buffer buffer = editor.getBuffer();
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            // getEndLine() is the line after the last one selected, so the
            // last line to sort is the one before it.
            sortLines(editor, region.getBeginLine(),
                      region.getEndLine().previous(), options);
        }
        finally {
            buffer.unlockWrite();
        }
    }

    /**
     * Sorts a span of lines, both ends included.
     *
     * The text of each line is replaced where it stands rather than the span
     * being cut and re-inserted, which keeps this clear of the end-of-buffer
     * newline rules and of the caret column entirely. Only {@code unique}
     * makes the span shorter, and the lines it leaves over are removed at the
     * end.
     *
     * <p>The caller arranges the write lock; the {@code sortLines} command
     * above does, and a modal command runs under the dispatcher's.
     *
     * @return the number of lines removed
     */
    public static int sortLines(Editor editor, Line first, Line last,
                                Options options)
    {
        if (first == null || last == null)
            return 0;
        final Buffer buffer = editor.getBuffer();
        final List<Entry> entries = new ArrayList<Entry>();
        for (Line line = first; line != null; line = line.next()) {
            entries.add(entryFor(text(line), options));
            if (line == last)
                break;
        }
        sort(entries, options);

        final List<String> wanted = new ArrayList<String>(entries.size());
        String previous = null;
        for (Entry e : entries) {
            // Equal lines, not equal keys: :sort u /:/ keeps x:1 and y:1.
            if (options.unique && previous != null
                && (options.ignoreCase ? previous.equalsIgnoreCase(e.text)
                                       : previous.equals(e.text)))
                continue;
            previous = e.text;
            wanted.add(e.text);
        }

        final int removed = entries.size() - wanted.size();
        // One undo step for the whole sort. The rewrite and the removal that
        // a unique sort needs are two different mechanisms -- setText under
        // an UndoLineEdit, and a region delete -- and left as two steps one
        // undo puts back the old text under the new line count, which is not
        // a state the buffer was ever in.
        final CompoundEdit outer =
            removed > 0 ? buffer.beginCompoundEdit() : null;
        try {
            CompoundEdit compoundEdit = new CompoundEdit();
            compoundEdit.addEdit(new UndoMove(editor));
            boolean changed = false;
            Line line = first;
            int i = 0;
            for (; i < wanted.size() && line != null; i++, line = line.next()) {
                if (!wanted.get(i).equals(text(line))) {
                    compoundEdit.addEdit(new UndoLineEdit(buffer, line));
                    line.setText(wanted.get(i));
                    changed = true;
                }
                if (line == last)
                    break;
            }
            compoundEdit.end();
            if (changed) {
                buffer.addEdit(compoundEdit);
                buffer.modified();
            }
            if (removed > 0)
                removeLines(editor, wanted.size(), first, last);
        }
        finally {
            if (outer != null)
                buffer.endCompoundEdit(outer);
        }

        buffer.setNeedsParsing(true);
        buffer.getFormatter().parseBuffer();
        buffer.repaint();
        return removed;
    }

    /** Takes away the lines a unique sort left over, at the end of the span. */
    private static void removeLines(Editor editor, int keep, Line first,
                                    Line last)
    {
        Line from = first;
        for (int i = 0; i < keep && from != null; i++)
            from = from.next();
        if (from == null)
            return;
        // The span ends at the start of the line after the last one, so that
        // the newlines go with the lines. At the end of the buffer there is
        // no such line, so the newline before the span goes instead.
        final Line after = last.next();
        final Editor ed = editor;
        if (after != null) {
            ed.setMark(new Position(after, 0));
            ed.setDot(from, 0);
        } else {
            final Line before = from.previous();
            ed.setMark(new Position(last, last.length()));
            ed.setDot(before != null ? before : from,
                      before != null ? before.length() : 0);
        }
        ed.moveCaretToDotCol();
        ed.deleteRegion();
        ed.setMark(null);
    }

    private static String text(Line line)
    {
        return line.getText() == null ? "" : line.getText();
    }

    /**
     * The key a line is compared by.
     *
     * With a pattern, {@code useMatch} compares the match itself and its
     * absence compares what follows the match -- which is the point of
     * {@code sort /.*:/} over a file of prefixed lines.
     */
    private static Entry entryFor(String text, Options options)
    {
        String key = text;
        if (options.pattern != null) {
            final Matcher matcher = options.pattern.matcher(text);
            key = !matcher.find() ? ""
                  : options.useMatch ? matcher.group()
                                     : text.substring(matcher.end());
        }
        if (options.real) {
            // Unlike n, a line with no float is not put first: it counts as
            // 0.0, which is where nvim puts "x" among -25, 0.1 and 0.5.
            final Entry e = new Entry(text, key, null, true);
            e.real = realIn(key);
            return e;
        }
        if (options.radix != 0) {
            // A line with no number of this base sorts before every line that
            // has one, keeping its place among the others. Not the same as
            // counting it zero, which would put it after a negative.
            final Long number = numberIn(key, options.radix);
            return new Entry(text, key, number, number != null);
        }
        // A line the pattern did not match is not a separate category: its
        // key is just the empty string, which sorts first anyway but sorts
        // *with* the lines whose match left nothing after it.
        return new Entry(text, options.ignoreCase ? key.toLowerCase() : key,
                         null, true);
    }

    /**
     * The first number of this base in the text, or null if there is none.
     *
     * Scanned for rather than required, so "d3" and " s5" sort as 3 and 5. A
     * minus sign directly in front counts, which is what puts "z-9" first.
     */
    private static Long numberIn(String text, int radix)
    {
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), radix) < 0)
                continue;
            int start = i;
            // 0x before a hex number belongs to it, rather than being read as
            // the digit zero followed by a stray x.
            if (radix == 16 && text.charAt(i) == '0' && i + 1 < text.length()
                && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')
                && i + 2 < text.length()
                && Character.digit(text.charAt(i + 2), 16) >= 0)
                start = i + 2;
            int end = start;
            while (end < text.length()
                   && Character.digit(text.charAt(end), radix) >= 0)
                ++end;
            final boolean negative = start > 0 && text.charAt(start - 1) == '-';
            try {
                final long value =
                    Long.parseLong(text.substring(start, end), radix);
                return negative ? -value : value;
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The first floating point number in the text, or 0.0 if there is none. */
    private static double realIn(String text)
    {
        final Matcher m = FLOAT.matcher(text);
        if (!m.find())
            return 0.0;
        try {
            return Double.parseDouble(m.group());
        }
        catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static final Pattern FLOAT = Pattern.compile(
        "[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");

    /**
     * Orders the entries, keeping equal ones as they were.
     *
     * Reversing reverses the result rather than the comparison, so the lines
     * with no number stay together at what is now the end.
     */
    private static void sort(List<Entry> entries, Options options)
    {
        entries.sort(new EntryComparator(options.radix != 0, options.real));
        if (options.reverse)
            Collections.reverse(entries);
    }

    private static class EntryComparator implements Comparator<Entry>
    {
        private final boolean numeric;
        private final boolean real;

        EntryComparator(boolean numeric, boolean real)
        {
            this.numeric = numeric;
            this.real = real;
        }

        public final int compare(Entry a, Entry b)
        {
            if (real)
                return Double.compare(a.real, b.real);
            if (!a.keyed || !b.keyed)
                return a.keyed == b.keyed ? 0 : (a.keyed ? 1 : -1);
            if (numeric)
                return Long.compare(a.number, b.number);
            return a.key.compareTo(b.key);
        }
    }
}
