/*
 * Background.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Log;

/** Named daemon threads that log what they throw. */
public final class Background {
    private Background() {}

    public static Thread start(String name, Runnable task) {
        Thread thread = newThread(name, task);
        thread.start();
        return thread;
    }

    /**
     * Runs work on a thread named name, then gives what it returns to then
     * on the event thread: null if it threw, an error too, which is logged,
     * so that then always runs.
     */
    public static <T> void then(String name, Supplier<T> work, Consumer<T> then) {
        start(name, () -> {
            T result;
            try {
                result = work.get();
            }
            catch (RuntimeException | Error e) {
                Log.error(e);
                result = null;
            }
            final T read = result;
            SwingUtilities.invokeLater(() -> then.accept(read));
        });
    }

    /** Not started, for callers that set a priority or start it later. */
    public static Thread newThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> Log.error(e));
        return thread;
    }
}
