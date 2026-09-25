/*
 * VimExSort.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * {@code :sort} -- put the lines of a range in order.
 *
 * The comparison is over a <em>key</em> taken from each line rather than over
 * the line itself, which is what every flag here really selects: a pattern
 * picks out the text to compare, and a number flag reads a number out of it.
 * The two kinds of key answer "no key" differently, and vim really does mean
 * both: a line with no <em>number</em> sorts before every line that has one,
 * keeping the order it was in, while a line the <em>pattern</em> missed is
 * simply compared as the empty string, alongside the lines whose match left
 * nothing after it.
 *
 * <p>The sort is stable, so equal keys keep their order too.
 */
final class VimExSort
{
    private VimExSort()
    {
    }

    /** A line and the key it is compared by. */
    private static final class Entry
    {
        final String text;
        final String key;
        /** The number read from the key, or null when the flags want text. */
        final Long number;
        /** False when a number flag is in force and the line has no number. */
        final boolean keyed;

        Entry(String text, String key, Long number, boolean keyed)
        {
            this.text = text;
            this.key = key;
            this.number = number;
            this.keyed = keyed;
        }
    }

    static void run(Editor editor, VimState state, VimEx.Command command)
        throws VimEx.BadCommand
    {
        // :sort with no range takes the whole buffer, where :d and :y take
        // the current line.
        final VimEx.Range range = command.range.given
            ? command.range
            : new VimEx.Range(1, Math.max(1, editor.getBuffer().getLineCount()),
                              true);

        final String args = command.args.trim();
        final String flags = flagsOf(args);
        final String pattern = patternOf(args);
        check(flags);

        final boolean ignoreCase = flags.indexOf('i') >= 0;
        final boolean unique = flags.indexOf('u') >= 0;
        final boolean useMatch = flags.indexOf('r') >= 0;
        final int radix = radixOf(flags);

        final Pattern regex = compile(pattern);
        final List<Entry> entries = new ArrayList<Entry>();
        for (int n = range.first; n <= range.last; n++) {
            final Line line = VimEx.lineAt(editor, n);
            if (line == null)
                throw new VimEx.BadCommand("E16: Invalid range");
            final String text = line.getText() == null ? "" : line.getText();
            entries.add(entryFor(text, regex, useMatch, radix, ignoreCase));
        }

        sort(entries, radix != 0, command.bang);
        final List<String> result = new ArrayList<String>(entries.size());
        String previous = null;
        for (Entry e : entries) {
            // :sort u drops a line whose comparison key repeats the one
            // before it, which after sorting means every duplicate.
            if (unique && previous != null && previous.equals(e.key))
                continue;
            previous = e.key;
            result.add(e.text);
        }
        write(editor, state, range, result);
    }

    // ------------------------------------------------------------- parsing

    /** The letters before any pattern. */
    private static String flagsOf(String args)
    {
        final int slash = args.indexOf('/');
        final String head = slash < 0 ? args : args.substring(0, slash);
        return head.replace(" ", "");
    }

    /** The {@code /pattern/}, or null when there is none. */
    private static String patternOf(String args)
    {
        final int open = args.indexOf('/');
        if (open < 0)
            return null;
        final int close = args.indexOf('/', open + 1);
        return close < 0 ? args.substring(open + 1)
                         : args.substring(open + 1, close);
    }

    private static void check(String flags) throws VimEx.BadCommand
    {
        for (int i = 0; i < flags.length(); i++) {
            final char c = flags.charAt(i);
            if ("iurnxobf".indexOf(c) < 0)
                throw new VimEx.BadCommand("E475: Invalid argument: " + c);
        }
    }

    /** The base a number flag asks for, or 0 when the sort is textual. */
    private static int radixOf(String flags)
    {
        if (flags.indexOf('x') >= 0)
            return 16;
        if (flags.indexOf('o') >= 0)
            return 8;
        if (flags.indexOf('b') >= 0)
            return 2;
        if (flags.indexOf('n') >= 0 || flags.indexOf('f') >= 0)
            return 10;
        return 0;
    }

    private static Pattern compile(String pattern) throws VimEx.BadCommand
    {
        if (pattern == null || pattern.isEmpty())
            return null;
        try {
            return Pattern.compile(VimSearch.toJavaRegex(pattern));
        }
        catch (PatternSyntaxException e) {
            throw new VimEx.BadCommand("E486: Pattern not found: " + pattern);
        }
    }

    // ---------------------------------------------------------------- keys

    /**
     * The key a line is compared by.
     *
     * With a pattern, {@code r} compares the match itself and its absence
     * compares what follows the match -- which is the whole point of
     * {@code :sort /.*:/} over a file of prefixed lines.
     */
    private static Entry entryFor(String text, Pattern regex, boolean useMatch,
                                  int radix, boolean ignoreCase)
    {
        String key = text;
        if (regex != null) {
            final Matcher matcher = regex.matcher(text);
            key = !matcher.find() ? ""
                  : useMatch ? matcher.group() : text.substring(matcher.end());
        }
        if (radix != 0) {
            // A line with no number of this base sorts before every line that
            // has one, keeping its place among the others. Not the same as
            // counting it zero, which would put it after a negative.
            final Long number = numberIn(key, radix);
            return new Entry(text, key, number, number != null);
        }
        // A line the pattern did not match is not a separate category: its
        // key is just the empty string, which sorts first anyway but sorts
        // *with* the lines whose match left nothing after it.
        return new Entry(text, ignoreCase ? key.toLowerCase() : key, null, true);
    }

    /**
     * The first number of this base in the text, or null if there is none.
     *
     * Vim scans for one rather than requiring the line to be one, so "d3" and
     * " s5" sort as 3 and 5. A minus sign directly in front counts, which is
     * what puts "z-9" first.
     */
    private static Long numberIn(String text, int radix)
    {
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), radix) < 0)
                continue;
            int start = i;
            // 0x before a hex number belongs to it, and is skipped rather
            // than read as the digit zero followed by a stray x.
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
                final long value = Long.parseLong(text.substring(start, end),
                                                  radix);
                return negative ? -value : value;
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- sorting

    /**
     * Orders the entries, keeping equal ones as they were.
     *
     * {@code !} reverses the result rather than the comparison, so the lines
     * without a key stay together at what is now the end.
     */
    private static void sort(List<Entry> entries, boolean numeric,
                             boolean reverse)
    {
        entries.sort((a, b) -> {
            if (!a.keyed || !b.keyed)
                return a.keyed == b.keyed ? 0 : (a.keyed ? 1 : -1);
            if (numeric)
                return Long.compare(a.number, b.number);
            return a.key.compareTo(b.key);
        });
        if (reverse)
            java.util.Collections.reverse(entries);
    }

    // -------------------------------------------------------------- writing

    /**
     * Puts the ordered lines back.
     *
     * The text of each line is replaced where it stands rather than the whole
     * range being cut and re-inserted, which keeps this clear of the
     * end-of-buffer newline rules. Only {@code u} makes the range shorter,
     * and the lines it leaves over are deleted at the end.
     */
    private static void write(Editor editor, VimState state, VimEx.Range range,
                              List<String> lines) throws VimEx.BadCommand
    {
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            for (int i = 0; i < lines.size(); i++) {
                final Line line = VimEx.lineAt(editor, range.first + i);
                if (line == null)
                    break;
                final String was = line.getText() == null ? "" : line.getText();
                if (was.equals(lines.get(i)))
                    continue;
                editor.setMark(new Position(line, line.length()));
                editor.setDot(line, 0);
                // setDot moves the caret in the model; the display keeps its own
                // column, and j pads an insert out to it. Without this, writing into
                // an empty line indents it to wherever the last edit left the caret.
                editor.moveCaretToDotCol();
                editor.deleteRegion();
                editor.setMark(null);
                editor.insertString(lines.get(i));
            }
            if (lines.size() < range.last - range.first + 1) {
                final VimEx.Range extra =
                    new VimEx.Range(range.first + lines.size(), range.last, true);
                VimOperators.deleteRange(editor,
                                         VimExCommands.linesOf(editor, extra));
            }
            final Line first = VimEx.lineAt(editor, range.first);
            if (first != null) {
                editor.setDot(first, VimMotions.firstNonBlank(first));
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        state.clampCaret(editor);
    }
}
