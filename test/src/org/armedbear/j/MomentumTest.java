/*
 * MomentumTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How fast a gesture was going when it ended. */
public class MomentumTest {
    private static final long MS = 1_000_000L;

    // Events of dy pixels, every millis milliseconds.
    private static List<Momentum.Sample> every(long millis, int... dys) {
        final List<Momentum.Sample> samples = new ArrayList<>();
        for (int i = 0; i < dys.length; i++)
            samples.add(new Momentum.Sample(i * millis * MS, dys[i]));
        return samples;
    }

    @Test
    public void aTouchpadFlick() {
        // 100 pixels every 10 milliseconds.
        assertEquals(10.0, Momentum.velocity(every(10, 100, 100, 100, 100)), 1e-9);
    }

    @Test
    public void upIsNegative() {
        assertEquals(-5.0, Momentum.velocity(every(10, -50, -50, -50)), 1e-9);
    }

    @Test
    public void oneEventIsNoSpeedAtAll() {
        assertEquals(0.0, Momentum.velocity(every(10, 100)));
        assertEquals(0.0, Momentum.velocity(new ArrayList<>()));
    }

    @Test
    public void aBurstOfClicksAtOnceCountsAsAFrame() {
        // Four clicks after the first, all in the same instant: over 16ms.
        assertEquals(4 * 45 / 16.0, Momentum.velocity(every(0, 45, 45, 45, 45, 45)), 1e-9);
    }

    @Test
    public void theSpeedHasALimit() {
        assertEquals(Momentum.MAX_VELOCITY, Momentum.velocity(every(1, 500, 500, 500)));
    }
}
