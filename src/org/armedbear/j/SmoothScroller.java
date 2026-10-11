/*
 * SmoothScroller.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.Timer;

/**
 * Eases a display's scrolling, fast at first and slowing to a stop: over
 * smoothScrollingDuration milliseconds for a command's scroll, and at most
 * wheelScrollDuration for a click of the wheel. Position follows the clock, not the
 * frame count, so a slow frame is caught up rather than drawn out. A scroll
 * that comes while one is under way starts again from where the window is,
 * going to the new end. Wheel clicks that come quickly ease for less time,
 * as Firefox's do, so that a fast wheel or a touchpad, which XWayland sends
 * as a stream of clicks, is followed closely rather than chased.
 */
final class SmoothScroller implements ActionListener {
    // A little under one frame at 120 Hz.
    private static final int FRAME_MILLIS = 8;
    // Begin this far into the curve so the first frame moves at once.
    private static final long HEAD_START_NANOS = 10_000_000L;
    // XWayland sends a fast wheel as batches of clicks a fraction of a
    // millisecond apart. A click this soon after the last is in its batch.
    private static final long BATCH_NANOS = 3_000_000L;

    private final Display display;
    private final Timer timer = new Timer(FRAME_MILLIS, this);

    private Buffer buffer; // The buffer being scrolled.
    private int distance; // Pixels from the start to the end.
    private int moved; // Pixels done so far.
    private long startNanos;
    private long durationNanos;
    // Wheel scrolling takes the caret along, as scrollCaret says.
    private boolean followCaret;
    // The wheel turned while a command's scroll was being shown: the caret
    // the command placed stays put until the end, then comes into view.
    private boolean settleCaret;
    // Going on after a flick, slowing to a stop, rather than easing.
    private boolean coasting;
    private long lastWheelNanos;
    private long batchNanos; // When the latest batch of clicks began.
    private long batchMillis; // How long that batch eases.
    private boolean wheelTurned;

    // debugScrollTiming: what each scroll cost, logged when it ends.
    private boolean timing;
    private long timingStartNanos;
    private long lastTickNanos;
    private int frames;
    private long maxGapNanos;
    private long workNanos;
    private long maxWorkNanos;
    // Where the work went: moving what is on screen, drawing the lines that
    // uncovers, the scroll bar, flushing to the screen, and of the moving,
    // copying the pixels.
    private final long[] partNanos = new long[5];

    SmoothScroller(Display display) {
        this.display = display;
    }

    /** Wheel scrolling: dy more, from wherever the window is now. */
    void scrollBy(int dy) {
        final long now = System.nanoTime();
        // nanoTime may be negative, so the first click is known by a flag.
        final boolean sameBatch = wheelTurned && now - lastWheelNanos < BATCH_NANOS;
        final long interval = wheelTurned ? now - batchNanos : Long.MAX_VALUE;
        wheelTurned = true;
        lastWheelNanos = now;
        if (!sameBatch) {
            batchNanos = now;
            batchMillis = wheelMillis(interval, Math.max(0, display.wheelScrollDuration()));
        }
        final long millis = batchMillis;
        final int behind = distance - moved;
        if (timer.isRunning() && !followCaret)
            settleCaret = true;
        else
            followCaret = true;
        start(dy, millis);
        if (timing)
            Log.info(
                String.format(
                    "scroll wheel %+dpx, %s, easing %dms, %dpx behind",
                    dy,
                    sameBatch
                            ? "same batch"
                            : interval == Long.MAX_VALUE
                                    ? "the first click"
                                    : String.format("%.1fms after the last batch", interval / 1e6),
                    millis,
                    behind));
    }

    // How long a batch of wheel clicks eases, interval nanoseconds after the
    // last batch began: twice that, so each batch is spread over the frames
    // until the next and the window stops soon after the wheel does.
    static long wheelMillis(long interval, long max) {
        return Math.min(max, interval / 500_000L);
    }

    /** Eases dy pixels from where the window is; the caret stays put. */
    void animate(int dy) {
        followCaret = false;
        settleCaret = false;
        start(dy, Math.max(0, display.smoothScrollingDuration()));
        if (timing)
            Log.info(String.format("scroll command %+dpx", dy));
    }

    private void start(int dy, long millis) {
        buffer = display.getEditor().getBuffer();
        coasting = false;
        distance = distance - moved + dy;
        moved = 0;
        durationNanos = millis * 1_000_000L + HEAD_START_NANOS;
        startNanos = System.nanoTime() - HEAD_START_NANOS;
        startTimer();
    }

    private void startTimer() {
        if (timer.isRunning())
            return;
        timing = Editor.preferences().getBooleanProperty(Property.DEBUG_SCROLL_TIMING);
        timingStartNanos = System.nanoTime();
        lastTickNanos = timingStartNanos;
        frames = 0;
        maxGapNanos = 0;
        workNanos = 0;
        maxWorkNanos = 0;
        java.util.Arrays.fill(partNanos, 0);
        timer.start();
    }

    /**
     * After a flick: goes on from velocity pixels a millisecond, slowing to a
     * stop in momentumScrollingDuration. It is timed from startNanos, which
     * may be a moment ago: when the gesture would have gone on had it not
     * ended.
     */
    void coast(double velocity, long startNanos) {
        final long millis = Math.max(1, display.momentumScrollingDuration());
        buffer = display.getEditor().getBuffer();
        followCaret = true;
        settleCaret = false;
        coasting = true;
        // The ease out starts at three times the average speed.
        distance = (int) Math.round(velocity * millis / 3.0);
        moved = 0;
        durationNanos = millis * 1_000_000L;
        this.startNanos = Math.min(startNanos, System.nanoTime());
        startTimer();
        if (timing)
            Log.info(String.format("scroll coast at %.2fpx/ms, %+dpx", velocity, distance));
    }

    /** New input from the wheel or touchpad: a coast stops where it is. */
    void stopCoasting() {
        if (coasting)
            stop();
    }

    /** Jumps to where the scroll was going, or stops a coast where it is. */
    void finish() {
        if (!timer.isRunning())
            return;
        if (display.getEditor().getBuffer() != buffer) {
            stop();
            return;
        }
        if (!coasting)
            display.scrollByPixels(distance - moved, clampBottom());
        end();
    }

    /**
     * Stops where the window is. Returns true when that cuts short a
     * command's scroll, rather than the wheel's.
     */
    boolean stop() {
        final boolean commandScroll = timer.isRunning() && !followCaret;
        if (timer.isRunning() && timing && frames > 0) {
            final long now = System.nanoTime();
            Log.info(
                String.format(
                    "scroll done in %.0fms%s: %d frames, gap %.1fms average %.1fms most, work %.2fms average %.2fms most"
                            + " (move %.2fms, of which copy %.2fms, lines %.2fms, scroll bar %.2fms, sync %.2fms)",
                    (now - timingStartNanos) / 1e6,
                    followCaret ? String.format(", %.0fms after the last click", (now - lastWheelNanos) / 1e6) : "",
                    frames,
                    (lastTickNanos - timingStartNanos) / 1e6 / frames,
                    maxGapNanos / 1e6,
                    workNanos / 1e6 / frames,
                    maxWorkNanos / 1e6,
                    partNanos[0] / 1e6 / frames,
                    partNanos[4] / 1e6 / frames,
                    partNanos[1] / 1e6 / frames,
                    partNanos[2] / 1e6 / frames,
                    partNanos[3] / 1e6 / frames));
        }
        timer.stop();
        coasting = false;
        distance = 0;
        moved = 0;
        buffer = null;
        return commandScroll;
    }

    // The wheel stops at the end of the buffer. A command's scroll goes
    // where the command put the window, which may be a little past it.
    private boolean clampBottom() {
        return followCaret || settleCaret;
    }

    // Stops, and does what was put off while the window moved.
    private void end() {
        final boolean caret = followCaret || settleCaret;
        stop();
        final Editor editor = display.getEditor();
        if (caret)
            editor.maybeScrollCaret();
        editor.updateDisplay();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final Editor editor = display.getEditor();
        // Another buffer is showing now.
        if (editor.getBuffer() != buffer) {
            stop();
            return;
        }
        final long tickNanos = System.nanoTime();
        final double t = Math.min(1.0, (tickNanos - startNanos) / (double) durationNanos);
        final int step = (int) Math.round(distance * easeOut(t)) - moved;
        boolean done = t >= 1.0;
        if (step != 0) {
            final int actual = display.scrollByPixels(step, clampBottom());
            long now = time(0, tickNanos);
            partNanos[4] += display.takeBlitNanos();
            moved += actual;
            // At the top or the bottom.
            if (actual != step)
                done = true;
            if (followCaret)
                editor.maybeScrollCaret();
            if (!done) {
                // Just the window; the status bar and sidebar wait for the end.
                display.repaintChangedLines();
                now = time(1, now);
                editor.updateScrollBars();
                now = time(2, now);
            }
            // Without this X11 may hold frames back and show several at once.
            Toolkit.getDefaultToolkit().sync();
            time(3, now);
        }
        if (timing) {
            final long work = System.nanoTime() - tickNanos;
            ++frames;
            maxGapNanos = Math.max(maxGapNanos, tickNanos - lastTickNanos);
            lastTickNanos = tickNanos;
            workNanos += work;
            maxWorkNanos = Math.max(maxWorkNanos, work);
        }
        if (done)
            end();
    }

    // Adds the time from since until now to a part; returns now.
    private long time(int part, long since) {
        if (!timing)
            return 0;
        final long now = System.nanoTime();
        partNanos[part] += now - since;
        return now;
    }

    // Cubic ease out.
    static double easeOut(double t) {
        final double u = 1.0 - t;
        return 1.0 - u * u * u;
    }
}
