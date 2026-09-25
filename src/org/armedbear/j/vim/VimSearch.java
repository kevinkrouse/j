/*
 * VimSearch.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;
import org.armedbear.j.Search;

/**
 * Finding a pattern, for {@code / ? n N * #}.
 *
 * The matching itself is j's {@link Search}; what is added here is what vim
 * expects around it -- never matching where the caret already is, and wrapping
 * round the end of the buffer, which j only does for a literal forward search.
 *
 * <p>Patterns are {@code java.util.regex} with a shim for {@code \&lt;} and
 * {@code \&gt;}. Vim's own dialect is not emulated: in vim's default magic
 * mode {@code \+} is "one or more" and {@code +} is a literal, and here it is
 * the other way round. That is a documented divergence, not an oversight --
 * see {@code doc/editmodes.html}.
 */
public final class VimSearch
{
    private VimSearch()
    {
    }

    /** A pattern and the direction it was entered in, for n and N. */
    public static final class Query
    {
        public final String pattern;
        public final boolean forward;
        /** True for * and #, which match whole words only. */
        public final boolean wholeWord;

        public Query(String pattern, boolean forward, boolean wholeWord)
        {
            this.pattern = pattern;
            this.forward = forward;
            this.wholeWord = wholeWord;
        }

        Query reversed()
        {
            return new Query(pattern, !forward, wholeWord);
        }
    }

    /** Raised for a pattern java.util.regex will not take. */
    public static final class BadPattern extends RuntimeException
    {
        BadPattern(String message)
        {
            super(message);
        }
    }

    /**
     * Where the count'th match is, or null if the pattern is nowhere.
     *
     * @param from  where to search from; never itself a result
     */
    public static Position find(Editor editor, Query query, Position from,
                                int count)
    {
        final Search search = compile(query, editor);
        Position pos = from;
        for (int i = 0; i < count; i++) {
            pos = step(editor, search, query.forward, pos);
            if (pos == null)
                return null;
        }
        return pos;
    }

    /** One match onwards, wrapping at the end of the buffer. */
    private static Position step(Editor editor, Search search, boolean forward,
                                 Position from)
    {
        final Buffer buffer = editor.getBuffer();
        Position found = forward ? nextAfter(search, buffer, from)
                                 : lastBefore(editor, search, from);
        if (found != null)
            return found;

        // Wrap. j wraps only for a literal forward search, so do it here for
        // every direction and every pattern.
        if (!VimKeyMap.getSharedOptions().getBoolean("wrapscan", true))
            return null;
        // A single match that we started on is found again by the wrap; that
        // is vim's behaviour too, so it is not filtered out.
        return forward
            ? search.find(buffer, new Position(buffer.getFirstLine(), 0))
            : lastBefore(editor, search, null);
    }

    /**
     * The next match after a position.
     *
     * Where the matches on a line are is decided by scanning that line from
     * its start, not from the caret -- the two differ as soon as a match can
     * overlap itself. Searching {@code a\+} in {@code aaa aa} from column 0
     * finds the match at column 4, because the matches are at 0 and 4; a scan
     * beginning at column 1 would invent one there.
     *
     * <p>Only the caret's own line needs this: {@link Search} already scans
     * every later line from its start.
     */
    private static Position nextAfter(Search search, Buffer buffer,
                                      Position from)
    {
        final Line line = from.getLine();
        Position scan = new Position(line, 0);
        while (true) {
            final Position match = search.find(buffer, scan);
            if (match == null)
                return null;
            if (match.getLine() != line)
                return match;   // already scanned from its own line's start
            if (match.getOffset() > from.getOffset())
                return match;
            scan = past(match, matchLength(search));
            if (scan == null)
                return null;   // end of the buffer; only the wrap is left
            if (scan.getLine() != line)
                return search.find(buffer, scan);
        }
    }

    /**
     * The last match starting before a position, or the last in the buffer
     * when {@code limit} is null.
     *
     * Each line is scanned forwards, because the matches have to be the same
     * set the forward direction sees -- otherwise {@code ?} and {@code n}
     * disagree about where the matches are.
     */
    private static Position lastBefore(Editor editor, Search search,
                                       Position limit)
    {
        final Buffer buffer = editor.getBuffer();
        Line line = limit != null ? limit.getLine() : lastLine(buffer);
        int before = limit != null ? limit.getOffset() : Integer.MAX_VALUE;
        for (; line != null; line = line.previous()) {
            final Position best = lastOnLine(editor, search, line, before);
            if (best != null)
                return best;
            before = Integer.MAX_VALUE;
        }
        return null;
    }

    /**
     * The last match on one line that starts before {@code before}.
     *
     * Line at a time so that walking backwards stops at the first line that
     * has a match, rather than sweeping the whole buffer on every keystroke.
     */
    private static Position lastOnLine(Editor editor, Search search, Line line,
                                       int before)
    {
        final Mode mode = editor.getBuffer().getMode();
        Position best = null;
        int offset = 0;
        while (offset <= line.length()) {
            final Position match =
                search.findInLine(mode, new Position(line, offset));
            if (match == null || match.getOffset() >= before)
                return best;
            best = match;
            offset = match.getOffset() + matchLength(search);
        }
        return best;
    }

    private static Line lastLine(Buffer buffer)
    {
        Line line = buffer.getFirstLine();
        while (line != null && line.next() != null)
            line = line.next();
        return line;
    }

    /** Just past a match, stepping to the next line when it ends one. */
    private static Position past(Position match, int length)
    {
        final Line line = match.getLine();
        final int next = match.getOffset() + length;
        if (next <= line.length())
            return new Position(line, next);
        return line.next() == null ? null : new Position(line.next(), 0);
    }

    /** How long the match just found was, at least one character. */
    private static int matchLength(Search search)
    {
        final Matcher matcher = search.getMatch();
        final int length = matcher != null ? matcher.group().length()
                                           : search.getPatternLength();
        return Math.max(1, length);
    }

    private static Search compile(Query query, Editor editor)
    {
        final Search search = new Search();
        search.setPattern(toJavaRegex(query.pattern));
        search.setIgnoreCase(ignoreCase(query.pattern));
        search.setWholeWordsOnly(query.wholeWord);
        search.setRegularExpression(true);
        try {
            search.setREFromPattern();
        }
        catch (PatternSyntaxException e) {
            throw new BadPattern(e.getDescription());
        }
        return search;
    }

    /**
     * Vim's {@code 'ignorecase'}, narrowed by {@code 'smartcase'}: a pattern
     * with an upper case letter in it is taken to mean that case.
     */
    private static boolean ignoreCase(String pattern)
    {
        final VimOptions options = VimKeyMap.getSharedOptions();
        if (!options.getBoolean("ignorecase", false))
            return false;
        if (!options.getBoolean("smartcase", false))
            return true;
        for (int i = 0; i < pattern.length(); i++)
            if (Character.isUpperCase(pattern.charAt(i)))
                return false;
        return true;
    }

    /**
     * The shim: vim's word boundaries, which java.util.regex spells
     * differently. Everything else is passed through untouched.
     */
    static String toJavaRegex(String pattern)
    {
        final StringBuilder sb = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                final char next = pattern.charAt(i + 1);
                if (next == '<' || next == '>') {
                    sb.append("\\b");
                    ++i;
                    continue;
                }
                sb.append(c).append(next);
                ++i;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** What * and # decided to search for, and where it starts. */
    static final class Word
    {
        final String text;
        final int offset;
        /** False for a run of symbols, which gets no word boundaries. */
        final boolean keyword;

        Word(String text, int offset, boolean keyword)
        {
            this.text = text;
            this.offset = offset;
            this.keyword = keyword;
        }
    }

    /**
     * The word * and # search for: the one under the caret, or the next one
     * along the line when the caret is on a space or punctuation.
     *
     * A line with no keyword on it at all is not a refusal -- vim takes the
     * run of symbols instead, so {@code *} on {@code /}} finds the next
     * {@code /}}.
     */
    static Word wordAtDot(Editor editor)
    {
        final Position dot = editor.getDot();
        if (dot == null)
            return null;
        final Mode mode = editor.getBuffer().getMode();
        final Position pos = new Position(dot);
        final String text = pos.getLine().getText();
        if (text == null)
            return null;

        for (int offset = pos.getOffset(); offset < text.length(); offset++) {
            pos.setOffset(offset);
            final String word = mode.getIdentifier(pos);
            if (word != null && !word.isEmpty())
                // getIdentifier scans back to the start of the word, so with
                // the caret inside one the word begins before this offset.
                return new Word(word, Math.max(0, text.lastIndexOf(word, offset)),
                                true);
        }
        // No keyword: the first run of non-blanks that is not one either.
        for (int offset = dot.getOffset(); offset < text.length(); offset++) {
            if (Character.isWhitespace(text.charAt(offset)))
                continue;
            int end = offset;
            while (end < text.length()
                   && !Character.isWhitespace(text.charAt(end)))
                ++end;
            return new Word(text.substring(offset, end), offset, false);
        }
        return null;
    }

    /** Quotes a literal, so a word with regex characters in it still works. */
    static String literal(String text)
    {
        return "\\Q" + text + "\\E";
    }
}
