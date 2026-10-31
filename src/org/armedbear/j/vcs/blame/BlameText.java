/*
 * BlameText.java
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.vcs.blame;

import java.awt.Color;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.TextStyle;
import org.armedbear.j.VirtualText;
import org.armedbear.j.mode.web.WebBuffer;
import org.armedbear.j.util.TimeStyle;

/**
 * The current line's blame: dimmed text after the end of the caret's
 * line, who last changed it, once the caret has rested. One for the app,
 * on the event thread, following the current editor.
 */
public final class BlameText {
    static final String OWNER = "vcs.blame.line";
    // How long the caret rests before the text shows.
    static final int DELAY_MILLIS = 750;
    // Its color, this far from the background toward the text.
    private static final double SHADE = 0.35;
    // The spaces after the line's end, besides the one EOL text has.
    private static final String GAP = "  ";

    private static final Timer timer = new Timer(DELAY_MILLIS, e -> show());
    // Where the caret last rested, as show() found it: what viewChanged
    // compares against. Null while a rest is awaited.
    private static Editor restedOn;
    private static Line restedLine;
    private static int restedModCount;
    // The text shown, the buffer whose layer holds it and its line; and
    // the blame listened to.
    private static Buffer shown;
    private static Line shownLine;
    private static String shownText;
    private static Blame listened;
    private static final Runnable listener = BlameText::redraw;

    static {
        timer.setRepeats(false);
    }

    private BlameText() {}

    /** The caret, buffer or scroll may have changed: keep the text where the caret rests. */
    public static void viewChanged(Editor editor) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> viewChanged(editor));
            return;
        }
        if (editor == null || editor != Editor.currentEditor())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer == null || editor.getDot() == null)
            return;
        final Line dot = editor.getDotLine();
        // The caret resting where it was, the text unchanged: leave what is shown.
        if (editor == restedOn && dot == restedLine && buffer.getModCount() == restedModCount)
            return;
        clearText();
        restedOn = null;
        restedLine = null;
        // Another buffer: the one left stops being read now, not at the next rest.
        if (listened != null && buffer.getBlame() != listened)
            stopListening();
        // Only a window on screen: a test's editor runs no blame.
        if (editor.getDisplay().isShowing())
            timer.restart();
    }

    // The caret rested: show its line's blame, or give up.
    static void show() {
        final Editor editor = Editor.currentEditor();
        if (editor == null || editor.getBuffer() == null) {
            giveUp();
            return;
        }
        final Buffer buffer = editor.getBuffer();
        final Line dot = editor.getDot() == null ? null : editor.getDotLine();
        restedOn = editor;
        restedLine = dot;
        restedModCount = buffer.getModCount();
        if (dot == null || !qualifies(buffer)) {
            giveUp();
            return;
        }
        listen(buffer);
        draw(editor, buffer, dot);
    }

    // The blame streamed more: redraw if still resting where the text is.
    private static void redraw() {
        final Editor editor = restedOn;
        if (editor == null || editor != Editor.currentEditor() || editor.getDot() == null)
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer == null || editor.getDotLine() != restedLine)
            return;
        draw(editor, buffer, restedLine);
    }

    private static void draw(Editor editor, Buffer buffer, Line line) {
        final Blame blame = listened;
        final BlameData data = blame == null ? null : blame.data();
        if (data == null || data.hasNoHistory()) {
            clearText();
            return;
        }
        final BlameCommit commit = data.commit(line);
        if (commit == null) {
            clearText();
            return;
        }
        final String text = text(commit);
        // More streamed in, the line's the same: nothing to repaint.
        if (buffer == shown && line == shownLine && text.equals(shownText))
            return;
        clearText();
        final Formatter f = editor.getFormatter();
        final Color color = DefaultTheme.blend(f.getBackgroundColor(), f.getColor(0), SHADE);
        // No action: a click past the line's end moves the caret.
        final VirtualText.Run run =
                new VirtualText.Run(text, color, TextStyle.PLAIN, null, commit.isUncommitted() ? null : commit.hash());
        shown = buffer;
        shownLine = line;
        shownText = text;
        buffer.getVirtualText().set(OWNER, List.of(VirtualText.eol(line, List.of(VirtualText.Run.of(GAP), run))));
    }

    private static String text(BlameCommit commit) {
        if (commit.isUncommitted())
            return "Not committed yet";
        final long now = System.currentTimeMillis() / 1000;
        return commit.author() + ", " + TimeStyle.ago(commit.authorTime(), now) + " \u00b7 " + commit.summary();
    }

    private static boolean qualifies(Buffer buffer) {
        return buffer.getBooleanProperty(Property.VCS_LINE_BLAME)
                && Blame.canBlame(buffer)
                && !(buffer instanceof WebBuffer)
                && buffer.getLineCount() <= buffer.getIntegerProperty(Property.VCS_BLAME_MAX_LINES);
    }

    // Listening moves with the caret: a buffer no longer looked at stops
    // being read, unless the column listens too.
    private static void listen(Buffer buffer) {
        final Blame blame = Blame.of(buffer);
        if (blame == listened)
            return;
        stopListening();
        if (blame != null) {
            listened = blame;
            blame.addListener(listener);
        }
    }

    private static void stopListening() {
        if (listened != null) {
            listened.removeListener(listener);
            listened = null;
        }
    }

    private static void giveUp() {
        clearText();
        stopListening();
    }

    private static void clearText() {
        if (shown != null) {
            shown.getVirtualText().clear(OWNER);
            shown = null;
        }
        shownLine = null;
        shownText = null;
    }

    // Forget where the caret rested and what is shown, so a test starts clean.
    static void reset() {
        timer.stop();
        clearText();
        stopListening();
        restedOn = null;
        restedLine = null;
    }
}
