/*
 * RangeNormalizer.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.CaretCommands;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Turns "where the motion started and ended" into "what the operator deletes".
 *
 * Almost all of vim's apparent inconsistency between {@code dw}, {@code de},
 * {@code d}} and {@code dj} lives in this one function. The rules are from
 * {@code :help exclusive} and {@code :help cw}, and each is here because
 * leaving it out produces an emulation that is wrong in a way users feel but
 * cannot name.
 */
final class RangeNormalizer
{
    private RangeNormalizer()
    {
    }

    /**
     * @param start where the caret was when the operator was typed
     * @param end   where the motion landed
     */
    static VimRange normalize(Position start, Position end, MotionKind kind,
                              boolean motionWasForward)
    {
        Position from = new Position(start);
        Position to = new Position(end);
        if (to.isBefore(from)) {
            final Position swap = from;
            from = to;
            to = swap;
        }

        if (kind == MotionKind.CHARWISE_EXCLUSIVE)
            kind = applyExclusiveRules(from, to, kind);

        switch (kind) {
            case LINEWISE:
                return linewise(from, to);
            case CHARWISE_INCLUSIVE:
                // Take the character the motion landed on as well. An empty
                // line has none, and its newline does not count: d$ there
                // does nothing.
                to = new Position(to);
                if (to.getOffset() < to.getLineLength())
                    to.setOffset(CodePoints.next(to.getLine(), to.getOffset()));
                return new VimRange(from, to);
            default:
                return new VimRange(from, to);
        }
    }

    /**
     * The two adjustments in {@code :help exclusive}.
     *
     * A forward motion that lands in the first column has overshot onto a line
     * the user did not mean to touch, so the range is pulled back to the end of
     * the previous line. If the motion also started at or before the first
     * non-blank, nothing of interest is left on either end line and the whole
     * thing becomes linewise -- which is why {@code d}} on a paragraph takes
     * the lines rather than leaving two ragged halves.
     */
    private static MotionKind applyExclusiveRules(Position from, Position to,
                                                  MotionKind kind)
    {
        if (to.getOffset() != 0 || to.getLine() == from.getLine())
            return kind;
        final Line previous = to.getLine().previous();
        if (previous == null)
            return kind;

        to.setLine(previous);
        to.setOffset(previous.length());

        // Staying exclusive is what "becomes inclusive" means in a half open
        // range: an end of `length` already takes the last character in.
        return from.getOffset() <= VimMotions.firstNonBlank(from.getLine())
            ? MotionKind.LINEWISE
            : MotionKind.CHARWISE_EXCLUSIVE;
    }

    /**
     * The exception in {@code :help d}: a characterwise delete across lines,
     * with only blanks before its start and after its end, takes the whole
     * lines -- so it does not leave a line of blanks behind.
     */
    static VimRange deleteRange(VimRange range)
    {
        if (range.linewise || range.start.getLine() == range.end.getLine())
            return range;
        final Line first = range.start.getLine();
        final Line last = range.end.getLine();
        if (!isBlank(first.substring(0, range.start.getOffset()))
            || !isBlank(last.substring(range.end.getOffset())))
            return range;
        return VimRange.lines(first, last);
    }

    /** Only spaces and tabs, vim's blanks: a form feed is not one. */
    private static boolean isBlank(String s)
    {
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) != ' ' && s.charAt(i) != '\t')
                return false;
        return true;
    }

    private static VimRange linewise(Position from, Position to)
    {
        return VimRange.lines(from.getLine(), to.getLine());
    }

    /**
     * The w-with-an-operator rule from {@code :help word}:
     *
     * <blockquote>When using the "w" motion in combination with an operator
     * and the last word moved over is at the end of a line, the end of that
     * word becomes the end of the operated text, not the first word in the
     * next line.</blockquote>
     *
     * This is why {@code dw} on the last word of a line deletes the word and
     * leaves the line, rather than pulling the next one up. It is a rule about
     * w specifically, not about exclusive motions, so it runs before those and
     * usually stops them applying at all.
     *
     * The end goes back to the end of the line the operator started on, not
     * to one line before where the motion landed: {@code dw} on the last word
     * of a line leaves the line, and leaves any blank lines below it alone
     * even though the motion crossed them looking for a word.
     *
     * Only applies when the motion landed at the start of a line, or at the
     * first non-blank of an indented one. Having got somewhere mid-line --
     * which is what a count large enough to pass a word does -- the motion
     * meant it.
     */
    static void clipWordMotionAtLineEnd(Position from, Position to)
    {
        if (to.getLine() == from.getLine()
            || to.getOffset() != CaretCommands.firstNonBlank(to.getLine()))
            return;
        to.setLine(from.getLine());
        to.setOffset(from.getLine().length());
    }
}
