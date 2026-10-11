/*
 * Momentum.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Timer;

/**
 * Watches a touchpad gesture, and when it ends at speed, has the scroller
 * coast on and slow to a stop. Java says nothing of fingers lifting,
 * so a gesture ends when no event has come for a few times the gap between
 * its events.
 */
final class Momentum implements ActionListener {
    /** One event: when, and how many pixels. */
    record Sample(long nanos, int dy) {}

    // How far back the speed is measured from the last event.
    private static final long WINDOW_NANOS = 40_000_000L;
    // A burst of clicks all at once counts as spread over a frame at least.
    private static final long MIN_SPAN_NANOS = 16_000_000L;
    // The quiet that ends a gesture, as a multiple of the gap between its
    // events, and its bounds in milliseconds.
    private static final int GAPS_TO_END = 2;
    private static final int MIN_END_MILLIS = 20;
    private static final int MAX_END_MILLIS = 120;
    // In pixels a millisecond: slower than this is a drag, not a flick.
    static final double MIN_VELOCITY = 0.5;
    static final double MAX_VELOCITY = 20.0;

    private final SmoothScroller scroller;
    private final Timer gestureEnd = new Timer(MAX_END_MILLIS, this);
    private final List<Sample> samples = new ArrayList<>();
    // Between the last two events, or 0.
    private long interval;

    Momentum(SmoothScroller scroller) {
        this.scroller = scroller;
        gestureEnd.setRepeats(false);
    }

    /** An event of the gesture: dy pixels, now. */
    void add(int dy) {
        final long now = System.nanoTime();
        // Turning back starts a new gesture.
        if (!samples.isEmpty() && Integer.signum(dy) != Integer.signum(samples.getLast().dy()))
            samples.clear();
        final long interval = samples.isEmpty() ? -1 : now - samples.getLast().nanos();
        this.interval = Math.max(0, interval);
        samples.add(new Sample(now, dy));
        while (now - samples.getFirst().nanos() > WINDOW_NANOS)
            samples.removeFirst();
        int delay = MAX_END_MILLIS;
        if (interval >= 0)
            delay = Math.clamp(GAPS_TO_END * interval / 1_000_000L, MIN_END_MILLIS, MAX_END_MILLIS);
        gestureEnd.setInitialDelay(delay);
        gestureEnd.restart();
    }

    /** Forgets the gesture, so that it does not coast. */
    void cancel() {
        gestureEnd.stop();
        samples.clear();
    }

    // No event came when the next was due: the coast takes over from then,
    // as if it had, so that the window does not stand still waiting.
    @Override
    public void actionPerformed(ActionEvent e) {
        if (!samples.isEmpty())
            end(samples.getLast().nanos() + interval);
    }

    private void end(long coastFrom) {
        final double velocity = velocity(samples);
        samples.clear();
        if (Math.abs(velocity) >= MIN_VELOCITY)
            scroller.coast(velocity, coastFrom);
    }

    /**
     * Pixels a millisecond over the samples, the newest last: what moved
     * after the first of them, over the time since it.
     */
    static double velocity(List<Sample> samples) {
        if (samples.size() < 2)
            return 0;
        long sum = 0;
        for (int i = 1; i < samples.size(); i++)
            sum += samples.get(i).dy();
        final long span = samples.getLast().nanos() - samples.getFirst().nanos();
        final double velocity = sum / (Math.max(span, MIN_SPAN_NANOS) / 1e6);
        return Math.clamp(velocity, -MAX_VELOCITY, MAX_VELOCITY);
    }
}
