/*
 * Session.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.extension;

import org.armedbear.j.File;

/**
 * One connection to a language runtime.
 *
 * <p>A {@link LanguageClient} can hold several, keyed by whatever the user
 * finds useful &mdash; Conjure keys them by working directory, and J could key
 * them by buffer, by project, or by the REPL the user is pointing at. Keeping
 * evaluation on a session rather than on the client is what makes "evaluate
 * this form in <em>that</em> REPL" expressible at all.
 */
public interface Session extends AutoCloseable
{
    /** The key this session was obtained with. */
    String getKey();

    /**
     * Whether the runtime behind this session is up. Starting it is deferred
     * until something actually needs it, so this is false for a while after
     * the extension has registered.
     */
    boolean isReady();

    /**
     * Evaluate, reporting to the handler when the runtime answers. Starts the
     * runtime if it is not up yet. The handler may be called on any thread.
     */
    void eval(EvalRequest request, EvalHandler handler);

    /**
     * Evaluate and wait. Convenient for the handful of places in core that are
     * already synchronous; prefer {@link #eval} for anything that might take a
     * while.
     */
    EvalResult evalSync(EvalRequest request) throws EvalException;

    /** Load a file into the runtime. */
    void loadFile(File file) throws EvalException;

    /** Whether the runtime reports a named feature, e.g. Lisp's *features*. */
    default boolean hasFeature(String name)
    {
        return false;
    }

    /** Interrupt whatever the runtime is doing, where it supports that. */
    default void interrupt()
    {
    }

    @Override
    default void close()
    {
    }
}
