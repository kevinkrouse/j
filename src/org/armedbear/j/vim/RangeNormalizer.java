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
                // Take the character the motion landed on as well.
                to = new Position(to);
                if (to.getOffset() < to.getLineLength())
                    to.setOffset(to.getOffset() + 1);
                else if (to.getLine().next() != null)
                    to = new Position(to.getLine().next(), 0);
                return new VimRange(from, to, false);
            default:
                return new VimRange(from, to, false);
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

    private static VimRange linewise(Position from, Position to)
    {
        final Position start = new Position(from.getLine(), 0);
        final Line after = to.getLine().next();
        final Position end = after != null
            ? new Position(after, 0)
            : new Position(to.getLine(), to.getLine().length());
        return new VimRange(start, end, true);
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
     * Nothing is pulled back when no word actually ended at the line break --
     * {@code dw} on an empty line really does delete the line break.
     */
    static void clipWordMotionAtLineEnd(Position from, Position to)
    {
        if (to.getLine() == from.getLine() || to.getOffset() != 0)
            return;
        final Line previous = to.getLine().previous();
        if (previous == null || previous.length() == 0)
            return;
        if (Character.isWhitespace(previous.charAt(previous.length() - 1)))
            return;
        to.setLine(previous);
        to.setOffset(previous.length());
    }
}
