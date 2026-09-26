/*
 * AdjacentWindowTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * {@code Frame.adjacent}: which window CTRL-W h j k l goes to, by where the
 * windows are and where the caret is, as nvim chooses.
 */
public class AdjacentWindowTest
{
    // Two windows side by side over one the full width, 4 pixel dividers:
    //
    //   +--------+--------+
    //   |   a    |   b    |
    //   +--------+--------+
    //   |        c        |
    //   +-----------------+
    private static final Rectangle A = new Rectangle(0, 0, 98, 98);
    private static final Rectangle B = new Rectangle(102, 0, 98, 98);
    private static final Rectangle C = new Rectangle(0, 102, 200, 98);

    private static int adjacent(Rectangle from, Point caret, char direction,
                                Rectangle... others)
    {
        final List<Rectangle> list = Arrays.asList(others);
        return Frame.adjacent(from, caret, list, direction);
    }

    @Test
    public void upGoesToTheWindowAboveTheCaret()
    {
        // From c: over the caret's column, left or right.
        assertEquals(0, adjacent(C, new Point(20, 150), 'k', A, B));
        assertEquals(1, adjacent(C, new Point(150, 150), 'k', A, B));
    }

    @Test
    public void downFromEitherGoesToTheOneBelow()
    {
        assertEquals(0, adjacent(A, new Point(10, 10), 'j', C, B));
        assertEquals(0, adjacent(B, new Point(110, 10), 'j', C, A));
    }

    @Test
    public void sidewaysStaysOnTheRow()
    {
        assertEquals(0, adjacent(A, new Point(10, 10), 'l', B, C));
        assertEquals(0, adjacent(B, new Point(110, 10), 'h', A, C));
        // Nothing beside c.
        assertEquals(-1, adjacent(C, new Point(10, 150), 'l', A, B));
        assertEquals(-1, adjacent(C, new Point(10, 150), 'h', A, B));
    }

    @Test
    public void nothingBeyondTheEdge()
    {
        assertEquals(-1, adjacent(A, new Point(10, 10), 'k', B, C));
        assertEquals(-1, adjacent(C, new Point(10, 150), 'j', A, B));
    }

    @Test
    public void theNearestOfSeveralInARow()
    {
        // Three side by side: from the right one, h is the middle one.
        final Rectangle left = new Rectangle(0, 0, 60, 100);
        final Rectangle middle = new Rectangle(64, 0, 60, 100);
        final Rectangle right = new Rectangle(128, 0, 60, 100);
        assertEquals(1, adjacent(right, new Point(130, 10), 'h', left,
                                 middle));
    }

    @Test
    public void onlyWindowsAlongsideCount()
    {
        // One up and to the left is nearer than the one level with it, but
        // is not beside it.
        final Rectangle here = new Rectangle(100, 100, 100, 100);
        final Rectangle upLeft = new Rectangle(50, 0, 40, 50);
        final Rectangle left = new Rectangle(0, 100, 60, 100);
        assertEquals(1, adjacent(here, new Point(110, 110), 'h', upLeft,
                                 left));
    }

    @Test
    public void aCaretPastEveryWindowTakesTheClosest()
    {
        // The caret's column is beyond b: b is still the one nearer.
        assertEquals(1, adjacent(C, new Point(199, 150), 'k', A,
                                 new Rectangle(102, 0, 60, 98)));
    }
}
