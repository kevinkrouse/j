/*
 * VimTextObjects.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.HashMap;
import java.util.Map;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;

/**
 * The text objects, by the names the key map table uses.
 *
 * Unlike a motion, a text object names a span outright -- {@code iw} is "the
 * word the caret is in", wherever in it the caret happens to be -- so it
 * returns a {@link VimRange} and skips {@link RangeNormalizer} entirely. The
 * inclusive/exclusive rules exist to work out how far a *movement* reached;
 * there is no movement here.
 *
 * <p>Each object comes in two forms: inner ({@code iw}) takes just the thing,
 * outer ({@code aw}) takes its surroundings too.
 *
 * <p>Known gap: the word object works within one line, so {@code aw} on a
 * blank line does nothing, where vim counts the empty line as a word and
 * takes the next one with it. Closing that means chunking over positions
 * rather than over the line's text.
 */
public final class VimTextObjects
{
    /** Names the span, or null when the caret is not in such a thing. */
    public interface TextObject
    {
        VimRange range(MotionContext ctx, Position from, boolean inner);
    }

    private static final Map<String, TextObject> OBJECTS =
        new HashMap<String, TextObject>();

    private VimTextObjects()
    {
    }

    public static TextObject get(String name)
    {
        return OBJECTS.get(name);
    }

    public static void register(String name, TextObject object)
    {
        OBJECTS.put(name, object);
    }

    static {
        register("word", VimTextObjects::word);
        register("quote", VimTextObjects::quote);
        register("bracket", VimTextObjects::bracket);
        register("paragraph", VimTextObjects::paragraph);
    }

    // --------------------------------------------------------------- word

    /**
     * iw and aw.
     *
     * A "word" here includes a run of blanks, so {@code diw} on a space
     * deletes the space. The outer form additionally takes the blanks after
     * the word, or the ones before it when there are none after.
     */
    private static VimRange word(MotionContext ctx, Position from, boolean inner)
    {
        final Mode mode = ctx.editor.getBuffer().getMode();
        final boolean bigWord = ctx.arg("bigWord");
        final Line line = from.getLine();
        final String text = line.getText();
        if (text == null || text.isEmpty())
            return new VimRange(new Position(line, 0), new Position(line, 0));

        final int at = Math.min(from.getOffset(), text.length() - 1);
        int start = startOfChunk(text, at, mode, bigWord);
        int end = at;

        // iw counts chunks, alternating word and blank run, so 2iw is a word
        // plus the space after it. aw counts *words*, each with the blanks
        // beside it -- two chunks a time, in whichever order the caret's own
        // chunk puts them.
        final int chunks = inner ? ctx.count : ctx.count * 2;
        for (int i = 0; i < chunks && end < text.length(); i++)
            end = endOfChunk(text, end, mode, bigWord);

        // A word that ends the line has no blanks after it to take, so "a
        // word" takes the ones before it instead. Only when the caret started
        // on a word: starting on blanks they are already in the span.
        if (!inner && !Character.isWhitespace(text.charAt(at))
            && (end == 0 || !Character.isWhitespace(text.charAt(end - 1))))
            start = skipBlanks(text, start - 1, -1) + 1;

        return new VimRange(new Position(line, start), new Position(line, end));
    }

    /** The offset just past the chunk containing {@code at}. */
    private static int endOfChunk(String text, int at, Mode mode,
                                  boolean bigWord)
    {
        if (at >= text.length())
            return text.length();
        final int cls = classOf(text.charAt(at), mode, bigWord);
        int i = at;
        while (i < text.length()
               && classOf(text.charAt(i), mode, bigWord) == cls)
            ++i;
        return i;
    }

    /** The offset the chunk containing {@code at} starts at. */
    private static int startOfChunk(String text, int at, Mode mode,
                                    boolean bigWord)
    {
        if (at >= text.length())
            return text.length();
        final int cls = classOf(text.charAt(at), mode, bigWord);
        int i = at;
        while (i > 0 && classOf(text.charAt(i - 1), mode, bigWord) == cls)
            --i;
        return i;
    }

    private static int skipBlanks(String text, int from, int step)
    {
        int i = from;
        while (i >= 0 && i < text.length()
               && Character.isWhitespace(text.charAt(i)))
            i += step;
        return i;
    }

    /**
     * Vim's three classes: blank, keyword, and everything else. A WORD has
     * only two, which is what makes {@code iW} take {@code foo.bar} whole.
     */
    private static int classOf(char c, Mode mode, boolean bigWord)
    {
        if (Character.isWhitespace(c))
            return 0;
        if (bigWord)
            return 1;
        return mode.isIdentifierPart(c) ? 1 : 2;
    }

    // -------------------------------------------------------------- quote

    /**
     * i" i' i` and their outer forms, confined to one line as in vim.
     *
     * The pair is found by counting quotes from the start of the line, so the
     * caret does not have to be inside one: {@code di"} with the caret before
     * the opening quote still takes the first pair on the line.
     */
    private static VimRange quote(MotionContext ctx, Position from,
                                  boolean inner)
    {
        final char q = ctx.arg("quote", "\"").charAt(0);
        final Line line = from.getLine();
        final String text = line.getText();
        if (text == null)
            return null;
        final int at = from.getOffset();

        // Quotes pair up from the start of the line, so which pair the caret
        // belongs to depends on how many came before it -- both quotes of a
        // pair belong to that pair, and a caret in the gap between two pairs
        // takes the quotes either side of it instead.
        int previousClose = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != q || isEscaped(text, i))
                continue;
            final int close = closingQuote(text, i, q);
            if (close < 0)
                break;  // Odd number of quotes; let the fallback decide.
            if (at <= close) {
                if (at < i && previousClose >= 0)
                    return pair(line, text, previousClose, i, inner);
                return pair(line, text, i, close, inner);
            }
            previousClose = close;
            i = close;
        }
        // Pairing from the start of the line does not always put the caret in
        // a pair -- an apostrophe in "it's" throws the count off. Fall back
        // to the quotes either side of the caret.
        return surroundingQuotes(line, text, at, q, inner);
    }

    private static VimRange surroundingQuotes(Line line, String text, int at,
                                              char q, boolean inner)
    {
        for (int open = Math.min(at, text.length() - 1); open >= 0; open--) {
            if (text.charAt(open) != q || isEscaped(text, open))
                continue;
            final int close = closingQuote(text, open, q);
            return close < 0 ? null : pair(line, text, open, close, inner);
        }
        return null;
    }

    /**
     * The range for a located quote pair. The outer form takes the blanks
     * after the closing quote, or the ones before the opening one when there
     * are none after.
     */
    private static VimRange pair(Line line, String text, int open, int close,
                                 boolean inner)
    {
        if (inner)
            return charwise(line, open + 1, close);
        final int after = skipBlanks(text, close + 1, 1);
        if (after != close + 1)
            return charwise(line, open, after);
        return charwise(line, skipBlanks(text, open - 1, -1) + 1, close + 1);
    }

    private static int closingQuote(String text, int open, char q)
    {
        for (int i = open + 1; i < text.length(); i++)
            if (text.charAt(i) == q && !isEscaped(text, i))
                return i;
        return -1;
    }

    private static boolean isEscaped(String text, int at)
    {
        int backslashes = 0;
        for (int i = at - 1; i >= 0 && text.charAt(i) == '\\'; i--)
            ++backslashes;
        return backslashes % 2 == 1;
    }

    // ------------------------------------------------------------ bracket

    /**
     * i( i[ i{ i&lt; and their outer forms, across lines.
     *
     * The enclosing pair if the caret is inside one, otherwise the next pair
     * that opens on this line -- which is how {@code di(} works with the
     * caret still on the function name.
     */
    private static VimRange bracket(MotionContext ctx, Position from,
                                    boolean inner)
    {
        final char open = ctx.arg("open", "(").charAt(0);
        final char close = ctx.arg("close", ")").charAt(0);
        final Buffer buffer = ctx.editor.getBuffer();

        Position start = enclosingOpen(buffer, from, open, close, ctx.count);
        if (start == null)
            start = openOnThisLine(from, open);
        if (start == null)
            return null;
        final Position end = matchForward(buffer, start, open, close);
        if (end == null)
            return null;

        if (!inner)
            return new VimRange(new Position(start), step(end));
        return innerBlock(start, end);
    }

    /**
     * The span between a bracket pair, with vim's two line rules.
     *
     * An opening bracket that ends its line hands the next line over whole,
     * and a closing bracket with only blanks before it gives back the line it
     * sits on. When both apply the block is whole lines, which is why
     * {@code ci(} on a multi-line call opens a fresh indented line rather
     * than leaving the caret squeezed between the brackets.
     */
    private static VimRange innerBlock(Position open, Position close)
    {
        final boolean openEndsLine =
            open.getOffset() == open.getLineLength() - 1;
        final boolean closeStartsLine =
            onlyBlanksBefore(close.getLine(), close.getOffset());
        final Line firstInner = open.getLine().next();

        if (openEndsLine && closeStartsLine && firstInner != null
            && firstInner != close.getLine())
            return VimRange.lines(firstInner, close.getLine().previous());

        final Position start = openEndsLine && firstInner != null
            ? new Position(firstInner, 0)
            : step(open);
        Position end = new Position(close);
        if (closeStartsLine) {
            final Line before = close.getLine().previous();
            if (before != null && before != open.getLine())
                end = new Position(before, before.length());
        }
        if (end.isBefore(start))
            end = new Position(start);
        return new VimRange(start, end);
    }

    private static boolean onlyBlanksBefore(Line line, int offset)
    {
        final String text = line.getText();
        if (text == null)
            return true;
        for (int i = 0; i < offset && i < text.length(); i++)
            if (!Character.isWhitespace(text.charAt(i)))
                return false;
        return true;
    }

    /** The count'th unmatched opening bracket before the caret. */
    private static Position enclosingOpen(Buffer buffer, Position from,
                                          char open, char close, int count)
    {
        final Position pos = new Position(from);
        // The caret sitting on the opening bracket counts as inside it.
        if (charAt(pos) == open)
            return countOut(buffer, pos, open, close, count - 1);
        int depth = 0;
        while (pos.prev()) {
            final char c = charAt(pos);
            if (c == close)
                ++depth;
            else if (c == open && depth-- == 0)
                return countOut(buffer, pos, open, close, count - 1);
        }
        return null;
    }

    /** Steps out {@code levels} further pairs, for 2i( and friends. */
    private static Position countOut(Buffer buffer, Position at, char open,
                                     char close, int levels)
    {
        Position pos = at;
        for (int i = 0; i < levels; i++) {
            // From just outside this bracket, or the search would find the
            // same one again and never move.
            final Position outside = new Position(pos);
            if (!outside.prev())
                return pos;
            final Position outer =
                enclosingOpen(buffer, outside, open, close, 1);
            if (outer == null)
                return pos;
            pos = outer;
        }
        return pos;
    }

    private static Position openOnThisLine(Position from, char open)
    {
        final String text = from.getLine().getText();
        if (text == null)
            return null;
        for (int i = from.getOffset(); i < text.length(); i++)
            if (text.charAt(i) == open)
                return new Position(from.getLine(), i);
        return null;
    }

    private static Position matchForward(Buffer buffer, Position openAt,
                                         char open, char close)
    {
        final Position pos = new Position(openAt);
        int depth = 0;
        while (true) {
            final char c = charAt(pos);
            if (c == open)
                ++depth;
            else if (c == close && --depth == 0)
                return pos;
            if (!pos.next())
                return null;
        }
    }

    private static char charAt(Position pos)
    {
        return pos.getOffset() < pos.getLineLength() ? pos.getChar() : '\n';
    }

    private static Position step(Position pos)
    {
        final Position next = new Position(pos);
        next.next();
        return next;
    }

    // ---------------------------------------------------------- paragraph

    /**
     * ip and ap: a run of non-blank lines, or a run of blank ones.
     *
     * The outer form adds the blank lines that follow, which is what makes
     * {@code dap} remove a paragraph and the gap after it in one go.
     */
    private static VimRange paragraph(MotionContext ctx, Position from,
                                      boolean inner)
    {
        final boolean startedBlank = blank(from.getLine());
        Line first = from.getLine();
        while (first.previous() != null
               && blank(first.previous()) == startedBlank)
            first = first.previous();

        // Same shape as iw/aw: ip counts runs, ap counts paragraphs with the
        // blank lines beside them.
        Line last = from.getLine();
        boolean blankRun = startedBlank;
        final int runs = inner ? ctx.count : ctx.count * 2;
        for (int i = 0; i < runs; i++) {
            while (last.next() != null && blank(last.next()) == blankRun)
                last = last.next();
            if (i + 1 < runs) {
                if (last.next() == null)
                    break;
                last = last.next();
                blankRun = !blankRun;
            }
        }

        // A paragraph at the end of the buffer has no blank lines after it to
        // take, so "a paragraph" takes the ones before it instead.
        if (!inner && !startedBlank && !blank(last))
            while (first.previous() != null && blank(first.previous()))
                first = first.previous();

        return VimRange.lines(first, last);
    }

    private static boolean blank(Line line)
    {
        return line.length() == 0;
    }

    // ------------------------------------------------------------ helpers

    private static VimRange charwise(Line line, int start, int end)
    {
        return new VimRange(new Position(line, Math.max(0, start)),
                            new Position(line, Math.max(0, end)));
    }
}
