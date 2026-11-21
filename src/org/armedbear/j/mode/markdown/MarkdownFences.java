/*
 * MarkdownFences.java
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

package org.armedbear.j.mode.markdown;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.extension.FenceHandler;

/**
 * Hands the fenced blocks an extension claims (```mermaid, say) to the
 * handler that wants them. The Markdown mode finds the fences -- it
 * already parses them -- so the handler never reads the formatter's
 * internals.
 *
 * <p>Dispatched on activate and, debounced, on edit. {@link
 * FenceHandler#render} runs on the event thread and returns promptly, so
 * this class only discovers and dispatches; it never blocks. State is
 * held against a weak reference of its buffer, so a closed buffer and its
 * timer are forgotten.
 */
public final class MarkdownFences {

    // The debounce before a rescan after the last edit.
    private static final int DEBOUNCE_MILLIS = 300;

    private static final Map<Buffer, MarkdownFences> STATES = new WeakHashMap<>();

    private final WeakReference<Buffer> bufferRef;
    private final Timer debounce;
    // The buffer's modCount at the last dispatch, so a caret move that
    // edited nothing does not schedule a rescan.
    private int lastScanModCount = -1;

    private MarkdownFences(Buffer buffer) {
        this.bufferRef = new WeakReference<>(buffer);
        this.debounce = new Timer(DEBOUNCE_MILLIS, e -> dispatch());
        this.debounce.setRepeats(false);
    }

    private static synchronized MarkdownFences forBuffer(Buffer buffer) {
        return STATES.computeIfAbsent(buffer, MarkdownFences::new);
    }

    /** A Markdown buffer was activated: dispatch its claimed fences. */
    public static void onActivate(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (!isMarkdown(buffer))
            return;
        forBuffer(buffer).dispatch();
    }

    /** A command ran in a Markdown buffer: reschedule a debounced rescan. */
    public static void onEdit(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (!isMarkdown(buffer) || !Extensions.hasFenceHandlers())
            return;
        final MarkdownFences state = forBuffer(buffer);
        if (buffer.getModCount() == state.lastScanModCount)
            return;
        state.debounce.restart();
    }

    private void dispatch() {
        final Buffer buffer = bufferRef.get();
        if (buffer == null || !Extensions.hasFenceHandlers())
            return;
        // The editor showing the buffer, not the current one: the debounce
        // fires after the user may have switched windows.
        final Editor editor = editorFor(buffer);
        if (editor == null)
            return;
        for (MarkdownFormatter.FenceBlock block : MarkdownFormatter
                .claimedFences(buffer.getFirstLine(), name -> Extensions.fenceHandler(name) != null)) {
            final FenceHandler handler = Extensions.fenceHandler(block.name());
            try {
                handler.render(editor, buffer, block.first(), block.last(), block.body());
            }
            catch (Throwable t) {
                // One bad handler must not stop the others or the editor.
                Log.error("fence handler for " + block.name() + " failed: " + t);
                Log.error(t);
            }
        }
        // Tell every handler the round is over, even those that got no
        // fence: a fence renamed away from the claimed language leaves
        // its line in place, so only the handler can drop its stale
        // decoration now.
        for (FenceHandler handler : Extensions.fenceHandlers()) {
            try {
                handler.renderDone(editor, buffer);
            }
            catch (Throwable t) {
                Log.error("fence handler renderDone failed: " + t);
                Log.error(t);
            }
        }
        lastScanModCount = buffer.getModCount();
    }

    // The editor showing buffer, or null if none is.
    private static Editor editorFor(Buffer buffer) {
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                return ed;
        return null;
    }

    private static boolean isMarkdown(Buffer buffer) {
        return buffer != null && buffer.getMode() instanceof MarkdownMode;
    }
}
