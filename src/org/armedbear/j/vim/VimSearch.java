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
import java.util.regex.Pattern;
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
 * <p>Patterns are vim's, magic levels and all; {@link VimRegex} rewrites them
 * for {@code java.util.regex}, which is what j's {@code Search} runs.
 */
public final class VimSearch {
    private VimSearch() {}

    /** A pattern and the direction it was entered in, for n and N. */
    public static final class Query {
        public final String pattern;
        public final boolean forward;
        /** True for * and #, which match whole words only. */
        public final boolean wholeWord;
        /**
         * False for * # g* g#, which use 'ignorecase' but not 'smartcase':
         * the word came from the buffer, so its capitals say nothing about
         * what the user meant.
         */
        public final boolean smartcase;
        /**
         * One of j's own searches this stands for, run as it is -- so that
         * n goes on with a find made outside vim edit mode -- or null.
         */
        final Search own;

        public Query(String pattern, boolean forward, boolean wholeWord) {
            this(pattern, forward, wholeWord, true);
        }

        public Query(
            String pattern,
            boolean forward,
            boolean wholeWord,
            boolean smartcase
        ) {
            this(pattern, forward, wholeWord, smartcase, null);
        }

        private Query(
            String pattern,
            boolean forward,
            boolean wholeWord,
            boolean smartcase,
            Search own
        ) {
            this.pattern = pattern;
            this.forward = forward;
            this.wholeWord = wholeWord;
            this.smartcase = smartcase;
            this.own = own;
        }

        Query reversed() {
            return withDirection(!forward);
        }

        /** The same search going the given way; a j search is copied to keep it. */
        Query withDirection(boolean forward) {
            Search search = own;
            if (search != null && search.isForward() != forward) {
                search = (Search) search.clone();
                search.setForward(forward);
            }
            return new Query(pattern, forward, wholeWord, smartcase, search);
        }
    }

    /**
     * A query compiled to j's {@link Search}, which is what j keeps as its
     * last search: findNext goes on with a {@code /}, and n reads the query
     * back.
     */
    static final class Compiled extends Search {
        final Query query;

        Compiled(Query query) {
            this.query = query;
        }
    }

    /**
     * What n repeats, from j's last search: the query a vim search was, or
     * one standing for a find of j's own, in its direction, spelled for vim so that
     * {@code :s//} can use it -- {@code \V} for a literal, {@code \v} for a
     * regular expression, which is near enough to Java's.
     */
    static Query queryOf(Search search) {
        if (search == null)
            return null;
        if (search instanceof Compiled compiled)
            return compiled.query;
        final String pattern = search.getPattern();
        final String spelled = (search.ignoreCase() ? "\\c" : "")
            + (search.isRegularExpression()
                ? "\\v" + pattern
                : "\\V" + pattern.replace("\\", "\\\\"));
        return new Query(
            spelled,
            search.isForward(),
            search.wholeWordsOnly(),
            false,
            search
        );
    }

    /** Raised for a pattern java.util.regex will not take. */
    public static final class BadPattern extends RuntimeException {
        BadPattern(String message) {
            super(message);
        }
    }

    /**
     * Where the count'th match is, or null if the pattern is nowhere.
     *
     * @param from  where to search from; never itself a result
     */
    public static Position find(
        Editor editor,
        Query query,
        Position from,
        int count
    ) {
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
    private static Position step(
        Editor editor,
        Search search,
        boolean forward,
        Position from
    ) {
        final Buffer buffer = editor.getBuffer();
        Position found = forward
            ? nextAfter(search, buffer, from)
            : lastBefore(editor, search, from);
        if (found != null)
            return found;

        // Wrap. j wraps only for a literal forward search, so do it here for
        // every direction and every pattern.
        if (!VimKeyMap.getSharedOptions().isOn("wrapscan"))
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
    private static Position nextAfter(
        Search search,
        Buffer buffer,
        Position from
    ) {
        final Line line = from.getLine();
        Position scan = new Position(line, 0);
        while (true) {
            final Position match = search.find(buffer, scan);
            if (match == null)
                return null;
            if (match.getLine() != line)
                return match; // already scanned from its own line's start
            if (match.getOffset() > from.getOffset())
                return match;
            scan = past(match, matchLength(search));
            if (scan == null)
                return null; // end of the buffer; only the wrap is left
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
    private static Position lastBefore(
        Editor editor,
        Search search,
        Position limit
    ) {
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
    private static Position lastOnLine(
        Editor editor,
        Search search,
        Line line,
        int before
    ) {
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

    /**
     * A query compiled to keep as j's last search. A bad one is kept too, as
     * vim keeps it: it matches nothing, and n reports it again.
     */
    static Search compileToKeep(Query query, Editor editor) {
        final Search search = compileQuietly(query, editor);
        if (search != null)
            return search;
        final Compiled bad = new Compiled(query);
        bad.setPattern(query.pattern);
        bad.setRegularExpression(true);
        bad.setRE(Pattern.compile("(?!)"));
        return bad;
    }

    /** A query compiled, or null when the pattern is bad. */
    static Search compileQuietly(Query query, Editor editor) {
        try {
            return compile(query, editor);
        }
        catch (BadPattern e) {
            return null;
        }
    }

    /**
     * What a compiled query depends on: the pattern, and the options and the
     * last replacement ({@code ~}) its translation reads.
     */
    static String compiledKey(Query query) {
        final VimOptions options = VimKeyMap.getSharedOptions();
        return query.pattern + '\0' + query.wholeWord + query.smartcase
            + options.isOn("ignorecase")
            + options.isOn("smartcase") + '\0'
            + VimExSubstitute.lastReplacement();
    }

    private static Line lastLine(Buffer buffer) {
        Line line = buffer.getFirstLine();
        while (line != null && line.next() != null)
            line = line.next();
        return line;
    }

    /** Just past a match, stepping to the next line when it ends one. */
    private static Position past(Position match, int length) {
        final Line line = match.getLine();
        final int next = match.getOffset() + length;
        if (next <= line.length())
            return new Position(line, next);
        return line.next() == null ? null : new Position(line.next(), 0);
    }

    /** How long the match just found was, at least one character. */
    private static int matchLength(Search search) {
        final Matcher matcher = search.getMatch();
        final int length = matcher != null
            ? matcher.group().length()
            : search.getPatternLength();
        return Math.max(1, length);
    }

    private static Search compile(Query query, Editor editor) {
        if (query.own != null)
            return query.own;
        final Search search = new Compiled(query);
        // The translation is inside the try too: VimRegex refuses what it
        // cannot express by throwing, and a refusal has to reach the user as
        // a message rather than escape into the key handler.
        try {
            final VimRegex.Result translated = VimRegex.translate(
                query.pattern,
                VimExSubstitute.lastReplacement()
            );
            search.setPattern(translated.java());
            search.setIgnoreCase(
                VimRegex.ignoreCase(
                    translated,
                    null,
                    query.smartcase
                )
            );
            search.setWholeWordsOnly(query.wholeWord);
            search.setRegularExpression(true);
            search.setREFromPattern();
        }
        catch (PatternSyntaxException e) {
            throw new BadPattern(e.getDescription());
        }
        return search;
    }

    /**
     * Vim's {@code 'ignorecase'}, narrowed by {@code 'smartcase'} and
     * overridden by {@code \c} or {@code \C} in the pattern itself.
     */
    static boolean ignoreCase(String pattern) {
        return VimRegex.ignoreCase(
            VimRegex.translate(pattern, VimExSubstitute.lastReplacement()),
            null
        );
    }

    /** A vim pattern in Java's syntax; see {@link VimRegex}. */
    static String toJavaRegex(String pattern) {
        return VimRegex.translate(pattern, VimExSubstitute.lastReplacement())
            .java();
    }
}
