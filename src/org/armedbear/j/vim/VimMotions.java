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
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
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
        register("repeatCharacterSearch", VimMotions::repeatCharacterSearch);
        register("moveByParagraph", VimMotions::moveByParagraph);
        register("goToMark", VimMotions::goToMark);
        register("jumpToMark", VimMotions::jumpToMark);
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
        int offset = from.getOffset() + (forward ? ctx.count : -ctx.count);
        if (offset < 0)
            offset = 0;
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
                next = wordEnd ? VimWords.forwardToWordEnd(pos, mode, bigWord)
                               : VimWords.forwardToWordStart(pos, mode, bigWord);
            else
                next = wordEnd ? VimWords.backwardToWordEnd(pos, mode, bigWord)
                               : VimWords.backwardToWordStart(pos, mode, bigWord);
            if (next == null)
                return i == 0 ? null : pos;
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
        if (ctx.character == null || ctx.character.length() != 1)
            return null;
        final char target = ctx.character.charAt(0);
        final boolean forward = ctx.arg("forward");
        final boolean till = ctx.arg("till");
        ctx.state.setLastCharacterSearch(target, forward, till);
        return findCharacter(from, target, forward, till, ctx.count, false);
    }

    /** ; and , -- the last f/F/t/T again, or reversed. */
    private static Position repeatCharacterSearch(MotionContext ctx, Position from)
    {
        final VimState.CharacterSearch last = ctx.state.getLastCharacterSearch();
        if (last == null)
            return null;
        final boolean forward = ctx.arg("reverse") ? !last.forward : last.forward;
        return findCharacter(from, last.target, forward, last.till, ctx.count,
                             true);
    }

    /**
     * @param repeat true for ';' and ',', which need one extra step for a
     *        till search: the caret is already parked against the character
     *        it stopped before, so searching from there would never move.
     */
    private static Position findCharacter(Position from, char target,
                                          boolean forward, boolean till,
                                          int count, boolean repeat)
    {
        final Line line = from.getLine();
        final String text = line.getText();
        if (text == null)
            return null;

        int found = from.getOffset();
        if (till && repeat)
            found += forward ? 1 : -1;

        for (int i = 0; i < count; i++) {
            found = indexOf(text, target, found + (forward ? 1 : -1), forward);
            if (found < 0)
                return null;
        }
        final int landing = till ? (forward ? found - 1 : found + 1) : found;
        if (landing < 0 || landing >= text.length())
            return null;
        return at(line, landing);
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
        if (ctx.character == null || ctx.character.length() != 1)
            return null;
        final Position mark = ctx.state.getMarks()
            .get(ctx.character.charAt(0), ctx.editor.getBuffer());
        if (mark == null)
            return null;
        return ctx.arg("linewise")
            ? at(mark.getLine(), firstNonBlank(mark.getLine()))
            : at(mark.getLine(), mark.getOffset());
    }

    /** ]` and [` -- to the nearest mark either side of the caret. */
    private static Position jumpToMark(MotionContext ctx, Position from)
    {
        final VimMarks marks = ctx.state.getMarks();
        final boolean forward = ctx.arg("forward");
        Position pos = from;
        for (int i = 0; i < ctx.count; i++) {
            final Position next = forward
                ? marks.next(ctx.editor.getBuffer(), pos)
                : marks.previous(ctx.editor.getBuffer(), pos);
            if (next == null)
                return i == 0 ? null : pos;
            pos = next;
        }
        return ctx.arg("linewise")
            ? at(pos.getLine(), firstNonBlank(pos.getLine()))
            : at(pos.getLine(), pos.getOffset());
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
        return ctx.state.getMode().isCommandMode() ? Math.max(0, length - 1)
                                                   : length;
    }

    private static void clampToLine(MotionContext ctx, Position pos)
    {
        final int last = lastOffset(ctx, pos.getLine());
        if (pos.getOffset() > last)
            pos.setOffset(last);
    }

    static int firstNonBlank(Line line)
    {
        final String text = line.getText();
        if (text == null)
            return 0;
        for (int i = 0; i < text.length(); i++)
            if (!Character.isWhitespace(text.charAt(i)))
                return i;
        return Math.max(0, text.length() - 1);
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
