/*
 * VimMotions.java
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
import org.armedbear.j.CaretCommands;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Words;
import org.armedbear.j.Position;

/**
 * The motions, by the names the key map table uses.
 *
 * A motion works out where the caret should end up and returns that position,
 * or null if there is nowhere to go -- at which point vim beeps and, if an
 * operator was pending, abandons it.
 *
 * <p>Motions do not move the caret themselves. That matters for operators: in
 * {@code dw} the motion says how far {@code w} would go, and the delete uses
 * the span without the caret ever visiting the far end.
 */
public final class VimMotions
{
    /** Computes where a motion ends. */
    public interface Motion
    {
        Position move(MotionContext ctx, Position from);

        /**
         * How far an operator following this motion reaches.
         *
         * Usually the table row says, but ';' and ',' cannot: their kind
         * follows the f/t search they repeat, which is only known at run time.
         */
        default MotionKind kindOf(MotionContext ctx)
        {
            return MotionKind.of(ctx.command);
        }
    }

    private static final Map<String, Motion> MOTIONS =
        new HashMap<String, Motion>();

    private VimMotions()
    {
    }

    public static Motion get(String name)
    {
        return MOTIONS.get(name);
    }

    public static void register(String name, Motion motion)
    {
        MOTIONS.put(name, motion);
    }

    static {
        register("moveByCharacters", VimMotions::moveByCharacters);
        register("moveByLines", VimMotions::moveByLines);
        register("moveToStartOfLine", (ctx, from) -> at(from.getLine(), 0));
        register("moveToFirstNonBlank", VimMotions::moveToFirstNonBlank);
        register("moveToEol", VimMotions::moveToEol);
        register("moveToLine", VimMotions::moveToLine);
        register("moveToColumn", VimMotions::moveToColumn);
        register("moveByWords", VimMotions::moveByWords);
        register("moveToCharacter", VimMotions::moveToCharacter);
        register("repeatCharacterSearch", new RepeatCharacterSearch());
        register("moveByParagraph", VimMotions::moveByParagraph);
        register("goToMark", VimMotions::goToMark);
        register("jumpToMark", VimMotions::jumpToMark);
        register("moveToScreenLine", VimMotions::moveToScreenLine);
        register("moveToMatchingBracket", VimMotions::moveToMatchingBracket);
        register("repeatSearch", VimMotions::repeatSearch);
        register("searchWordAtDot", VimMotions::searchWordAtDot);
    }

    // ------------------------------------------------------------ motions

    /**
     * h and l.
     *
     * Vim's h and l stop at the ends of the line rather than wrapping, and a
     * count that would overshoot moves as far as it can instead of failing.
     */
    private static Position moveByCharacters(MotionContext ctx, Position from)
    {
        final boolean forward = ctx.arg("forward");
        // dl on the last character of a line deletes it, so the exclusive end
        // has to be able to sit one past where the caret could.
        final int last = forward && ctx.forOperator
            ? from.getLineLength()
            : lastOffset(ctx, from.getLine());
        int offset = from.getOffset();
        for (int i = 0; i < ctx.count; i++)
            offset = forward ? CodePoints.next(from.getLine(), offset)
                             : CodePoints.previous(from.getLine(), offset);
        if (offset > last)
            offset = last;
        return offset == from.getOffset() ? null : at(from.getLine(), offset);
    }

    /**
     * j and k.
     *
     * The column the caret wants is remembered across a run of them, so that
     * passing through a short line does not lose the column on the way back.
     */
    private static Position moveByLines(MotionContext ctx, Position from)
    {
        final boolean forward = ctx.arg("forward");
        Line line = from.getLine();
        for (int i = 0; i < ctx.count; i++) {
            final Line next = forward ? line.nextVisible() : line.previousVisible();
            if (next == null) {
                // Vim refuses to move at all rather than moving part way.
                if (i == 0)
                    return null;
                break;
            }
            line = next;
        }
        final Buffer buffer = ctx.editor.getBuffer();
        final int wanted = ctx.state.getDesiredColumn(ctx.editor, from);
        final Position to = new Position(line, 0);
        to.moveToCol(wanted, buffer.getTabWidth());
        clampToLine(ctx, to);
        return to;
    }

    /** ^ */
    private static Position moveToFirstNonBlank(MotionContext ctx, Position from)
    {
        return at(from.getLine(), firstNonBlank(from.getLine()));
    }

    /**
     * $
     *
     * With a count, {@code 3$} goes to the end of the third line down.
     */
    private static Position moveToEol(MotionContext ctx, Position from)
    {
        Line line = from.getLine();
        for (int i = 1; i < ctx.count; i++) {
            final Line next = line.nextVisible();
            if (next == null)
                break;
            line = next;
        }
        return at(line, lastOffset(ctx, line));
    }

    /**
     * gg and G.
     *
     * Both take a line number as their count; without one, gg goes to the
     * first line and G to the last. The caret lands on the first non-blank.
     */
    private static Position moveToLine(MotionContext ctx, Position from)
    {
        final Buffer buffer = ctx.editor.getBuffer();
        final Line line;
        if (ctx.countGiven)
            line = lineNumbered(buffer, ctx.count);
        else if (ctx.arg("forward"))
            line = lastLine(buffer);
        else
            line = buffer.getFirstLine();
        if (line == null)
            return null;
        return at(line, firstNonBlank(line));
    }

    /** | -- to a screen column, counting from one. */
    private static Position moveToColumn(MotionContext ctx, Position from)
    {
        final Position to = new Position(from.getLine(), 0);
        to.moveToCol(ctx.count - 1, ctx.editor.getBuffer().getTabWidth());
        clampToLine(ctx, to);
        return to;
    }

    /** w W b B e E ge gE -- one implementation, four arguments. */
    private static Position moveByWords(MotionContext ctx, Position from)
    {
        final Mode mode = ctx.editor.getBuffer().getMode();
        final boolean forward = ctx.arg("forward");
        final boolean wordEnd = ctx.arg("wordEnd");
        final boolean bigWord = ctx.arg("bigWord");

        Position pos = from;
        for (int i = 0; i < ctx.count; i++) {
            final Position next;
            if (forward)
                next = wordEnd ? Words.forwardToWordEnd(pos, mode, bigWord)
                               : Words.forwardToWordStart(pos, mode, bigWord);
            else
                next = wordEnd ? Words.backwardToWordEnd(pos, mode, bigWord)
                               : Words.backwardToWordStart(pos, mode, bigWord);
            if (next == null) {
                // Out of words. Going forward that means the end of the
                // buffer, not failure: `de` on the last word still deletes
                // it, and `d9w` takes everything that is left. Going
                // backward from the very start there is nowhere to go, so
                // the motion really does fail.
                if (forward) {
                    ctx.clampedToBufferEnd = true;
                    return ctx.editor.getBuffer().getEnd();
                }
                return i == 0 ? null : pos;
            }
            pos = next;
        }
        return pos;
    }

    /**
     * f F t T -- to, or up to, a character on this line.
     *
     * Confined to the line the caret is on: vim does not search past the end.
     */
    private static Position moveToCharacter(MotionContext ctx, Position from)
    {
        final char target = ctx.characterArg();
        if (target == 0)
            return null;
        final boolean forward = ctx.arg("forward");
        final boolean till = ctx.arg("till");
        ctx.state.setLastCharacterSearch(target, forward, till);
        return findCharacter(from, target, forward, till, ctx.count, false);
    }

    /**
     * ; and , -- the last f/F/t/T again, or reversed.
     *
     * Its own class rather than a lambda because the kind is not in the table:
     * f and t are inclusive, F and T are exclusive, and ',' flips which of
     * those applies. So `d;` after `f4` takes the 4 and `d;` after `F4` does
     * not.
     */
    private static final class RepeatCharacterSearch implements Motion
    {
        @Override
        public Position move(MotionContext ctx, Position from)
        {
            final VimState.CharacterSearch last =
                ctx.state.getLastCharacterSearch();
            if (last == null)
                return null;
            return findCharacter(from, last.target, forward(ctx, last),
                                 last.till, ctx.count, true);
        }

        @Override
        public MotionKind kindOf(MotionContext ctx)
        {
            final VimState.CharacterSearch last =
                ctx.state.getLastCharacterSearch();
            if (last == null)
                return MotionKind.of(ctx.command);
            return forward(ctx, last) ? MotionKind.CHARWISE_INCLUSIVE
                                      : MotionKind.CHARWISE_EXCLUSIVE;
        }

        private static boolean forward(MotionContext ctx,
                                       VimState.CharacterSearch last)
        {
            return ctx.arg("reverse") ? !last.forward : last.forward;
        }
    }

    /**
     * @param repeat true for ';' and ',', which need one extra step for a
     *        till search: the caret is already parked against the character
     *        it stopped before, so searching from there would never move.
     */
    /** f, F, t and T, which are j's own {@link CaretCommands} scan. */
    private static Position findCharacter(Position from, char target,
                                          boolean forward, boolean till,
                                          int count, boolean repeat)
    {
        return CaretCommands.findCharacter(from,
            new CaretCommands.CharSearch(target, forward, till), count, repeat);
    }

    private static int indexOf(String text, char target, int from,
                               boolean forward)
    {
        for (int i = from; i >= 0 && i < text.length(); i += forward ? 1 : -1)
            if (text.charAt(i) == target)
                return i;
        return -1;
    }

    /**
     * { and } -- to the blank line that ends the paragraph.
     *
     * A paragraph boundary is an empty line, so these land on one; running out
     * of buffer lands on the far end of it instead.
     */
    private static Position moveByParagraph(MotionContext ctx, Position from)
    {
        final boolean forward = ctx.arg("forward");
        Position pos = from;
        for (int i = 0; i < ctx.count; i++) {
            final Position next = paragraphBoundary(pos, forward);
            if (next == null)
                return i == 0 ? null : pos;
            pos = next;
        }
        return pos;
    }

    private static Position paragraphBoundary(Position from, boolean forward)
    {
        Line line = forward ? from.getLine().nextVisible()
                            : from.getLine().previousVisible();
        if (line == null)
            return null;
        while (line.length() != 0) {
            final Line next = forward ? line.nextVisible()
                                      : line.previousVisible();
            if (next == null)
                // No boundary left: stop at the edge of the buffer.
                return at(line, forward ? line.length() : 0);
            line = next;
        }
        return at(line, 0);
    }

    /**
     * `a and 'a -- to a mark.
     *
     * The backtick form goes to the exact spot; the quote form goes to the
     * first non-blank of its line and is linewise, which is why {@code d'a}
     * takes whole lines and {@code d`a} does not.
     */
    private static Position goToMark(MotionContext ctx, Position from)
    {
        final char name = ctx.characterArg();
        if (name == 0)
            return null;
        final Position mark =
            ctx.state.getMarks().get(name, ctx.editor.getBuffer());
        if (mark == null)
            return null;
        return ctx.arg("linewise")
            ? at(mark.getLine(), firstNonBlank(mark.getLine()))
            : at(mark.getLine(), mark.getOffset());
    }

    /**
     * ]` [` ]' [' -- to the nearest mark either side of the caret.
     *
     * The quote forms go by lines: a mark on the caret's own line does not
     * count, and with no mark to go to they still land on the first
     * non-blank, as nvim does.
     */
    private static Position jumpToMark(MotionContext ctx, Position from)
    {
        final VimMarks marks = ctx.state.getMarks();
        final boolean forward = ctx.arg("forward");
        final boolean linewise = ctx.arg("linewise");
        Position pos = from;
        for (int i = 0; i < ctx.count; i++) {
            // Searching from the far end of the line skips its own marks.
            final Position after = !linewise ? pos
                : new Position(pos.getLine(),
                               forward ? pos.getLine().length() : 0);
            final Position next = forward
                ? marks.next(ctx.editor.getBuffer(), after)
                : marks.previous(ctx.editor.getBuffer(), after);
            if (next == null) {
                if (i == 0 && !linewise)
                    return null;
                break;
            }
            pos = next;
        }
        return linewise
            ? at(pos.getLine(), firstNonBlank(pos.getLine()))
            : at(pos.getLine(), pos.getOffset());
    }

    /**
     * H, M and L -- to a line of the window rather than of the buffer.
     *
     * H and L take a count of lines in from the edge; M ignores it.
     */
    private static Position moveToScreenLine(MotionContext ctx, Position from)
    {
        // H, M and L, which are j's own moveToWindowTop and friends.
        final Line line = CaretCommands.screenLine(ctx.editor,
                                                   ctx.arg("where", "top"),
                                                   ctx.count);
        return line == null ? null : at(line, firstNonBlank(line));
    }

    /**
     * % -- to the bracket matching the first one at or after the caret.
     *
     * Only looks on the caret's line for the bracket to match from, as vim
     * does; the match itself may be anywhere.
     */
    private static Position moveToMatchingBracket(MotionContext ctx, Position from)
    {
        final String open = "([{";
        final String close = ")]}";
        final String text = from.getLine().getText();
        if (text == null)
            return null;

        int offset = -1;
        for (int i = from.getOffset(); i < text.length(); i++) {
            final char c = text.charAt(i);
            if (open.indexOf(c) >= 0 || close.indexOf(c) >= 0) {
                offset = i;
                break;
            }
        }
        if (offset < 0)
            return null;

        // The actual search is Editor's own, which already knows to ignore a
        // bracket inside a comment or string literal, or one that is
        // backslash-escaped -- rules this motion has no business
        // reimplementing.
        final Position match =
            ctx.editor.findMatchInternal(new Position(from.getLine(), offset), 0);
        return match == null ? null : at(match.getLine(), match.getOffset());
    }

    // ------------------------------------------------------------- search

    /**
     * n and N -- the last pattern again, in the same direction or the other
     * one. A motion like any other, so {@code dn} works.
     */
    private static Position repeatSearch(MotionContext ctx, Position from)
    {
        final VimSearch.Query last = ctx.state.getLastSearch();
        if (last == null) {
            ctx.editor.status("No previous search");
            return null;
        }
        return found(ctx, ctx.arg("reverse") ? last.reversed() : last, from);
    }

    /**
     * * and # -- the word under the caret, whole words unless g* or g#.
     *
     * Sets the last pattern, so n carries on from where these left off.
     */
    private static Position searchWordAtDot(MotionContext ctx, Position from)
    {
        final VimSearch.Word word = VimSearch.wordAtDot(ctx.editor);
        if (word == null)
            return null;
        final VimSearch.Query query =
            new VimSearch.Query(VimSearch.literal(word.text),
                                ctx.arg("forward"),
                                word.keyword && !ctx.arg("partial"), false);
        ctx.state.setLastSearch(query);
        // From the word, not from the caret: * with the caret on the spaces
        // before a word searches from the word, so the word itself is not a
        // result.
        return found(ctx, query, new Position(from.getLine(), word.offset));
    }

    private static Position found(MotionContext ctx, VimSearch.Query query,
                                  Position from)
    {
        try {
            final Position to =
                VimSearch.find(ctx.editor, query, from, ctx.count);
            if (to == null)
                ctx.editor.status("Pattern not found: " + query.pattern);
            return to;
        }
        catch (VimSearch.BadPattern e) {
            ctx.editor.status("Bad pattern: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ helpers

    private static Position at(Line line, int offset)
    {
        return new Position(line, offset);
    }

    /**
     * The furthest offset the caret may occupy on a line.
     *
     * One less in a command mode than in insert, because in normal mode the
     * caret is on a character rather than between two.
     */
    private static int lastOffset(MotionContext ctx, Line line)
    {
        final int length = line.length();
        return ctx.state.getMode().isCommandMode()
            ? CodePoints.snap(line, Math.max(0, length - 1))
            : length;
    }

    private static void clampToLine(MotionContext ctx, Position pos)
    {
        final int last = lastOffset(ctx, pos.getLine());
        if (pos.getOffset() > last)
            pos.setOffset(last);
    }

    /** j's own, which moveToWindowTop and its kin use too. */
    static int firstNonBlank(Line line)
    {
        return CaretCommands.firstNonBlank(line);
    }

    private static Line lineNumbered(Buffer buffer, int number)
    {
        Line line = buffer.getFirstLine();
        for (int i = 1; i < number && line != null; i++) {
            final Line next = line.nextVisible();
            if (next == null)
                return line;
            line = next;
        }
        return line;
    }

    private static Line lastLine(Buffer buffer)
    {
        Line line = buffer.getFirstLine();
        if (line == null)
            return null;
        while (line.nextVisible() != null)
            line = line.nextVisible();
        return line;
    }
}
