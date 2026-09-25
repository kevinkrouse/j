/*
 * VimEx.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * One typed {@code :} line, taken apart.
 *
 * An ex line is a range, then a command name, then whatever that command makes
 * of the rest. Splitting it is a separate job from running it because the
 * split is where vim's grammar lives: {@code :%s/a/b/g} is a range, a name and
 * three fields, and {@code :1,3d x} is a range, a name and a register.
 *
 * <p>This class owns the whole string and hands j's {@code CommandTable} only
 * a bare name. {@link Editor#executeCommand(String, boolean)} reads a leading
 * {@code (} as a Lisp form and anything containing {@code =} with no space to
 * its left as a property assignment, either of which would quietly eat an ex
 * command -- {@code :s/a=b/c/} most of all.
 */
public final class VimEx
{
    /** Lines the command applies to, as 1-based numbers, both ends inclusive. */
    public static final class Range
    {
        public final int first;
        public final int last;
        /** False when the line carried no range and the command must default. */
        public final boolean given;

        Range(int first, int last, boolean given)
        {
            this.first = first;
            this.last = last;
            this.given = given;
        }
    }

    /** What a parsed line asks for. */
    public static final class Command
    {
        public final Range range;
        /** The command name as typed, possibly abbreviated; "" if none. */
        public final String name;
        /** True when the name was followed by {@code !}. */
        public final boolean bang;
        /** Everything after the name, untrimmed on the left of its first field. */
        public final String args;

        Command(Range range, String name, boolean bang, String args)
        {
            this.range = range;
            this.name = name;
            this.bang = bang;
            this.args = args;
        }
    }

    /** A line that cannot be parsed, carrying what to show the user. */
    public static final class BadCommand extends Exception
    {
        BadCommand(String message)
        {
            super(message);
        }
    }

    private final String text;
    private final Editor editor;
    private final VimState state;
    private int pos;

    private VimEx(Editor editor, VimState state, String text)
    {
        this.editor = editor;
        this.state = state;
        this.text = text;
    }

    /**
     * Takes a typed line apart.
     *
     * The leading {@code :} may be present or not; a line typed at the prompt
     * has already lost it, one replayed from a recording has not.
     */
    public static Command parse(Editor editor, VimState state, String line)
        throws BadCommand
    {
        String s = line == null ? "" : line;
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ':' || s.charAt(i) == ' '))
            ++i;
        final VimEx ex = new VimEx(editor, state, s.substring(i));
        final Range range = ex.range();
        final String name = ex.name();
        return new Command(range, name, ex.bang(name), ex.rest());
    }

    // ------------------------------------------------------------- range

    /**
     * The line range, if the command carries one.
     *
     * {@code %} is the whole buffer, a bare address is one line, and two
     * separated by a comma are the span. Vim allows either end to be left out
     * -- {@code :,5} and {@code :5,} both mean "from or to the current line".
     */
    private Range range() throws BadCommand
    {
        skipSpace();
        if (peek() == '%') {
            ++pos;
            return new Range(1, lineCount(), true);
        }
        final Integer first = address();
        skipSpace();
        if (peek() != ',' && peek() != ';') {
            if (first == null)
                return new Range(currentLine(), currentLine(), false);
            return new Range(first, first, true);
        }
        // ';' sets the caret to the first address before reading the second.
        // Nothing here needs that difference yet, so both are read as ','.
        ++pos;
        final Integer second = address();
        final int from = first == null ? currentLine() : first;
        final int to = second == null ? currentLine() : second;
        return from <= to ? new Range(from, to, true)
                          : new Range(to, from, true);
    }

    /**
     * One address, or null if there is none here.
     *
     * A number, {@code .}, {@code $}, a mark, or a search -- each then taking
     * any number of {@code +n} and {@code -n} offsets.
     */
    private Integer address() throws BadCommand
    {
        skipSpace();
        Integer base = null;
        final char c = peek();
        if (Character.isDigit(c)) {
            base = number();
        } else if (c == '.') {
            ++pos;
            base = currentLine();
        } else if (c == '$') {
            ++pos;
            base = lineCount();
        } else if (c == '\'') {
            ++pos;
            base = markLine(next());
        } else if (c == '/' || c == '?') {
            base = searchLine(c);
        }
        // An offset with no address in front counts from the current line, so
        // ":+3" and ":-2" work; with nothing at all this returns null.
        Integer offset = null;
        while (true) {
            skipSpace();
            final char sign = peek();
            if (sign != '+' && sign != '-')
                break;
            ++pos;
            skipSpace();
            final int step = Character.isDigit(peek()) ? number() : 1;
            offset = (offset == null ? 0 : offset) + (sign == '+' ? step : -step);
        }
        if (base == null && offset == null)
            return null;
        // Not clamped. Vim refuses an address past the end of the buffer
        // rather than quietly using the last line, so :50 on three lines is
        // an error; the commands check when they resolve it to a Line.
        return (base == null ? currentLine() : base)
               + (offset == null ? 0 : offset);
    }

    private int number()
    {
        int n = 0;
        while (Character.isDigit(peek())) {
            n = n * 10 + (text.charAt(pos) - '0');
            ++pos;
        }
        return n;
    }

    /** The line a mark sits on, for {@code :'a,'bd} and {@code :'<,'>}. */
    private int markLine(char name) throws BadCommand
    {
        final Position mark = state.getMarks().get(name, editor.getBuffer());
        if (mark == null)
            throw new BadCommand("E20: Mark not set");
        return mark.lineNumber() + 1;
    }

    /** The line a {@code /pat/} or {@code ?pat?} address finds. */
    private int searchLine(char delimiter) throws BadCommand
    {
        ++pos;
        final StringBuilder pattern = new StringBuilder();
        while (pos < text.length() && text.charAt(pos) != delimiter) {
            if (text.charAt(pos) == '\\' && pos + 1 < text.length())
                pattern.append(text.charAt(pos++));
            pattern.append(text.charAt(pos++));
        }
        if (pos < text.length())
            ++pos;
        final VimSearch.Query query =
            new VimSearch.Query(pattern.toString(), delimiter == '/', false);
        final Position found = VimSearch.find(editor, query, editor.getDot(), 1);
        if (found == null)
            throw new BadCommand("E486: Pattern not found: " + pattern);
        return found.lineNumber() + 1;
    }

    // ----------------------------------------------------------- command

    /**
     * The command name.
     *
     * Letters, or one of the handful of names that are punctuation:
     * {@code :&amp;}, {@code :&lt;}, {@code :&gt;}, {@code :!} and {@code :=}.
     */
    private String name()
    {
        skipSpace();
        final int start = pos;
        final char c = peek();
        if (c == '&' || c == '<' || c == '>' || c == '=' || c == '!' || c == '~') {
            // > and < repeat to mean more than one shift, as in :>>.
            while (peek() == c)
                ++pos;
            return text.substring(start, pos);
        }
        while (Character.isLetter(peek()))
            ++pos;
        return text.substring(start, pos);
    }

    /**
     * The {@code !} some commands take.
     *
     * Not every {@code !} after a name is one. {@code :s} takes any
     * punctuation as its separator, {@code !} included, so {@code :s!a!b!} is
     * a perfectly ordinary substitute and eating that first {@code !} would
     * leave {@code a!b!} to be read with {@code a} as the separator.
     */
    private boolean bang(String name)
    {
        if (peek() != '!' || takesSeparator(name))
            return false;
        ++pos;
        return true;
    }

    /** True for the commands whose argument opens with a delimiter. */
    private static boolean takesSeparator(String name)
    {
        return name.equals("s") || "substitute".startsWith(name)
               && name.startsWith("s");
    }

    private String rest()
    {
        final String s = text.substring(Math.min(pos, text.length()));
        // A separating space belongs to the syntax, not to the argument; a
        // second one might be the argument, as in ":s/ / /".
        return s.startsWith(" ") ? s.substring(1) : s;
    }

    // ----------------------------------------------------------- helpers

    private char peek()
    {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next()
    {
        return pos < text.length() ? text.charAt(pos++) : '\0';
    }

    private void skipSpace()
    {
        while (pos < text.length() && text.charAt(pos) == ' ')
            ++pos;
    }

    private int currentLine()
    {
        final Position dot = editor.getDot();
        return dot == null ? 1 : dot.lineNumber() + 1;
    }

    private int lineCount()
    {
        final Buffer buffer = editor.getBuffer();
        return buffer == null ? 1 : Math.max(1, buffer.getLineCount());
    }

    /** The line a 1-based number names, or null if the buffer is shorter. */
    static Line lineAt(Editor editor, int number)
    {
        Line line = editor.getBuffer().getFirstLine();
        for (int i = 1; i < number && line != null; i++)
            line = line.next();
        return line;
    }
}
