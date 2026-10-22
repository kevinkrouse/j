/*
 * VcsCompareCommands.java
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

package org.armedbear.j.vcs;

import static org.armedbear.j.Constants.FILETYPE_BINARY;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.DiffAlignment;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.signs.EditorHunks.Side;
import org.armedbear.j.vcs.signs.SignsUpdater;

/** Comparing a file with what is staged of it. */
public final class VcsCompareCommands {
    private VcsCompareCommands() {}

    /**
     * The file being edited side by side with what is staged of it, as the
     * buffer has it, saved or not: in its encoding and its mode. A file not
     * in the index, new or in a conflict, is all added. It follows the
     * buffer: an edit, a reload or a change to what is staged shows soon
     * after.
     */
    public static void vcsCompare(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        final File file = buffer.getFile();
        if (buffer.getType() != Buffer.TYPE_NORMAL || file == null || buffer.getFileType() == FILETYPE_BINARY) {
            editor.status("Not a text file");
            return;
        }
        final VcsRepository r = file.isRemote() ? null : Repositories.forFile(file);
        if (r == null) {
            editor.status("Not a file in a repository");
            return;
        }
        final String path = r.relativePath(file);
        final Charset charset = SignsUpdater.charset(buffer);
        final List<String> lines = lines(buffer);
        final int modCount = buffer.getModCount();
        final BasesCache bases = new BasesCache(r, path, null);
        Background.start("vcs compare", () -> {
            final Bases read = bases.get();
            SwingUtilities.invokeLater(() -> {
                // Not if the window has moved on to something else meanwhile.
                if (!Editor.getEditorList().contains(editor) || editor.getBuffer() != buffer)
                    return;
                if (read == null) {
                    editor.status("Couldn't read what is staged: see vcsCommandLog");
                    return;
                }
                final Side staged = staged(read, charset);
                final Side now = Side.ofBuffer(lines, charset, staged != null && staged.crlf());
                final CompareBuffer c = CompareBuffer.open(
                    editor.getFrame(),
                    buffer.getMode(),
                    r.root() + "|" + path,
                    oldTitle(path, staged),
                    text(staged),
                    path + " (buffer)",
                    text(now));
                final Following f = new Following(c, buffer, bases);
                c.follow(f);
                buffer.addFollower(f);
                // Edited while what is staged was read.
                if (buffer.getModCount() != modCount)
                    f.textChanged();
            });
        });
    }

    /**
     * A comparison following the buffer of a file: shown again a moment after
     * the buffer's text changes, or after its version control state is
     * looked up again, which reads what is staged anew. Not while no window
     * shows it; not at all once it closes or the buffer is saved as another
     * file.
     */
    private static final class Following implements Buffer.Follower, CompareBuffer.Source {
        // How long after the last change it is shown, as the change marks are.
        private static final int DELAY_MILLIS = 100;
        // Reads and diffs, one at a time, for all of them.
        private static final ExecutorService worker =
                Executors.newSingleThreadExecutor(task -> Background.newThread("vcs compare", task));

        private final CompareBuffer comparison;
        private final Buffer buffer;
        private final BasesCache bases;
        private final Timer timer;
        // On the event thread: each showing's number, so that only the last
        // asked for is shown.
        private int generation;
        private boolean stopped;
        // Changed while out of every window.
        private boolean pending;

        Following(CompareBuffer comparison, Buffer buffer, BasesCache bases) {
            this.comparison = comparison;
            this.buffer = buffer;
            this.bases = bases;
            timer = new Timer(DELAY_MILLIS, e -> refresh());
            timer.setRepeats(false);
        }

        @Override
        public void textChanged() {
            SwingUtilities.invokeLater(timer::restart);
        }

        @Override
        public void vcsChecked() {
            bases.invalidate();
            SwingUtilities.invokeLater(timer::restart);
        }

        @Override
        public void shown() {
            if (pending) {
                pending = false;
                timer.restart();
            }
        }

        @Override
        public void stop() {
            stopped = true;
            timer.stop();
            buffer.removeFollower(this);
        }

        // The buffer's lines now; in the background what is staged, read if
        // not kept, and the two diffed; then shown.
        private void refresh() {
            if (stopped)
                return;
            if (comparison.isClosed() || !Editor.getBufferList().contains(buffer) || !ofTheFile()) {
                stop();
                return;
            }
            if (!comparison.isShown()) {
                pending = true;
                return;
            }
            final int asked = ++generation;
            final Charset charset = SignsUpdater.charset(buffer);
            final List<String> lines = lines(buffer);
            worker.execute(() -> {
                final Bases read = bases.get();
                // Not read, as while another git holds the index's lock: left
                // as it is, to be read again next time.
                if (read == null)
                    return;
                final Side staged = staged(read, charset);
                final CompareBuffer.Text oldText = text(staged);
                final CompareBuffer.Text newText = text(Side.ofBuffer(lines, charset, staged != null && staged.crlf()));
                final DiffAlignment alignment = DiffAlignment.of(oldText.keys(), newText.keys());
                SwingUtilities.invokeLater(() -> {
                    // A later one is coming, or none is wanted.
                    if (stopped || asked != generation)
                        return;
                    comparison.setOldTitle(oldTitle(bases.getPath(), staged));
                    comparison.update(oldText, newText, alignment);
                });
            });
        }

        // Whether the buffer is still of the file compared, not saved as another.
        private boolean ofTheFile() {
            final File file = buffer.getFile();
            final VcsRepository r = bases.getRepository();
            return file != null && Repositories.forFile(file) == r && bases.getPath().equals(r.relativePath(file));
        }
    }

    // What is staged of a file, or null if it is not in the index.
    private static Side staged(Bases bases, Charset charset) {
        return bases.index() == null ? null : Side.of(bases.index(), charset);
    }

    private static String oldTitle(String path, Side staged) {
        return path + (staged == null ? " (not in the index)" : " (staged)");
    }

    // A side's text; none for null.
    private static CompareBuffer.Text text(Side side) {
        return side == null
                ? new CompareBuffer.Text(List.of(), List.of())
                : new CompareBuffer.Text(side.lines(), side.keys());
    }

    // The buffer's lines, as it has them now.
    private static List<String> lines(Buffer buffer) {
        final List<String> out = new ArrayList<>();
        for (Line l = buffer.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }
}
