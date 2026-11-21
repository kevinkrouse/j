/*
 * FenceHandler.java
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

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;

/**
 * Renders a fenced Markdown block whose info string it claims, e.g.
 * ```mermaid. The Markdown mode finds the fences (it already parses them)
 * and hands the claimed ones here, so the handler never reads the
 * formatter's internals.
 *
 * <p>{@link #render} is called on the event thread, with the buffer
 * unlocked -- as with the inline-image path, read it on the EDT or copy
 * what you need. Return promptly: do the real work on a background thread
 * and register a {@link org.armedbear.j.VirtualText.Placement#REPLACE}
 * when the result is ready.
 */
public interface FenceHandler {
    /** The fence info-string word this handler claims, e.g. "mermaid". */
    boolean handles(String fenceName);

    /**
     * A claimed fence was opened or changed. {@code first} is the opening
     * fence line and {@code last} the closing one; {@code body} is the
     * text between them.
     */
    void render(Editor editor, Buffer buffer, Line first, Line last, String body);

    /**
     * The dispatch that just called {@link #render} is finished with this
     * buffer: every claimed fence in it has been handed over, and no more
     * will come until the next dispatch. A handler that keeps a
     * decoration per fence uses this to drop the ones whose fence is no
     * longer claimed -- renamed to another language, say, so its line
     * survives but no {@code render} arrives for it. The default does
     * nothing, for handlers that render each fence independently.
     */
    default void renderDone(Editor editor, Buffer buffer) {}
}
