/*
 * Following.java
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

package org.armedbear.j.mode.compare;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.Line;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;

/**
 * A comparison of a buffer, as it has it, saved or not, with a base at the
 * left: shown again a moment after the buffer's text changes, or after its
 * version control state is looked up again, or after the base changes, as
 * seen when the frame is activated or the comparison shown. Not while no
 * window shows it; not at all once it closes or the base no longer applies.
 */
public final class Following implements Buffer.Follower, CompareBuffer.Source {
    /** What a buffer is compared with, as what is staged of its file or another file. */
    public interface Base {
        /** Its text in charset and its title now, or null if it can't be read; off the event thread. */
        Read read(Charset charset);

        /** Whether it is still buffer's, as it is not once buffer is saved as another file. */
        boolean of(Buffer buffer);

        /** Said when it can't be read at first. */
        String unreadable();

        /** buffer's version control state was looked up again. */
        default void vcsChecked() {}

        /** Whether it changed since it was last read, as a file on disk can; on the event thread. */
        default boolean changed() {
            return false;
        }
    }

    /** A base as read: its text, none if it has none, and its title. */
    public record Read(Side side, String title) {}

    // How long after the last change it is shown, as the change marks are.
    private static final int DELAY_MILLIS = 100;
    // Reads and diffs, one at a time, for all of them.
    private static final ExecutorService worker =
            Executors.newSingleThreadExecutor(task -> Background.newThread("compare", task));

    private final CompareBuffer comparison;
    private final Buffer buffer;
    private final Base base;
    private final Frame frame;
    private final Timer timer;
    // As j checks its files for changes on disk.
    private final WindowAdapter activated = new WindowAdapter() {
        @Override
        public void windowActivated(WindowEvent e) {
            if (base.changed())
                timer.restart();
        }
    };
    // On the event thread: each showing's number, so that only the last
    // asked for is shown.
    private int generation;
    private boolean stopped;
    // Changed while out of every window.
    private boolean pending;

    private Following(CompareBuffer comparison, Buffer buffer, Base base, Frame frame) {
        this.comparison = comparison;
        this.buffer = buffer;
        this.base = base;
        this.frame = frame;
        timer = new Timer(DELAY_MILLIS, e -> refresh());
        timer.setRepeats(false);
        if (frame != null)
            frame.addWindowListener(activated);
    }

    /**
     * editor's buffer, a text file, titled title, against base, across
     * editor's frame; key names the comparison, so that comparing the same
     * again shows it.
     */
    public static void open(Editor editor, String key, String title, Base base) {
        final Buffer buffer = editor.getBuffer();
        open(buffer, base, (read, side) -> {
            // Not if the window has moved on to something else meanwhile.
            if (!Editor.getEditorList().contains(editor) || editor.getBuffer() != buffer)
                return null;
            if (read == null) {
                editor.status(base.unreadable());
                return null;
            }
            return CompareBuffer.open(editor.getFrame(), buffer.getMode(), key, read.title(), read.side(), title, side);
        });
    }

    /**
     * buffer against base, read in the background: show has the base as
     * read, null if it couldn't be, and the buffer's side, and gives the
     * comparison it shows them in, followed from then on, or null for none.
     */
    public static void open(Buffer buffer, Base base, BiFunction<Read, Side, CompareBuffer> show) {
        final Charset charset = buffer.getCharset();
        final List<String> lines = lines(buffer);
        final int modCount = buffer.getModCount();
        Background.start("compare", () -> {
            final Read read = base.read(charset);
            SwingUtilities.invokeLater(() -> {
                final Side side = read == null ? null : Side.ofBuffer(lines, charset, read.side().crlf());
                final CompareBuffer c = show.apply(read, side);
                if (c == null || read == null)
                    return;
                final Following f = new Following(c, buffer, base, c.shownIn());
                c.follow(f);
                buffer.addFollower(f);
                // Edited while the base was read.
                if (buffer.getModCount() != modCount)
                    f.textChanged();
            });
        });
    }

    @Override
    public void textChanged() {
        SwingUtilities.invokeLater(timer::restart);
    }

    @Override
    public void vcsChecked() {
        base.vcsChecked();
        SwingUtilities.invokeLater(timer::restart);
    }

    @Override
    public void shown() {
        if (pending || base.changed()) {
            pending = false;
            timer.restart();
        }
    }

    @Override
    public void stop() {
        stopped = true;
        timer.stop();
        buffer.removeFollower(this);
        if (frame != null)
            frame.removeWindowListener(activated);
    }

    // The buffer's lines now; in the background the base, and the two
    // diffed; then shown.
    private void refresh() {
        if (stopped)
            return;
        if (comparison.isClosed() || !Editor.getBufferList().contains(buffer) || !base.of(buffer)) {
            stop();
            return;
        }
        if (!comparison.isShown()) {
            pending = true;
            return;
        }
        final int asked = ++generation;
        final Charset charset = buffer.getCharset();
        final List<String> lines = lines(buffer);
        final Whitespace ignored = DiffWhitespace.ignored();
        worker.execute(() -> {
            final Read read = base.read(charset);
            // Not read, as while another git holds the index's lock: left
            // as it is, to be read again next time.
            if (read == null)
                return;
            final Side newSide = Side.ofBuffer(lines, charset, read.side().crlf());
            final DiffAlignment alignment = DiffAlignment.of(read.side(), newSide, ignored);
            SwingUtilities.invokeLater(() -> {
                // A later one is coming, or none is wanted.
                if (stopped || asked != generation)
                    return;
                comparison.setOldTitle(read.title());
                comparison.update(read.side(), newSide, alignment);
            });
        });
    }

    // The buffer's lines, as it has them now.
    private static List<String> lines(Buffer buffer) {
        final List<String> out = new ArrayList<>();
        for (Line l = buffer.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }
}
